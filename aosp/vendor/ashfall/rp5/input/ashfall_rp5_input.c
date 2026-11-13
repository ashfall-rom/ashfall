/*
 * AshFall RP5 built-in gamepad bridge.
 *
 * The RP5's gamepad is not a USB/HID device: the buttons, sticks and
 * triggers are sampled by an MCU which talks to the SoC over the 115200-baud
 * UART on /dev/ttyHS1.  Stock Android runs a userspace bridge from the stock
 * system image (pservice/RsMapping + librsinput.so) which reads this UART and
 * injects the result through /dev/uinput.  A GSI replaces that system image,
 * so without this daemon no input device ever appears.
 *
 * The MCU protocol (little-endian, 0xA5 0xD3 0x5A 0x3D framing) was recovered
 * from the stock librsinput.so; see docs/rp5/input.md for the evidence.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "ashfall-rp5-input"

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
#include <sys/time.h>
#include <termios.h>
#include <unistd.h>

#include <linux/input.h>
#include <linux/uinput.h>

#include <log/log.h>

#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))

#define MCU_DEV "/dev/ttyHS1"
#define MCU_DEV_STOCK "/dev/rscom"

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
    KEY_HOME,       /* bit 6  */
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

struct calib_axis {
    int center;
    int min;
    int max;
};

struct calibration {
    struct calib_axis lx, ly, rx, ry;
    int l2_min, l2_max;
    int r2_min, r2_max;
};

/* Defaults from /mnt/vendor/persist/retrostation/joys on a stock RP5. */
static struct calibration cal = {
    .lx = {125, -1134, 1196},
    .ly = {31, -1146, 1174},
    .rx = {82, -1121, 1218},
    .ry = {-6, -1167, 1192},
    .l2_min = 92,
    .l2_max = 1266,
    .r2_min = 0,
    .r2_max = 1362,
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
    struct timeval tv;

    gettimeofday(&tv, NULL);
    memset(&ev, 0, sizeof(ev));
    ev.time = tv;
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write_all(fd, &ev, sizeof(ev)) < 0)
        ALOGW("uinput write failed: %s", strerror(errno));
}

static void emit_sync(int fd)
{
    emit_event(fd, EV_SYN, SYN_REPORT, 0);
}

static int create_uinput(void)
{
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("cannot open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        ALOGE("UI_SET_EVBIT failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    for (size_t i = 0; i < ARRAY_SIZE(mcu_keymap); i++) {
        if (mcu_keymap[i] == 0)
            continue;
        if (ioctl(fd, UI_SET_KEYBIT, mcu_keymap[i]) < 0)
            ALOGW("UI_SET_KEYBIT(%u) failed: %s", mcu_keymap[i], strerror(errno));
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
        absinfo.flat = 0;
        absinfo.fuzz = 0;

        if (ioctl(fd, UI_SET_ABSBIT, code) < 0)
            ALOGW("UI_SET_ABSBIT(%u) failed: %s", code, strerror(errno));

#ifdef UI_ABS_SETUP
        {
            struct uinput_abs_setup setup;
            memset(&setup, 0, sizeof(setup));
            setup.code = code;
            setup.absinfo = absinfo;
            if (ioctl(fd, UI_ABS_SETUP, &setup) < 0)
                ALOGW("UI_ABS_SETUP(%u) failed: %s", code, strerror(errno));
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
            ALOGE("UI_DEV_SETUP failed: %s", strerror(errno));
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
        ALOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    ALOGI("uinput gamepad created");
    return fd;
}

static int parse_key_value(const char *line, const char **key, float *value)
{
    static char name[64];
    const char *eq = strchr(line, '=');
    if (!eq)
        return -1;
    size_t nlen = (size_t)(eq - line);
    if (nlen == 0 || nlen >= sizeof(name))
        return -1;
    memcpy(name, line, nlen);
    name[nlen] = '\0';
    *value = strtof(eq + 1, NULL);
    *key = name;
    return 0;
}

static void read_calibration(void)
{
    FILE *f = fopen("/mnt/vendor/persist/retrostation/joys", "r");
    char line[128];
    int seen = 0;

    if (!f) {
        ALOGI("no factory calibration (%s), using RP5 defaults", strerror(errno));
        return;
    }

    while (fgets(line, sizeof(line), f)) {
        const char *key;
        float value;
        if (parse_key_value(line, &key, &value) < 0)
            continue;
        if (!strcmp(key, "left_x_center")) cal.lx.center = (int)value;
        else if (!strcmp(key, "left_x_min")) cal.lx.min = (int)value;
        else if (!strcmp(key, "left_x_max")) cal.lx.max = (int)value;
        else if (!strcmp(key, "left_y_center")) cal.ly.center = (int)value;
        else if (!strcmp(key, "left_y_min")) cal.ly.min = (int)value;
        else if (!strcmp(key, "left_y_max")) cal.ly.max = (int)value;
        else if (!strcmp(key, "right_x_center")) cal.rx.center = (int)value;
        else if (!strcmp(key, "right_x_min")) cal.rx.min = (int)value;
        else if (!strcmp(key, "right_x_max")) cal.rx.max = (int)value;
        else if (!strcmp(key, "right_y_center")) cal.ry.center = (int)value;
        else if (!strcmp(key, "right_y_min")) cal.ry.min = (int)value;
        else if (!strcmp(key, "right_y_max")) cal.ry.max = (int)value;
        else if (!strcmp(key, "l2_min")) cal.l2_min = (int)value;
        else if (!strcmp(key, "l2_max")) cal.l2_max = (int)value;
        else if (!strcmp(key, "r2_min")) cal.r2_min = (int)value;
        else if (!strcmp(key, "r2_max")) cal.r2_max = (int)value;
        else continue;
        seen++;
    }
    fclose(f);

    ALOGI("factory calibration loaded (%d values): "
          "lx=%d[%d..%d] ly=%d[%d..%d] rx=%d[%d..%d] ry=%d[%d..%d] "
          "l2=[%d..%d] r2=[%d..%d]",
          seen,
          cal.lx.center, cal.lx.min, cal.lx.max,
          cal.ly.center, cal.ly.min, cal.ly.max,
          cal.rx.center, cal.rx.min, cal.rx.max,
          cal.ry.center, cal.ry.min, cal.ry.max,
          cal.l2_min, cal.l2_max, cal.r2_min, cal.r2_max);
}

/* Ignore the small ADC/calibration offset at rest (~5% of full travel). */
#define AXIS_DEADZONE 1500

static int axis_value(int raw, const struct calib_axis *a)
{
    int64_t v;

    /*
     * The MCU's ADC direction is opposite to the Android axis direction:
     * both the stock librsinput.so converter and the mainline rsinput driver
     * negate the raw value.  The calibration file stores a symmetric
     * min/max range, so only the sign convention changes here.
     */
    if (raw >= a->center) {
        if (a->max <= a->center)
            return 0;
        v = (int64_t)(a->center - raw) * 32768 / (a->max - a->center);
    } else {
        if (a->center <= a->min)
            return 0;
        v = (int64_t)(a->center - raw) * 32767 / (a->center - a->min);
    }
    if (v < -32768)
        v = -32768;
    if (v > 32767)
        v = 32767;
    if (v > -AXIS_DEADZONE && v < AXIS_DEADZONE)
        v = 0;
    return (int)v;
}

/*
 * Triggers are wired "backwards": at rest the raw value sits above the
 * calibrated maximum and pulling the trigger lowers it towards the minimum.
 * The stock joys file describes exactly that travel.
 */
static int trigger_value(int raw, int min, int max)
{
    int v;

    if (max <= min)
        return 0;
    v = (max - raw) * 255 / (max - min);
    if (v < 0)
        v = 0;
    if (v > 255)
        v = 255;
    return v;
}

static void handle_status(const uint8_t *data, size_t len, int uinput_fd,
                          uint16_t *prev_keys, int *prev_axes, int *good_frames)
{
    if (len < 14)
        return;

    /* The key word is big-endian; the analog values below are little-endian. */
    uint16_t keys = (uint16_t)((data[0] << 8) | data[1]);
    uint16_t l2_raw = (uint16_t)(data[2] | (data[3] << 8));
    uint16_t r2_raw = (uint16_t)(data[4] | (data[5] << 8));
    int16_t lx_raw = (int16_t)(data[6] | (data[7] << 8));
    int16_t ly_raw = (int16_t)(data[8] | (data[9] << 8));
    int16_t rx_raw = (int16_t)(data[10] | (data[11] << 8));
    int16_t ry_raw = (int16_t)(data[12] | (data[13] << 8));

    int axes[AXIS_COUNT];
    axes[AXIS_L2] = trigger_value(l2_raw, cal.l2_min, cal.l2_max);
    axes[AXIS_R2] = trigger_value(r2_raw, cal.r2_min, cal.r2_max);
    axes[AXIS_LX] = axis_value(lx_raw, &cal.lx);
    axes[AXIS_LY] = axis_value(ly_raw, &cal.ly);
    axes[AXIS_RX] = axis_value(rx_raw, &cal.rx);
    axes[AXIS_RY] = axis_value(ry_raw, &cal.ry);

    /*
     * The MCU reports the D-pad as four independent bits; Android wants a
     * HAT axis.  This is the same conversion the stock library performs in
     * gamepad_codes_convert() (BTN_DPAD_* -> ABS_HAT0X/ABS_HAT0Y).
     */
    axes[AXIS_HATX] = ((keys & KEY_BIT_RIGHT) ? 1 : 0) -
                      ((keys & KEY_BIT_LEFT) ? 1 : 0);
    axes[AXIS_HATY] = ((keys & KEY_BIT_DOWN) ? 1 : 0) -
                      ((keys & KEY_BIT_UP) ? 1 : 0);

    int changed = 0;

    if (keys != *prev_keys) {
        uint16_t key_changes = (uint16_t)(keys ^ *prev_keys);
        for (int i = 0; i < 16; i++) {
            if (!(key_changes & (1u << i)))
                continue;
            if (mcu_keymap[i] == 0)
                continue; /* D-pad bit, reported through ABS_HAT0X/Y */
            int pressed = (keys >> i) & 1;
            emit_event(uinput_fd, EV_KEY, mcu_keymap[i], pressed);
            ALOGD("key bit %d -> code %u %s", i, mcu_keymap[i],
                  pressed ? "down" : "up");
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
        ALOGI("first MCU status frame: keys=0x%04x l2=%u r2=%u "
              "lx=%d ly=%d rx=%d ry=%d hat=%d,%d",
              keys, l2_raw, r2_raw, lx_raw, ly_raw, rx_raw, ry_raw,
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
            ALOGI("MCU UART: %s", paths[i]);
            break;
        }
    }
    if (fd < 0) {
        ALOGE("cannot open MCU UART: %s", strerror(errno));
        return -1;
    }

    if (tcgetattr(fd, &tio) < 0) {
        ALOGE("tcgetattr: %s", strerror(errno));
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
        ALOGE("tcsetattr: %s", strerror(errno));
        close(fd);
        return -1;
    }
    tcflush(fd, TCIOFLUSH);
    return fd;
}

static int64_t now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

static void mcu_send_init(int fd)
{
    if (write_all(fd, mcu_init_frame, sizeof(mcu_init_frame)) < 0)
        ALOGW("MCU init write failed: %s", strerror(errno));
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
    struct sigaction sa;

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    read_calibration();

    /* The uinput node exists on every boot, but retry rather than exit if we
       happen to race ueventd; init's crash backoff could be minutes. */
    while (g_running && uinput_fd < 0) {
        uinput_fd = create_uinput();
        if (uinput_fd < 0)
            sleep(1);
    }
    if (uinput_fd < 0)
        return 1;

    /* (Re)connect to the MCU forever: the UART can disappear or go quiet when
       rs_gpio cycles MCU power across a screen off/on.  Keeping the uinput
       device alive across reconnects avoids Android seeing a new gamepad. */
    while (g_running) {
        mcu_fd = mcu_open();
        if (mcu_fd < 0) {
            sleep(1);
            continue;
        }

        /* A new session must re-report absolute axes; the kernel still holds
           the values from before the reconnect. */
        rx_len = 0;
        prev_keys = 0;
        for (size_t i = 0; i < ARRAY_SIZE(prev_axes); i++)
            prev_axes[i] = INT_MIN;
        good_frames = 0;
        last_status_ms = now_ms();

        mcu_send_init(mcu_fd);
        last_init_ms = now_ms();
        ALOGI("waiting for MCU status frames");

        while (g_running) {
            struct pollfd pfd = {.fd = mcu_fd, .events = POLLIN};
            int timeout = good_frames ? 1000 : 500;
            int rc = poll(&pfd, 1, timeout);

            if (rc < 0) {
                if (errno == EINTR)
                    continue;
                ALOGE("poll: %s", strerror(errno));
                break;
            }
            if (rc == 0) {
                int64_t now = now_ms();
                if (!good_frames || now - last_status_ms > 2000) {
                    /* MCU not ready yet, or it stopped after a screen cycle. */
                    if (now - last_init_ms >= 500) {
                        mcu_send_init(mcu_fd);
                        last_init_ms = now;
                    }
                }
                continue;
            }
            if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
                ALOGE("MCU UART error (revents=0x%x), reconnecting", pfd.revents);
                break;
            }
            if (!(pfd.revents & POLLIN))
                continue;

            ssize_t n = read(mcu_fd, rx + rx_len, sizeof(rx) - rx_len);
            if (n < 0) {
                if (errno == EINTR)
                    continue;
                ALOGE("MCU read: %s", strerror(errno));
                break;
            }
            if (n == 0) {
                ALOGE("MCU UART hung up");
                break;
            }
            rx_len += (size_t)n;

            size_t off = 0;
            while (rx_len - off >= 8) {
                if (memcmp(rx + off, mcu_header, sizeof(mcu_header)) != 0) {
                    off++;
                    continue;
                }
                size_t payload_len = (size_t)(rx[off + 6] | (rx[off + 7] << 8));
                if (payload_len > 64) {
                    off++;
                    continue;
                }
                size_t frame_len = 8 + payload_len + 1;
                if (rx_len - off < frame_len)
                    break;

                uint8_t checksum = 0;
                for (size_t i = 4; i < 8 + payload_len; i++)
                    checksum ^= rx[off + i];
                if (checksum != rx[off + 8 + payload_len]) {
                    off++;
                    continue;
                }

                uint8_t cmd = rx[off + 5];
                const uint8_t *payload = rx + off + 8;
                if (cmd == 0x02) {
                    handle_status(payload, payload_len, uinput_fd, &prev_keys,
                                  prev_axes, &good_frames);
                    last_status_ms = now_ms();
                } else if (cmd == 0x01 && payload_len >= 1 && payload[0] == 0x01) {
                    /* MCU asks to (re)initialise, e.g. after a screen cycle. */
                    if (now_ms() - last_init_ms >= 100) {
                        mcu_send_init(mcu_fd);
                        last_init_ms = now_ms();
                    }
                }
                off += frame_len;
            }

            if (off > 0) {
                memmove(rx, rx + off, rx_len - off);
                rx_len -= off;
            }
            if (rx_len == sizeof(rx))
                rx_len = 0;
        }

        close(mcu_fd);
        mcu_fd = -1;
        if (g_running)
            sleep(1);
    }

    ALOGI("stopping");
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
    }
    return 0;
}
