/*
 * AshFall RP5 built-in gamepad bridge + input extensions.
 *
 * The RP5's gamepad is not a USB/HID device: the buttons, sticks and
 * triggers are sampled by an MCU which talks to the SoC over the 115200-baud
 * UART on /dev/ttyHS1.  Stock Android runs a userspace bridge from the stock
 * system image (pservice/RsMapping + librsinput.so) which reads this UART and
 * injects the result through /dev/uinput.  A GSI replaces that system image,
 * so without this daemon no input device ever appears.
 *
 * Phase 5 additions (docs/phase5.md §2.3):
 *   - calibration load order user -> factory copy -> built-in defaults, with
 *     the factory file copied to /data/misc/ashfall/joys.factory by init;
 *   - stick/trigger deadzones from persist.ashfall.pad.* applied here, so
 *     every app and emulator benefits;
 *   - control socket /dev/socket/ashfall_input (GET/SET/RESET CAL, RAW ON/OFF).
 *
 * The MCU protocol (0xA5 0xD3 0x5A 0x3D framing) was recovered from the stock
 * librsinput.so; see docs/rp5/input.md for the evidence.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "calib.h"
#include "control.h"
#include "input.h"
#include "mapping.h"
#include "plat.h"
#include "session.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#include <linux/input.h>
#include <linux/uinput.h>

#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))

#define MCU_DEV "/dev/ttyHS1"
#define MCU_DEV_STOCK "/dev/rscom"

#define PROP_REFRESH_MS 250

/* A5 D3 5A 3D | seq | cmd | len_lo len_hi | payload | checksum */
static const uint8_t mcu_header[4] = {0xA5, 0xD3, 0x5A, 0x3D};

/*
 * "Set parameters" request for a Retroid Pocket 5 (MCU device type 5):
 *   seq=1 cmd=COMMOD(1) len=10 data={5, 1, 00 00 00 05 00 00 00 01}
 * checksum = XOR(seq..last data byte) = 0x0a.
 */
static const uint8_t mcu_init_frame[] = {
    0xA5, 0xD3, 0x5A, 0x3D, 0x01, 0x01, 0x0A, 0x00,
    0x05, 0x01, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x01, 0x0A,
};

/*
 * The key word is big-endian in the payload (data[0] is the high byte) and
 * the stock parser walks it from bit 15 down to bit 0 against the default
 * table in librsinput.so (0x2e82c).  Re-ordering the table by ascending bit
 * number gives the mapping below.
 *
 * Bits 8..11 are the D-pad.  They are not keys: the stock library converts
 * BTN_DPAD_* to ABS_HAT0X/ABS_HAT0Y in gamepad_codes_convert() before
 * injection, because Generic.kl has no BTN_DPAD_* entries.  This daemon does
 * the same (see handle_status()).
 */
static const uint16_t mcu_keymap[16] = {
    BTN_TL,         /* bit 0  */
    BTN_TR,         /* bit 1  */
    BTN_SELECT,     /* bit 2  */
    BTN_START,      /* bit 3  */
    BTN_THUMBL,     /* bit 4  */
    BTN_THUMBR,     /* bit 5  */
    KEY_HOMEPAGE,   /* bit 6: Android HOME (Generic.kl 172); KEY_HOME (102) is keyboard MOVE_HOME */
    KEY_BACK,       /* bit 7  */
    0,              /* bit 8  - D-pad up: ABS_HAT0Y */
    0,              /* bit 9  - D-pad down: ABS_HAT0Y */
    0,              /* bit 10 - D-pad left: ABS_HAT0X */
    0,              /* bit 11 - D-pad right: ABS_HAT0X */
    BTN_X,          /* bit 12 */
    BTN_Y,          /* bit 13 */
    BTN_A,          /* bit 14 */
    BTN_B,          /* bit 15 */
};

/* Payload bits for the D-pad, matching the key table above. */
#define KEY_BIT_UP    (1u << 8)
#define KEY_BIT_DOWN  (1u << 9)
#define KEY_BIT_LEFT  (1u << 10)
#define KEY_BIT_RIGHT (1u << 11)

enum {
    AXIS_L2,
    AXIS_R2,
    AXIS_LX,
    AXIS_LY,
    AXIS_RX,
    AXIS_RY,
    AXIS_HATX,
    AXIS_HATY,
    AXIS_COUNT,
};

static const uint16_t axis_codes[AXIS_COUNT] = {
    ABS_BRAKE, ABS_GAS, ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y,
};

static volatile sig_atomic_t g_running = 1;

static void on_signal(int sig)
{
    (void)sig;
    g_running = 0;
}

static int write_all(int fd, const void *buf, size_t len)
{
    const uint8_t *p = buf;

    while (len > 0) {
        ssize_t n = write(fd, p, len);

        if (n < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static void emit_event(int fd, uint16_t type, uint16_t code, int32_t value)
{
    struct input_event ev;

    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write_all(fd, &ev, sizeof(ev)) < 0)
        ah_logf(AH_LOG_WARN, "uinput write failed: %s", strerror(errno));
}

static void emit_sync(int fd)
{
    emit_event(fd, EV_SYN, SYN_REPORT, 0);
}

static int create_uinput(void)
{
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);

    if (fd < 0) {
        ah_logf(AH_LOG_ERROR, "cannot open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        ah_logf(AH_LOG_ERROR, "UI_SET_EVBIT failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    for (size_t i = 0; i < ARRAY_SIZE(mcu_keymap); i++) {
        if (mcu_keymap[i] == 0)
            continue;
        if (ioctl(fd, UI_SET_KEYBIT, mcu_keymap[i]) < 0)
            ah_logf(AH_LOG_WARN, "UI_SET_KEYBIT(%u) failed: %s", mcu_keymap[i],
                    strerror(errno));
    }

    for (size_t i = 0; i < ARRAY_SIZE(axis_codes); i++) {
        struct input_absinfo absinfo;
        uint16_t code = axis_codes[i];

        memset(&absinfo, 0, sizeof(absinfo));
        if (code == ABS_BRAKE || code == ABS_GAS) {
            absinfo.minimum = 0;
            absinfo.maximum = 255;
        } else if (code == ABS_HAT0X || code == ABS_HAT0Y) {
            absinfo.minimum = -1;
            absinfo.maximum = 1;
        } else {
            absinfo.minimum = -32768;
            absinfo.maximum = 32767;
        }

        if (ioctl(fd, UI_SET_ABSBIT, code) < 0)
            ah_logf(AH_LOG_WARN, "UI_SET_ABSBIT(%u) failed: %s", code,
                    strerror(errno));

#ifdef UI_ABS_SETUP
        {
            struct uinput_abs_setup setup;

            memset(&setup, 0, sizeof(setup));
            setup.code = code;
            setup.absinfo = absinfo;
            if (ioctl(fd, UI_ABS_SETUP, &setup) < 0)
                ah_logf(AH_LOG_WARN, "UI_ABS_SETUP(%u) failed: %s", code,
                        strerror(errno));
        }
#endif
    }

#ifdef UI_DEV_SETUP
    {
        struct uinput_setup setup;

        memset(&setup, 0, sizeof(setup));
        setup.id.bustype = BUS_USB;
        setup.id.vendor = 0x2020;
        setup.id.product = 0x3001;
        setup.id.version = 1;
        snprintf(setup.name, sizeof(setup.name), "AshFall RP5 Gamepad");
        if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
            ah_logf(AH_LOG_ERROR, "UI_DEV_SETUP failed: %s", strerror(errno));
            close(fd);
            return -1;
        }
    }
#else
    {
        struct uinput_user_dev dev;

        memset(&dev, 0, sizeof(dev));
        snprintf(dev.name, sizeof(dev.name), "AshFall RP5 Gamepad");
        dev.id.bustype = BUS_USB;
        dev.id.vendor = 0x2020;
        dev.id.product = 0x3001;
        dev.id.version = 1;
        for (size_t i = 0; i < ARRAY_SIZE(axis_codes); i++) {
            uint16_t code = axis_codes[i];

            if (code == ABS_BRAKE || code == ABS_GAS) {
                dev.absmin[code] = 0;
                dev.absmax[code] = 255;
            } else if (code == ABS_HAT0X || code == ABS_HAT0Y) {
                dev.absmin[code] = -1;
                dev.absmax[code] = 1;
            } else {
                dev.absmin[code] = -32768;
                dev.absmax[code] = 32767;
            }
        }
        if (write_all(fd, &dev, sizeof(dev)) < 0) {
            close(fd);
            return -1;
        }
    }
#endif

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        ah_logf(AH_LOG_ERROR, "UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    ah_logf(AH_LOG_INFO, "uinput gamepad created");
    return fd;
}

static void on_calibration_changed(struct input_ctx *ctx)
{
    ah_logf(AH_LOG_INFO, "calibration applied (source=%s)",
            cal_source_name(ctx->cal_source));
}

/* 16-bit key word, D-pad bits turned into HAT axes; stick axes calibrated and
 * deadzoned.  Fills both the raw sample (for RAW streaming) and the Android
 * axis array (for uinput). */
static void decode_status(const uint8_t *data, size_t len,
                          const struct input_ctx *ctx,
                          struct raw_sample *raw, int axes[AXIS_COUNT],
                          uint16_t *keys_out)
{
    uint16_t keys;
    int lx, ly, rx, ry;

    if (len < 14)
        return;

    /* The key word is big-endian; the analog values below are little-endian. */
    keys = (uint16_t)((data[0] << 8) | data[1]);
    raw->l2 = (int)(data[2] | (data[3] << 8));
    raw->r2 = (int)(data[4] | (data[5] << 8));
    raw->lx = (int)(int16_t)(data[6] | (data[7] << 8));
    raw->ly = (int)(int16_t)(data[8] | (data[9] << 8));
    raw->rx = (int)(int16_t)(data[10] | (data[11] << 8));
    raw->ry = (int)(int16_t)(data[12] | (data[13] << 8));
    raw->buttons = keys;

    lx = stick_map(raw->lx, ctx->cal.lx.center, ctx->cal.lx.min,
                   ctx->cal.lx.max);
    ly = stick_map(raw->ly, ctx->cal.ly.center, ctx->cal.ly.min,
                   ctx->cal.ly.max);
    rx = stick_map(raw->rx, ctx->cal.rx.center, ctx->cal.rx.min,
                   ctx->cal.rx.max);
    ry = stick_map(raw->ry, ctx->cal.ry.center, ctx->cal.ry.min,
                   ctx->cal.ry.max);
    stick_apply_deadzone(&lx, &ly, ctx->dz.inner_pct, ctx->dz.outer_pct);
    stick_apply_deadzone(&rx, &ry, ctx->dz.inner_pct, ctx->dz.outer_pct);

    axes[AXIS_LX] = lx;
    axes[AXIS_LY] = ly;
    axes[AXIS_RX] = rx;
    axes[AXIS_RY] = ry;

    axes[AXIS_L2] = trigger_apply_deadzone(
        trigger_map(raw->l2, ctx->cal.l2_min, ctx->cal.l2_max),
        ctx->dz.trigger_pct);
    axes[AXIS_R2] = trigger_apply_deadzone(
        trigger_map(raw->r2, ctx->cal.r2_min, ctx->cal.r2_max),
        ctx->dz.trigger_pct);

    /*
     * The MCU reports the D-pad as four independent bits; Android wants a
     * HAT axis.  This is the same conversion the stock library performs in
     * gamepad_codes_convert() (BTN_DPAD_* -> ABS_HAT0X/ABS_HAT0Y).
     */
    axes[AXIS_HATX] =
        ((keys & KEY_BIT_RIGHT) ? 1 : 0) - ((keys & KEY_BIT_LEFT) ? 1 : 0);
    axes[AXIS_HATY] =
        ((keys & KEY_BIT_DOWN) ? 1 : 0) - ((keys & KEY_BIT_UP) ? 1 : 0);

    if (keys_out)
        *keys_out = keys;
}

/*
 * Release everything Android may still think is held.  The decision logic
 * lives in session.c so the host tests can cover it; this is the uinput
 * adapter.
 */
static void sink_emit(void *ctx, uint16_t type, uint16_t code, int32_t value)
{
    emit_event(*(int *)ctx, type, code, value);
}

static void release_all(int uinput_fd, uint16_t *prev_keys, int *prev_axes)
{
    session_state st;

    st.keys = *prev_keys;
    st.axis_count = AXIS_COUNT;
    for (int i = 0; i < AXIS_COUNT; i++)
        st.axes[i] = prev_axes[i];
    if (session_release(&st, mcu_keymap, axis_codes, sink_emit, &uinput_fd) > 0)
        ah_logf(AH_LOG_INFO, "released input held across an MCU session loss");
    *prev_keys = st.keys;
    for (int i = 0; i < AXIS_COUNT; i++)
        prev_axes[i] = st.axes[i];
}

/*
 * Service the control socket while the daemon is not in an MCU session
 * (uinput retry, MCU reconnect wait, MCU quiet).  Without this a client
 * connects to /dev/socket/ashfall_input but GET/SET CAL never get a reply,
 * exactly when the calibration path is needed for diagnosis.
 */
static void control_wait(struct control *control, int timeout_ms)
{
    if (!control) {
        usleep((useconds_t)timeout_ms * 1000);
        return;
    }
    if (control_poll(control, timeout_ms) < 0 && errno != EINTR)
        ah_logf(AH_LOG_WARN, "control poll: %s", strerror(errno));
}

static void handle_status(const uint8_t *data, size_t len, int uinput_fd,
                          const struct input_ctx *ctx, uint16_t *prev_keys,
                          int *prev_axes, int *good_frames,
                          struct control *control, int64_t now_ms)
{
    struct raw_sample raw;
    int axes[AXIS_COUNT] = {0};
    uint16_t keys = 0;
    int changed = 0;

    if (len < 14)
        return;

    decode_status(data, len, ctx, &raw, axes, &keys);
    control_stream_raw(control, now_ms, &raw);

    if (keys != *prev_keys) {
        uint16_t key_changes = (uint16_t)(keys ^ *prev_keys);

        for (int i = 0; i < 16; i++) {
            if (!(key_changes & (1u << i)))
                continue;
            if (mcu_keymap[i] == 0)
                continue; /* D-pad bit, reported through ABS_HAT0X/Y */
            emit_event(uinput_fd, EV_KEY, mcu_keymap[i],
                       (keys >> i) & 1);
        }
        *prev_keys = keys;
        changed = 1;
    }

    for (int i = 0; i < AXIS_COUNT; i++) {
        if (axes[i] == prev_axes[i])
            continue;
        emit_event(uinput_fd, EV_ABS, axis_codes[i], axes[i]);
        prev_axes[i] = axes[i];
        changed = 1;
    }

    if (changed)
        emit_sync(uinput_fd);

    if ((*good_frames)++ == 0) {
        ah_logf(AH_LOG_INFO,
                "first MCU status frame: keys=0x%04x l2=%d r2=%d "
                "lx=%d ly=%d rx=%d ry=%d hat=%d,%d",
                keys, raw.l2, raw.r2, raw.lx, raw.ly, raw.rx, raw.ry,
                axes[AXIS_HATX], axes[AXIS_HATY]);
    }
}

static int mcu_open(void)
{
    const char *paths[] = {MCU_DEV, MCU_DEV_STOCK};
    struct termios tio;
    int fd = -1;

    for (size_t i = 0; i < ARRAY_SIZE(paths); i++) {
        fd = open(paths[i], O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (fd >= 0) {
            ah_logf(AH_LOG_INFO, "MCU UART: %s", paths[i]);
            break;
        }
    }
    if (fd < 0) {
        ah_logf(AH_LOG_ERROR, "cannot open MCU UART: %s", strerror(errno));
        return -1;
    }

    if (tcgetattr(fd, &tio) < 0) {
        ah_logf(AH_LOG_ERROR, "tcgetattr: %s", strerror(errno));
        close(fd);
        return -1;
    }
    tio.c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR |
                     ICRNL | IXON | IXOFF | IXANY);
    tio.c_oflag &= ~OPOST;
    tio.c_lflag &= ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
    tio.c_cflag &= ~(CSIZE | PARENB | CSTOPB | CRTSCTS);
    tio.c_cflag |= CLOCAL | CREAD | CS8;
    tio.c_cc[VMIN] = 1;
    tio.c_cc[VTIME] = 0;
    cfsetispeed(&tio, B115200);
    cfsetospeed(&tio, B115200);
    if (tcsetattr(fd, TCSANOW, &tio) < 0) {
        ah_logf(AH_LOG_ERROR, "tcsetattr: %s", strerror(errno));
        close(fd);
        return -1;
    }
    tcflush(fd, TCIOFLUSH);
    return fd;
}

static void mcu_send_init(int fd)
{
    if (write_all(fd, mcu_init_frame, sizeof(mcu_init_frame)) < 0)
        ah_logf(AH_LOG_WARN, "MCU init write failed: %s", strerror(errno));
}

int main(void)
{
    uint8_t rx[4096];
    size_t rx_len = 0;
    uint16_t prev_keys = 0;
    int prev_axes[AXIS_COUNT] = {0};
    int good_frames = 0;
    int mcu_fd = -1;
    int uinput_fd = -1;
    int64_t last_init_ms = 0;
    int64_t last_status_ms = 0;
    int64_t last_props_ms = 0;
    struct sigaction sa;
    struct input_ctx ctx;
    struct control *control;

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    ah_log_set_tag("ashfall-rp5-input");
    memset(&ctx, 0, sizeof(ctx));
    deadzones_default(&ctx.dz);
    deadzones_read(&ctx.dz);
    ctx.on_calibration_changed = on_calibration_changed;
    input_reload_calibration(&ctx);

    control = control_create(&ctx);
    if (!control)
        ah_logf(AH_LOG_WARN, "control socket unavailable");
    last_props_ms = ah_now_ms();

    /* The uinput node exists on every boot, but retry rather than exit if we
       happen to race ueventd; init's crash backoff could be minutes. */
    while (g_running && uinput_fd < 0) {
        uinput_fd = create_uinput();
        if (uinput_fd < 0)
            control_wait(control, 1000);
    }
    if (uinput_fd < 0)
        return 1;

    /* (Re)connect to the MCU forever: the UART can disappear or go quiet when
       rs_gpio cycles MCU power across a screen off/on.  Keeping the uinput
       device alive across reconnects avoids Android seeing a new gamepad. */
    while (g_running) {
        /* The MCU lost power with buttons held: release everything the kernel
         * still thinks is down before the new session starts. */
        if (prev_keys != 0 || prev_axes[AXIS_LX] != INT_MIN)
            release_all(uinput_fd, &prev_keys, prev_axes);

        mcu_fd = mcu_open();
        if (mcu_fd < 0) {
            control_wait(control, 1000); /* CAL must answer without the MCU */
            continue;
        }

        /* A new session must re-report absolute axes; the kernel still holds
           the values from before the reconnect. */
        rx_len = 0;
        prev_keys = 0;
        for (size_t i = 0; i < ARRAY_SIZE(prev_axes); i++)
            prev_axes[i] = INT_MIN;
        good_frames = 0;
        last_status_ms = ah_now_ms();

        mcu_send_init(mcu_fd);
        last_init_ms = ah_now_ms();
        ah_logf(AH_LOG_INFO, "waiting for MCU status frames");

        while (g_running) {
            struct pollfd fds[1 + CONTROL_MAX_CLIENTS];
            int nfds = 1;
            int timeout = good_frames ? PROP_REFRESH_MS : 500;
            int rc;
            int64_t now;

            fds[0].fd = mcu_fd;
            fds[0].events = POLLIN;
            fds[0].revents = 0;
            nfds += control_pollfds(control, fds + nfds,
                                    (int)ARRAY_SIZE(fds) - nfds);
            rc = poll(fds, (nfds_t)nfds, timeout);

            if (rc < 0) {
                if (errno == EINTR)
                    continue;
                ah_logf(AH_LOG_ERROR, "poll: %s", strerror(errno));
                break;
            }

            now = ah_now_ms();
            if (now - last_props_ms >= PROP_REFRESH_MS) {
                deadzones_read(&ctx.dz);
                last_props_ms = now;
            }

            if (rc > 0 && control)
                control_dispatch(control, fds + 1, nfds - 1);

            if (rc == 0) {
                if (!good_frames || now - last_status_ms > 2000) {
                    /* MCU not ready yet, or it stopped after a screen cycle.
                     * The UART usually stays open (rs_gpio cycles the MCU's
                     * power, not the TTY), so this is the only place a stuck
                     * button/axis is noticed: release before re-initialising
                     * the session. */
                    if (good_frames) {
                        release_all(uinput_fd, &prev_keys, prev_axes);
                        good_frames = 0;
                        ah_logf(AH_LOG_INFO,
                                "MCU went quiet; released held input");
                    }
                    if (now - last_init_ms >= 500) {
                        mcu_send_init(mcu_fd);
                        last_init_ms = now;
                    }
                }
                continue;
            }

            if (fds[0].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                ah_logf(AH_LOG_ERROR,
                        "MCU UART error (revents=0x%x), reconnecting",
                        fds[0].revents);
                break;
            }
            if (!(fds[0].revents & POLLIN))
                continue;

            {
                ssize_t n = read(mcu_fd, rx + rx_len, sizeof(rx) - rx_len);

                if (n < 0) {
                    if (errno == EINTR)
                        continue;
                    ah_logf(AH_LOG_ERROR, "MCU read: %s", strerror(errno));
                    break;
                }
                if (n == 0) {
                    ah_logf(AH_LOG_ERROR, "MCU UART hung up");
                    break;
                }
                rx_len += (size_t)n;
            }

            {
                size_t off = 0;

                while (rx_len - off >= 8) {
                    if (memcmp(rx + off, mcu_header, sizeof(mcu_header)) != 0) {
                        off++;
                        continue;
                    }
                    {
                        size_t payload_len =
                            (size_t)(rx[off + 6] | (rx[off + 7] << 8));
                        size_t frame_len;
                        uint8_t checksum = 0;

                        if (payload_len > 64) {
                            off++;
                            continue;
                        }
                        frame_len = 8 + payload_len + 1;
                        if (rx_len - off < frame_len)
                            break;

                        for (size_t i = 4; i < 8 + payload_len; i++)
                            checksum ^= rx[off + i];
                        if (checksum != rx[off + 8 + payload_len]) {
                            off++;
                            continue;
                        }

                        {
                            uint8_t cmd = rx[off + 5];
                            const uint8_t *payload = rx + off + 8;

                            if (cmd == 0x02) {
                                int64_t frame_now = ah_now_ms();

                                handle_status(payload, payload_len, uinput_fd,
                                              &ctx, &prev_keys, prev_axes,
                                              &good_frames, control, frame_now);
                                last_status_ms = frame_now;
                            } else if (cmd == 0x01 && payload_len >= 1 &&
                                       payload[0] == 0x01) {
                                /* MCU asks to (re)initialise, e.g. after a
                                 * screen cycle. */
                                int64_t frame_now = ah_now_ms();

                                if (frame_now - last_init_ms >= 100) {
                                    mcu_send_init(mcu_fd);
                                    last_init_ms = frame_now;
                                }
                            }
                        }
                        off += frame_len;
                    }
                }

                if (off > 0) {
                    memmove(rx, rx + off, rx_len - off);
                    rx_len -= off;
                }
                if (rx_len == sizeof(rx))
                    rx_len = 0;
            }
        }

        close(mcu_fd);
        mcu_fd = -1;
        if (g_running)
            control_wait(control, 1000);
    }

    ah_logf(AH_LOG_INFO, "stopping");
    control_destroy(control);
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
    }
    return 0;
}
