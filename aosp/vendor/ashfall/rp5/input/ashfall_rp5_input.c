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
 * Phase 6 (#138, docs/phase6.md §9.8): GamepadFusion is a *mode* of this
 * daemon, never a second one.  With persist.ashfall.pad.fusion=1 the same
 * uinput device becomes the fusion pad (VID/PID 045e:028e, "AshFall Fusion
 * Controller"), every external USB/Bluetooth gamepad is opened, grabbed and
 * merged into it, and the MCU pad contributes through the same merge.  Motion
 * nodes are never opened or grabbed (fusion.c classify rule), so Android's
 * sensor path is untouched.  With fusion off the device is exactly what it
 * has always been ("AshFall RP5 Gamepad", 2020:3001).
 *
 * The MCU protocol (0xA5 0xD3 0x5A 0x3D framing) was recovered from the stock
 * librsinput.so; see docs/rp5/input.md for the evidence.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "calib.h"
#include "control.h"
#include "fusion.h"
#include "input.h"
#include "mapping.h"
#include "plat.h"
#include "session.h"
#include "uinput_dev.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#include <linux/input.h>
#include <linux/uinput.h>

#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))

#define MCU_DEV "/dev/ttyHS1"
#define MCU_DEV_STOCK "/dev/rscom"
#define INPUT_DIR "/dev/input"

#define PROP_REFRESH_MS 250
#define MCU_OPEN_MS 1000
#define MCU_QUIET_MS 2000
#define MCU_REINIT_MS 500
#define FUSION_SCAN_MS 2000
#define SELF_NODE_MS 500
#define FUSION_FF_MAX UINPUT_DEV_FF_EFFECTS

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

/* Axis slots, in the same order as fusion.h's enum fusion_axis. */
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

/* The internal pad's fusion registry key ("2020:3001:mcu"), filled once. */
static const char *mcu_pad_key(void)
{
    static char key[FUSION_KEY_MAX];

    if (!key[0])
        fusion_key_format(FUSION_MCU_VID, FUSION_MCU_PID, FUSION_CONN_MCU,
                          key, sizeof(key));
    return key;
}

#define MCU_KEY mcu_pad_key()

/* One open external pad.  The fusion registry holds the state; this holds the
 * fds, the per-pad axis classification and the rumble relay bookkeeping. */
struct ff_relay {
    bool used;
    int src_id;
    int pad_id;
    struct ff_effect effect;
};

struct pad_handle {
    int fd;
    bool writable; /* opened O_RDWR: needed for EVIOCSFF and EV_FF */
    char node[64];
    char key[FUSION_KEY_MAX];
    int axis_slot[FUSION_ABS_MAX];
    struct ff_relay ff[FUSION_FF_MAX];
};

struct daemon {
    struct input_ctx ctx;
    struct fusion_state fusion;
    struct control *control;

    struct pad_handle pads[FUSION_MAX_PADS];

    /* Effects uploaded to the fusion uinput device, replayed to the pads. */
    struct {
        bool used;
        int id;
        struct ff_effect effect;
    } effects[FUSION_FF_MAX];

    int mcu_fd;
    int uinput_fd;
    int own_fd;    /* our own event node, for EV_FF play events */
    int notify_fd; /* inotify watch on /dev/input */

    bool fusion_device; /* the uinput device currently is the fusion pad */
    bool grabbed;       /* a tester owns GRAB ON */
    bool rumble;        /* the uinput device really advertises EV_FF */

    uint8_t rx[4096];
    size_t rx_len;
    uint16_t prev_keys;
    int prev_axes[AXIS_COUNT];
    int good_frames;

    int cal_retries;
    int64_t last_cal_ms;
    int64_t last_props_ms;
    int64_t last_mcu_ms;
    int64_t last_uinput_ms;
    int64_t last_init_ms;
    int64_t last_status_ms;
    int64_t last_scan_ms;
    int64_t last_self_ms;
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

/* --- uinput device -------------------------------------------------------- */

/*
 * The capability plan and the ioctl sequence live in uinput_dev.c so the host
 * tests can assert them (#156): the kernel only reports a bad capability at
 * UI_DEV_CREATE, and "Invalid argument" there is the same for a wrong bit, a
 * wrong order and a missing ff_effects_max.  These are the syscalls.
 */
static int uinput_open(void *ctx)
{
    (void)ctx;
    /*
     * O_NONBLOCK is required, not an optimisation: the fd is also read for
     * EV_UINPUT/EV_FF, and a blocking read on an empty queue parks in
     * uinput_read() holding the device mutex - the input loop, the next
     * UI_DEV_DESTROY and every later write would wait forever.
     */
    return open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
}

static int uinput_ioctl(void *ctx, int fd, unsigned long request,
                        unsigned long arg)
{
    (void)ctx;
    return ioctl(fd, request, arg);
}

static int uinput_write(void *ctx, int fd, const void *buf, size_t len)
{
    (void)ctx;
    return write_all(fd, buf, len);
}

static void uinput_close(void *ctx, int fd)
{
    (void)ctx;
    close(fd);
}

static const struct uinput_dev_ops uinput_ops = {
    .ctx = NULL,
    .open = uinput_open,
    .ioctl = uinput_ioctl,
    .write = uinput_write,
    .close = uinput_close,
};

/*
 * One uinput device, two identities (#138): fusion off is the device this
 * daemon has always created; fusion on is the well-known Xbox pad, plus force
 * feedback bits so rumble can be relayed to the physical pads.  The
 * identities cannot be switched in place, so a toggle destroys and recreates
 * the device - one device at a time, never two.
 */
static int create_uinput(struct daemon *d, bool fusion_on)
{
    struct uinput_dev_spec spec;
    bool rumble = false;
    int fd;

    memset(&spec, 0, sizeof(spec));
    spec.name = fusion_on ? FUSION_NAME_ON : FUSION_NAME_OFF;
    spec.bustype = BUS_USB;
    spec.vendor = fusion_on ? FUSION_VID : FUSION_MCU_VID;
    spec.product = fusion_on ? FUSION_PID : FUSION_MCU_PID;
    spec.version = 1;
    if (fusion_on) {
        spec.keys = fusion_key_caps;
        spec.key_count = fusion_key_cap_count;
        spec.rumble = true;
    } else {
        spec.keys = mcu_keymap;
        spec.key_count = (int)ARRAY_SIZE(mcu_keymap);
    }
    spec.axes = axis_codes;
    spec.axis_count = (int)ARRAY_SIZE(axis_codes);

    fd = uinput_dev_create(&uinput_ops, &spec, &rumble);
    d->rumble = rumble;
    if (fd < 0) {
        ah_logf(AH_LOG_ERROR, "uinput %s: %s%s", spec.name, strerror(errno),
                fusion_on ? " (EV_FF/rumble, then without)" : "");
        return -1;
    }
    if (fusion_on && !rumble)
        ah_logf(AH_LOG_WARN,
                "uinput: kernel refused EV_FF/FF_RUMBLE, rumble relay off");
    ah_logf(AH_LOG_INFO, "uinput %s created (%04x:%04x%s)", spec.name,
            spec.vendor, spec.product, rumble ? ", rumble" : "");
    return fd;
}

/* "/dev/input" + "/" + a (short) event name; never truncates silently, never
 * overflows. */
static void join_path(char *buf, size_t len, const char *dir, const char *name)
{
    size_t dlen = strlen(dir);
    size_t nlen = name ? strlen(name) : 0;

    if (len == 0)
        return;
    if (dlen + 1 >= len) {
        dlen = len - 2;
        nlen = 0;
    } else if (dlen + 1 + nlen >= len) {
        nlen = len - dlen - 2;
    }
    memcpy(buf, dir, dlen);
    buf[dlen] = '/';
    if (nlen)
        memcpy(buf + dlen + 1, name, nlen);
    buf[dlen + 1 + nlen] = '\0';
}

/* Our own event node: needed to read EV_FF play events back from the device we
 * created (a uinput device cannot receive them any other way). */
static int open_self_node(void)
{
    DIR *dir = opendir(INPUT_DIR);

    if (!dir)
        return -1;
    for (struct dirent *entry = readdir(dir); entry; entry = readdir(dir)) {
        struct input_id id;
        char name[FUSION_NAME_MAX];
        char path[64];
        int fd;

        if (strncmp(entry->d_name, "event", 5) != 0)
            continue;
        join_path(path, sizeof(path), INPUT_DIR, entry->d_name);
        fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0)
            continue;
        memset(&id, 0, sizeof(id));
        name[0] = '\0';
        if (ioctl(fd, EVIOCGID, &id) == 0 &&
            ioctl(fd, EVIOCGNAME(sizeof(name)), name) > 0 &&
            fusion_is_self(id.vendor, id.product, name)) {
            /* No EVIOCSMASK here: a per-type mask only filters the codes
             * listed for that type, so keys and axes would still be read
             * back.  Non-FF events from our own node are ignored below. */
            closedir(dir);
            return fd;
        }
        close(fd);
    }
    closedir(dir);
    return -1;
}

static void sink_emit(void *ctx, uint16_t type, uint16_t code, int32_t value)
{
    struct daemon *d = ctx;

    if (!d || d->uinput_fd < 0)
        return;
    emit_event(d->uinput_fd, type, code, value);
}

/* --- external pad registry ------------------------------------------------ */

struct pad_probe {
    struct input_id id;
    char name[FUSION_NAME_MAX];
    enum fusion_connection connection;
    struct fusion_absinfo absinfo[FUSION_ABS_MAX];
    uint16_t axis_src[FUSION_AXIS_COUNT];
    /* What the fusion registry asked for: a mergeable node plus its stable
     * vendor:product:connection key. */
    struct fusion_node node;
    char key[FUSION_KEY_MAX];
    bool motion_only; /* log only: the node is left on the sensor path */
};

static enum fusion_connection connection_of(const struct input_id *id)
{
    if (id->bustype == BUS_BLUETOOTH)
        return FUSION_CONN_BT;
    return FUSION_CONN_USB;
}

/*
 * The stock classification (#138): a gamepad carries EV_KEY plus one of
 * BTN_GAMEPAD/BTN_SOUTH/BTN_A/BTN_TRIGGER and at least one EV_ABS axis.  A
 * motion-only node fails this test and is therefore never opened, never
 * grabbed, and keeps feeding Android's sensor path.  Our own uinput device is
 * excluded too, or fusion would feed back into itself.  The decision itself is
 * fusion_node_mergeable() so the host tests cover it (#156).
 */
static bool probe_pad(int fd, struct pad_probe *out)
{
    uint8_t keybits[FUSION_BITS_BYTES(KEY_MAX + 1)];
    uint8_t absbits[FUSION_BITS_BYTES(ABS_MAX + 1)];
    uint8_t props[FUSION_BITS_BYTES(INPUT_PROP_MAX + 1)];

    memset(out, 0, sizeof(*out));
    memset(keybits, 0, sizeof(keybits));
    memset(absbits, 0, sizeof(absbits));
    memset(props, 0, sizeof(props));

    if (ioctl(fd, EVIOCGID, &out->id) < 0)
        return false;
    if (ioctl(fd, EVIOCGNAME(sizeof(out->name)), out->name) <= 0)
        out->name[0] = '\0';
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0)
        memset(keybits, 0, sizeof(keybits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) < 0)
        memset(absbits, 0, sizeof(absbits));
    if (ioctl(fd, EVIOCGPROP(sizeof(props)), props) < 0)
        memset(props, 0, sizeof(props));

    out->connection = connection_of(&out->id);
    out->node.vendor = out->id.vendor;
    out->node.product = out->id.product;
    out->node.connection = out->connection;
    out->node.name = out->name;
    out->node.gamepad =
        fusion_classify(keybits, sizeof(keybits), absbits, sizeof(absbits),
                        props, sizeof(props));
    out->node.motion_only =
        fusion_is_motion_only(keybits, sizeof(keybits), absbits, sizeof(absbits),
                              props, sizeof(props));
    out->motion_only = out->node.motion_only;
    if (!fusion_node_mergeable(&out->node, out->key, sizeof(out->key)))
        return false;

    for (int code = 0; code <= ABS_MAX; code++) {
        struct input_absinfo info;

        if (!fusion_bits_test(absbits, code))
            continue;
        memset(&info, 0, sizeof(info));
        if (ioctl(fd, EVIOCGABS(code), &info) < 0)
            continue;
        out->absinfo[code].present = true;
        out->absinfo[code].minimum = info.minimum;
        out->absinfo[code].maximum = info.maximum;
    }
    fusion_axis_sources(out->absinfo, out->axis_src);
    return true;
}

static int pad_by_node(const struct daemon *d, const char *node)
{
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        if (d->pads[i].fd >= 0 && !strcmp(d->pads[i].node, node))
            return i;
    }
    return -1;
}

static int pad_free_slot(const struct daemon *d)
{
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        if (d->pads[i].fd < 0)
            return i;
    }
    return -1;
}

static void pad_drop(struct daemon *d, int slot, const char *reason)
{
    struct pad_handle *pad = &d->pads[slot];
    int zero = 0;

    if (pad->fd < 0)
        return;
    /* Explicit release before close: close() would do it too, but an explicit
     * ungrab keeps the intent visible and runs before the fd disappears. */
    ioctl(pad->fd, EVIOCGRAB, &zero);
    close(pad->fd);
    if (pad->key[0]) {
        fusion_pad_remove(&d->fusion, pad->key);
        ah_logf(AH_LOG_INFO, "fusion: pad %s removed%s%s", pad->key,
                reason ? " (" : "", reason ? reason : "");
    }
    memset(pad, 0, sizeof(*pad));
    pad->fd = -1;
}

static void pad_drop_all(struct daemon *d)
{
    for (int i = 0; i < FUSION_MAX_PADS; i++)
        pad_drop(d, i, NULL);
}

static void fusion_scan(struct daemon *d)
{
    DIR *dir;
    struct dirent *entry;

    if (!d->fusion.enabled)
        return;
    dir = opendir(INPUT_DIR);
    if (!dir) {
        ah_logf(AH_LOG_WARN, "fusion: cannot scan %s: %s", INPUT_DIR,
                strerror(errno));
        return;
    }
    for (entry = readdir(dir); entry; entry = readdir(dir)) {
        struct pad_probe probe;
        struct fusion_pad *pad;
        struct pad_handle *handle;
        char node[64];
        int slot;
        int fd;
        bool writable;

        if (strncmp(entry->d_name, "event", 5) != 0)
            continue;
        join_path(node, sizeof(node), INPUT_DIR, entry->d_name);
        if (pad_by_node(d, node) >= 0)
            continue;
        slot = pad_free_slot(d);
        if (slot < 0) {
            ah_logf(AH_LOG_WARN, "fusion: more than %d pads, ignoring %s",
                    FUSION_MAX_PADS, node);
            break;
        }

        /*
         * Classify read-only first, so a motion-only node is probed once and
         * closed again - never grabbed, never held, so Android's sensor path
         * keeps it.  Only a confirmed gamepad is reopened read-write, which is
         * what force feedback needs.
         */
        fd = open(node, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0)
            continue;
        if (!probe_pad(fd, &probe)) {
            close(fd);
            continue;
        }
        writable = false;
        {
            int rw = open(node, O_RDWR | O_NONBLOCK | O_CLOEXEC);

            if (rw >= 0) {
                close(fd);
                fd = rw;
                writable = true;
            }
        }
        /*
         * Grab before the pad reaches the merge.  A pad we cannot grab must
         * not be merged either: apps would see the fusion pad *and* the
         * physical one - exactly the double input fusion exists to remove.
         */
        {
            int one = 1;

            if (ioctl(fd, EVIOCGRAB, &one) < 0) {
                ah_logf(AH_LOG_WARN,
                        "fusion: cannot grab %04x:%04x (%s), not merged: %s",
                        probe.id.vendor, probe.id.product, node,
                        strerror(errno));
                close(fd);
                continue;
            }
        }

        /* The key the registry decided on (vendor:product:connection), logged
         * by fusion_pad_add, so a BT and a USB session of the same pad keep
         * distinct, stable keys. */
        pad = fusion_pad_add(&d->fusion, probe.key, probe.name,
                             probe.connection, ah_now_ms());
        if (!pad) {
            close(fd);
            continue;
        }

        if (!writable)
            ah_logf(AH_LOG_WARN,
                    "fusion: %s is read-only, rumble relay unavailable",
                    pad->key);

        handle = &d->pads[slot];
        memset(handle, 0, sizeof(*handle));
        handle->fd = fd;
        handle->writable = writable;
        snprintf(handle->node, sizeof(handle->node), "%s", node);
        snprintf(handle->key, sizeof(handle->key), "%s", pad->key);
        for (int i = 0; i < FUSION_ABS_MAX; i++)
            handle->axis_slot[i] = -1;
        for (int axis = 0; axis < FUSION_AXIS_COUNT; axis++) {
            uint16_t code = probe.axis_src[axis];

            if (code == 0 || code > ABS_MAX)
                continue;
            handle->axis_slot[code] = axis;
            /* Rescale pads that do not speak the uinput range (-127..127
             * sticks, 10-bit triggers) instead of clamping them. */
            if (probe.absinfo[code].present)
                fusion_pad_set_axis_range(&d->fusion,
                                          fusion_pad_index(&d->fusion,
                                                           pad->key),
                                          axis, probe.absinfo[code].minimum,
                                          probe.absinfo[code].maximum);
        }
        ah_logf(AH_LOG_INFO, "fusion: pad %s \"%s\" grabbed (%s)", pad->key,
                pad->name, writable ? "rw" : "ro");
    }
    closedir(dir);
}

/* Drop pads whose node went away even if we missed the inotify event. */
static void fusion_check_missing(struct daemon *d)
{
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        if (d->pads[i].fd >= 0 && access(d->pads[i].node, F_OK) != 0)
            pad_drop(d, i, "gone");
    }
}

static bool pad_read(struct daemon *d, int slot)
{
    struct pad_handle *pad = &d->pads[slot];
    struct input_event events[64];
    int index = fusion_pad_index(&d->fusion, pad->key);
    ssize_t n;

    while ((n = read(pad->fd, events, sizeof(events))) > 0) {
        size_t count = (size_t)n / sizeof(events[0]);

        for (size_t i = 0; i < count && index >= 0; i++) {
            if (events[i].type == EV_KEY) {
                /* The device capabilities are fixed at creation; merging a
                 * code we never advertised would only add phantom state. */
                if (fusion_key_supported(events[i].code))
                    fusion_key_event(&d->fusion, index, events[i].code,
                                     events[i].value);
            } else if (events[i].type == EV_ABS) {
                int axis = events[i].code <= ABS_MAX
                               ? pad->axis_slot[events[i].code]
                               : -1;

                if (axis >= 0)
                    fusion_abs_event(&d->fusion, index, axis, events[i].value,
                                     ah_now_ms());
            }
        }
    }
    if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
        return false;
    return true;
}

/* --- force feedback relay ------------------------------------------------- */

static const struct ff_effect *effect_find(const struct daemon *d, int id)
{
    for (int i = 0; i < FUSION_FF_MAX; i++) {
        if (d->effects[i].used && d->effects[i].id == id)
            return &d->effects[i].effect;
    }
    return NULL;
}

/* Effect ids are per device: a recreated uinput node starts from scratch. */
static void effects_reset(struct daemon *d)
{
    memset(d->effects, 0, sizeof(d->effects));
}

static void effect_remember(struct daemon *d, const struct ff_effect *effect)
{
    for (int i = 0; i < FUSION_FF_MAX; i++) {
        if (d->effects[i].used && d->effects[i].id == effect->id) {
            d->effects[i].effect = *effect;
            return;
        }
        if (!d->effects[i].used) {
            d->effects[i].used = true;
            d->effects[i].id = effect->id;
            d->effects[i].effect = *effect;
            return;
        }
    }
}

static void effect_forget(struct daemon *d, int id)
{
    for (int i = 0; i < FUSION_FF_MAX; i++) {
        if (d->effects[i].used && d->effects[i].id == id) {
            d->effects[i].used = false;
            return;
        }
    }
}

/* Upload `src` to one pad the first time a play request arrives, then replay
 * every play/stop with that pad's own effect id.  Any error is logged and the
 * pad skipped: a pad that stopped answering must never stall the input loop. */
static int pad_effect(struct pad_handle *pad, const struct ff_effect *src)
{
    struct ff_effect effect;

    for (int i = 0; i < FUSION_FF_MAX; i++) {
        if (pad->ff[i].used && pad->ff[i].src_id == src->id)
            return pad->ff[i].pad_id;
    }
    for (int i = 0; i < FUSION_FF_MAX; i++) {
        if (pad->ff[i].used)
            continue;
        effect = *src;
        effect.id = -1;
        if (ioctl(pad->fd, EVIOCSFF, &effect) < 0) {
            ah_logf(AH_LOG_WARN, "fusion: EVIOCSFF on %s: %s", pad->key,
                    strerror(errno));
            return -1;
        }
        pad->ff[i].used = true;
        pad->ff[i].src_id = src->id;
        pad->ff[i].pad_id = effect.id;
        pad->ff[i].effect = effect;
        return effect.id;
    }
    return -1;
}

static void ff_relay(struct daemon *d, int id, int value)
{
    const struct ff_effect *src = effect_find(d, id);

    /* No EV_FF on the device (kernel refused it): nothing to relay. */
    if (!d->rumble || !src || value < 0)
        return;
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        struct pad_handle *pad = &d->pads[i];
        struct input_event ev;
        int pad_id;

        if (pad->fd < 0 || !pad->writable)
            continue;
        pad_id = pad_effect(pad, src);
        if (pad_id < 0)
            continue;
        memset(&ev, 0, sizeof(ev));
        ev.type = EV_FF;
        ev.code = (uint16_t)pad_id;
        ev.value = value;
        if (write(pad->fd, &ev, sizeof(ev)) < 0)
            ah_logf(AH_LOG_WARN, "fusion: FF replay to %s: %s", pad->key,
                    strerror(errno));
    }
}

/*
 * Read back what the kernel tells the uinput device: effect uploads/erases
 * (EV_UINPUT, only on the uinput fd) and play/stop requests (EV_FF, delivered
 * to the event node; some kernels also mirror them to the uinput fd).
 */
static void drain_self(struct daemon *d, int fd)
{
    struct input_event events[32];
    ssize_t n;

    while ((n = read(fd, events, sizeof(events))) > 0) {
        size_t count = (size_t)n / sizeof(events[0]);

        for (size_t i = 0; i < count; i++) {
            if (events[i].type == EV_FF) {
                ff_relay(d, (int)events[i].code, events[i].value);
                continue;
            }
#if defined(EV_UINPUT) && defined(UI_BEGIN_FF_UPLOAD) && \
    defined(UI_BEGIN_FF_ERASE)
            if (events[i].type == EV_UINPUT && fd == d->uinput_fd) {
                if (events[i].code == UI_FF_UPLOAD) {
                    struct uinput_ff_upload upload;

                    memset(&upload, 0, sizeof(upload));
                    upload.request_id = (__u32)events[i].value;
                    if (ioctl(fd, UI_BEGIN_FF_UPLOAD, &upload) == 0) {
                        effect_remember(d, &upload.effect);
                        upload.retval = 0;
                        ioctl(fd, UI_END_FF_UPLOAD, &upload);
                    }
                } else if (events[i].code == UI_FF_ERASE) {
                    struct uinput_ff_erase erase;

                    memset(&erase, 0, sizeof(erase));
                    erase.request_id = (__u32)events[i].value;
                    if (ioctl(fd, UI_BEGIN_FF_ERASE, &erase) == 0) {
                        effect_forget(d, (int)erase.effect_id);
                        erase.retval = 0;
                        ioctl(fd, UI_END_FF_ERASE, &erase);
                    }
                }
            }
#endif
        }
    }
}

/* --- MCU ------------------------------------------------------------------ */

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
static void mcu_release(struct daemon *d)
{
    session_state st;

    st.keys = d->prev_keys;
    st.axis_count = AXIS_COUNT;
    for (int i = 0; i < AXIS_COUNT; i++)
        st.axes[i] = d->prev_axes[i];
    if (session_release(&st, mcu_keymap, axis_codes, sink_emit, d) > 0)
        ah_logf(AH_LOG_INFO, "released input held across an MCU session loss");
    d->prev_keys = st.keys;
    for (int i = 0; i < AXIS_COUNT; i++)
        d->prev_axes[i] = st.axes[i];
}

/* The MCU state is either the uinput device itself (fusion off) or one pad of
 * the merge (fusion on). */
static void mcu_release_state(struct daemon *d)
{
    if (d->fusion_device) {
        int index = fusion_pad_index(&d->fusion, MCU_KEY);

        if (index >= 0)
            fusion_pad_clear(&d->fusion, index);
    } else {
        mcu_release(d);
    }
    d->good_frames = 0;
}

static void mcu_to_fusion(struct daemon *d, uint16_t keys,
                          const int axes[AXIS_COUNT], int64_t now_ms)
{
    int index = fusion_pad_index(&d->fusion, MCU_KEY);

    if (index < 0)
        return;
    for (int i = 0; i < 16; i++) {
        if (mcu_keymap[i] == 0)
            continue;
        fusion_key_event(&d->fusion, index, mcu_keymap[i],
                         (keys >> i) & 1);
    }
    for (int i = 0; i < AXIS_COUNT; i++)
        fusion_abs_event(&d->fusion, index, i, axes[i], now_ms);
}

static void handle_status(struct daemon *d, const uint8_t *data, size_t len)
{
    struct raw_sample raw;
    int axes[AXIS_COUNT] = {0};
    uint16_t keys = 0;
    int64_t now_ms;
    int changed = 0;

    if (len < 14)
        return;

    now_ms = ah_now_ms();
    decode_status(data, len, &d->ctx, &raw, axes, &keys);
    control_stream_raw(d->control, now_ms, &raw);

    if (d->grabbed) {
        /*
         * GRAB ON: every button (including Home, Back, Select, Start and B) is
         * only visible in the RAW stream; nothing is injected into uinput and
         * the "last reported" state is left alone so GRAB OFF re-reports
         * everything from scratch.
         */
        if (d->good_frames++ == 0)
            ah_logf(AH_LOG_INFO,
                    "first grabbed MCU status frame: keys=0x%04x "
                    "l2=%d r2=%d lx=%d ly=%d rx=%d ry=%d",
                    keys, raw.l2, raw.r2, raw.lx, raw.ly, raw.rx, raw.ry);
        return;
    }

    if (d->fusion_device) {
        mcu_to_fusion(d, keys, axes, now_ms);
        d->good_frames++;
        return;
    }

    if (keys != d->prev_keys) {
        uint16_t key_changes = (uint16_t)(keys ^ d->prev_keys);

        for (int i = 0; i < 16; i++) {
            if (!(key_changes & (1u << i)))
                continue;
            if (mcu_keymap[i] == 0)
                continue; /* D-pad bit, reported through ABS_HAT0X/Y */
            emit_event(d->uinput_fd, EV_KEY, mcu_keymap[i], (keys >> i) & 1);
        }
        d->prev_keys = keys;
        changed = 1;
    }

    for (int i = 0; i < AXIS_COUNT; i++) {
        if (axes[i] == d->prev_axes[i])
            continue;
        emit_event(d->uinput_fd, EV_ABS, axis_codes[i], axes[i]);
        d->prev_axes[i] = axes[i];
        changed = 1;
    }

    if (changed)
        emit_sync(d->uinput_fd);

    if (d->good_frames++ == 0) {
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

/* Parse every complete frame in d->rx; called after each read. */
static void mcu_parse(struct daemon *d)
{
    size_t off = 0;

    while (d->rx_len - off >= 8) {
        size_t payload_len;
        size_t frame_len;
        uint8_t checksum = 0;

        if (memcmp(d->rx + off, mcu_header, sizeof(mcu_header)) != 0) {
            off++;
            continue;
        }
        payload_len = (size_t)(d->rx[off + 6] | (d->rx[off + 7] << 8));
        if (payload_len > 64) {
            off++;
            continue;
        }
        frame_len = 8 + payload_len + 1;
        if (d->rx_len - off < frame_len)
            break;

        for (size_t i = 4; i < 8 + payload_len; i++)
            checksum ^= d->rx[off + i];
        if (checksum != d->rx[off + 8 + payload_len]) {
            off++;
            continue;
        }

        {
            uint8_t cmd = d->rx[off + 5];
            const uint8_t *payload = d->rx + off + 8;

            if (cmd == 0x02) {
                int64_t now = ah_now_ms();

                handle_status(d, payload, payload_len);
                d->last_status_ms = now;
            } else if (cmd == 0x01 && payload_len >= 1 &&
                       payload[0] == 0x01) {
                /* MCU asks to (re)initialise, e.g. after a screen cycle. */
                int64_t now = ah_now_ms();

                if (now - d->last_init_ms >= 100) {
                    mcu_send_init(d->mcu_fd);
                    d->last_init_ms = now;
                }
            }
        }
        off += frame_len;
    }

    if (off > 0) {
        memmove(d->rx, d->rx + off, d->rx_len - off);
        d->rx_len -= off;
    }
    if (d->rx_len == sizeof(d->rx))
        d->rx_len = 0;
}

/* --- fusion mode transitions ---------------------------------------------- */

/* Fusion on: the uinput device becomes the Xbox pad and every external pad is
 * grabbed and merged.  Fusion off: none of that, exactly the phase 5 device. */
static void apply_fusion_mode(struct daemon *d, bool on)
{
    /* Release what the old device still shows before it is destroyed. */
    if (d->fusion_device && !d->grabbed)
        fusion_release(&d->fusion, sink_emit, d);
    else if (!d->fusion_device && !d->grabbed)
        mcu_release(d);

    if (!on) {
        pad_drop_all(d);
        fusion_clear_pads(&d->fusion);
    }
    if (d->own_fd >= 0) {
        close(d->own_fd);
        d->own_fd = -1;
    }
    if (d->uinput_fd >= 0) {
        ioctl(d->uinput_fd, UI_DEV_DESTROY);
        close(d->uinput_fd);
        d->uinput_fd = -1;
    }
    d->uinput_fd = create_uinput(d, on);
    d->fusion_device = on;
    effects_reset(d);
    d->prev_keys = 0;
    for (int i = 0; i < AXIS_COUNT; i++)
        d->prev_axes[i] = INT_MIN;
    fusion_reset_reported(&d->fusion);
    if (d->uinput_fd < 0) {
        ah_logf(AH_LOG_ERROR,
                "fusion %s: uinput device unavailable, retrying",
                on ? "ON" : "OFF");
        return;
    }
    if (on) {
        d->own_fd = open_self_node();
        fusion_pad_add(&d->fusion, MCU_KEY, "RP5 internal (MCU)",
                       FUSION_CONN_MCU, ah_now_ms());
        fusion_scan(d);
        ah_logf(AH_LOG_INFO, "fusion ON: %d pad(s) merged",
                d->fusion.pad_count);
    } else {
        ah_logf(AH_LOG_INFO, "fusion OFF: physical pads visible again");
    }
}

static void fusion_rescan(struct daemon *d)
{
    if (d->uinput_fd < 0 || !d->fusion.enabled)
        return;
    fusion_check_missing(d);
    fusion_scan(d);
    ah_logf(AH_LOG_INFO, "fusion: rescan, %d pad(s)", d->fusion.pad_count);
}

/*
 * Apply a GRAB ON/OFF transition.  GRAB ON releases everything Android still
 * thinks is held and then suppresses all uinput output; GRAB OFF arms a full
 * re-report (keys 0, every axis dirty) so the next MCU frame restores the
 * current physical state.  Auto-release on client disconnect goes through the
 * same path.
 */
static void sync_grab(struct daemon *d)
{
    bool now = control_grabbed(d->control);

    if (now == d->grabbed)
        return;
    if (now) {
        if (d->fusion_device)
            fusion_release(&d->fusion, sink_emit, d);
        else
            mcu_release(d);
        ah_logf(AH_LOG_INFO, "GRAB ON: uinput output suppressed");
    } else {
        d->prev_keys = 0;
        for (int i = 0; i < AXIS_COUNT; i++)
            d->prev_axes[i] = INT_MIN;
        fusion_reset_reported(&d->fusion);
        ah_logf(AH_LOG_INFO, "GRAB OFF: uinput output resumed");
    }
    d->grabbed = now;
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

/*
 * #80: class core can start before init's `copy` of the factory calibration
 * (and a failed copy is only visible later), so a daemon that read nothing at
 * startup would keep the built-in defaults forever.  While the active source
 * is `default`, retry the load order every 2 s for the first minute.  Called
 * from every wait path, not only the MCU session loop.
 */
static void retry_calibration(struct input_ctx *ctx, int *tries, int64_t *last_ms)
{
    int64_t now = ah_now_ms();

    if (ctx->cal_source != CAL_SOURCE_DEFAULT) {
        *tries = 0;
        return;
    }
    if (*tries >= 30 || now - *last_ms < 2000)
        return;
    *last_ms = now;
    (*tries)++;
    input_reload_calibration(ctx);
}

/* --- main loop ------------------------------------------------------------ */

static void drain_inotify(struct daemon *d)
{
    char buf[sizeof(struct inotify_event) + NAME_MAX + 1];

    for (;;) {
        ssize_t n = read(d->notify_fd, buf, sizeof(buf));
        char *p;

        if (n <= 0)
            break;
        for (p = buf; p + sizeof(struct inotify_event) <= buf + n;) {
            const struct inotify_event *event =
                (const struct inotify_event *)p;

            p += sizeof(*event) + event->len;
            /* A hotplugged pad is a new event node: say so once (logcat
             * evidence for #156), then let the scan pick it up now instead of
             * on the next 2 s tick. */
            if (!fusion_hotplug_scan(event->mask,
                                     event->len ? event->name : ""))
                continue;
            d->last_scan_ms = 0;
            if (event->len > 0 && strncmp(event->name, "event", 5) == 0)
                ah_logf(AH_LOG_INFO, "fusion: hotplug %s, rescanning",
                        event->name);
        }
    }
}

static int inotify_start(void)
{
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);

    if (fd < 0) {
        ah_logf(AH_LOG_WARN, "fusion: inotify: %s", strerror(errno));
        return -1;
    }
    if (inotify_add_watch(fd, INPUT_DIR,
                          IN_CREATE | IN_DELETE | IN_ATTRIB | IN_MOVED_FROM |
                              IN_MOVED_TO) < 0) {
        ah_logf(AH_LOG_WARN, "fusion: inotify %s: %s", INPUT_DIR,
                strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void uinput_retry(struct daemon *d)
{
    int64_t now = ah_now_ms();

    if (d->uinput_fd >= 0 || now - d->last_uinput_ms < MCU_OPEN_MS)
        return;
    d->last_uinput_ms = now;
    d->uinput_fd = create_uinput(d, d->fusion.enabled);
    if (d->uinput_fd < 0)
        return;
    d->fusion_device = d->fusion.enabled;
    effects_reset(d);
    fusion_reset_reported(&d->fusion);
    d->prev_keys = 0;
    for (int i = 0; i < AXIS_COUNT; i++)
        d->prev_axes[i] = INT_MIN;
    if (d->own_fd >= 0) {
        close(d->own_fd);
        d->own_fd = -1;
    }
    if (d->fusion.enabled) {
        d->own_fd = open_self_node();
        fusion_pad_add(&d->fusion, MCU_KEY, "RP5 internal (MCU)",
                       FUSION_CONN_MCU, now);
        fusion_scan(d);
    }
}

static void mcu_connect(struct daemon *d)
{
    int64_t now = ah_now_ms();

    if (d->mcu_fd >= 0 || now - d->last_mcu_ms < MCU_OPEN_MS)
        return;
    d->last_mcu_ms = now;
    d->mcu_fd = mcu_open();
    if (d->mcu_fd < 0) {
        retry_calibration(&d->ctx, &d->cal_retries, &d->last_cal_ms);
        return;
    }
    /* The MCU lost power with buttons held: release everything the kernel
     * still thinks is down before the new session starts. */
    mcu_release_state(d);
    d->rx_len = 0;
    d->good_frames = 0;
    d->last_status_ms = now;
    d->last_init_ms = now;
    mcu_send_init(d->mcu_fd);
    ah_logf(AH_LOG_INFO, "waiting for MCU status frames");
}

/* Everything that must happen even when no fd is ready. */
static void daemon_tick(struct daemon *d)
{
    int64_t now = ah_now_ms();

    if (now - d->last_props_ms >= PROP_REFRESH_MS) {
        deadzones_read(&d->ctx.dz);
        retry_calibration(&d->ctx, &d->cal_retries, &d->last_cal_ms);
        fusion_settings_read(&d->fusion);
        d->last_props_ms = now;
    }

    uinput_retry(d);

    if (d->uinput_fd >= 0 && d->fusion.enabled != d->fusion_device)
        apply_fusion_mode(d, d->fusion.enabled);

    if (d->fusion.enabled) {
        if (d->fusion.rescan) {
            d->fusion.rescan = false;
            fusion_rescan(d);
        }
        if (d->uinput_fd >= 0 &&
            (d->last_scan_ms == 0 || now - d->last_scan_ms >= FUSION_SCAN_MS)) {
            int merged = d->fusion.pad_count;

            d->last_scan_ms = now;
            fusion_check_missing(d);
            fusion_scan(d);
            /* Log the result only when it changed: a 2 s tick must not fill
             * logcat, but a pad that appears or disappears must be visible. */
            if (d->fusion.pad_count != merged)
                ah_logf(AH_LOG_INFO, "fusion: scan: %d pad(s) merged",
                        d->fusion.pad_count);
        }
        if (d->own_fd < 0 && now - d->last_self_ms >= SELF_NODE_MS) {
            d->last_self_ms = now;
            d->own_fd = open_self_node();
        }
    }

    mcu_connect(d);

    if (d->mcu_fd >= 0 && d->good_frames &&
        now - d->last_status_ms > MCU_QUIET_MS) {
        mcu_release_state(d);
        ah_logf(AH_LOG_INFO, "MCU went quiet; released held input");
    }
    if (d->mcu_fd >= 0 && !d->good_frames &&
        now - d->last_init_ms >= MCU_REINIT_MS) {
        d->last_init_ms = now;
        mcu_send_init(d->mcu_fd);
    }

    sync_grab(d);
}

int main(void)
{
    struct daemon d;
    struct sigaction sa;

    memset(&d, 0, sizeof(d));
    d.mcu_fd = d.uinput_fd = d.own_fd = d.notify_fd = -1;
    for (int i = 0; i < FUSION_MAX_PADS; i++)
        d.pads[i].fd = -1;

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    ah_log_set_tag("ashfall-rp5-input");
    deadzones_default(&d.ctx.dz);
    deadzones_read(&d.ctx.dz);
    d.ctx.on_calibration_changed = on_calibration_changed;
    input_reload_calibration(&d.ctx);

    fusion_defaults(&d.fusion);
    fusion_settings_read(&d.fusion);
    d.ctx.fusion = &d.fusion;

    d.control = control_create(&d.ctx);
    if (!d.control)
        ah_logf(AH_LOG_WARN, "control socket unavailable");
    d.notify_fd = inotify_start();
    d.last_props_ms = ah_now_ms();

    /* The uinput node exists on every boot, but retry rather than exit if we
     * happen to race ueventd; init's crash backoff could be minutes. */
    while (g_running && d.uinput_fd < 0) {
        d.last_uinput_ms = ah_now_ms();
        d.uinput_fd = create_uinput(&d, d.fusion.enabled);
        if (d.uinput_fd < 0) {
            retry_calibration(&d.ctx, &d.cal_retries, &d.last_cal_ms);
            control_wait(d.control, 1000);
        }
    }
    if (d.uinput_fd < 0)
        return 1;
    d.fusion_device = d.fusion.enabled;
    fusion_reset_reported(&d.fusion);
    for (int i = 0; i < AXIS_COUNT; i++)
        d.prev_axes[i] = INT_MIN;
    if (d.fusion.enabled) {
        d.own_fd = open_self_node();
        fusion_pad_add(&d.fusion, MCU_KEY, "RP5 internal (MCU)",
                       FUSION_CONN_MCU, ah_now_ms());
        fusion_scan(&d);
    }
    d.last_scan_ms = ah_now_ms();
    ah_logf(AH_LOG_INFO, "fusion: %s", d.fusion.enabled ? "on" : "off");

    /*
     * One loop for everything: the MCU (which can disappear across a screen
     * cycle), the control socket, the external pads of the fusion merge and
     * inotify.  Fusion keeps working while the MCU is away, and GET/SET CAL
     * always answer (#70).
     */
    while (g_running) {
        struct pollfd fds[4 + FUSION_MAX_PADS + CONTROL_MAX_CLIENTS + 1];
        int idx_mcu = -1;
        int idx_notify = -1;
        int idx_own = -1;
        int idx_uinput = -1;
        int idx_pad[FUSION_MAX_PADS];
        int control_start;
        int nfds = 0;
        int timeout;
        int rc;

        daemon_tick(&d);

        for (int i = 0; i < FUSION_MAX_PADS; i++)
            idx_pad[i] = -1;
        memset(fds, 0, sizeof(fds));

        if (d.mcu_fd >= 0) {
            idx_mcu = nfds;
            fds[nfds].fd = d.mcu_fd;
            fds[nfds++].events = POLLIN;
        }
        if (d.notify_fd >= 0) {
            idx_notify = nfds;
            fds[nfds].fd = d.notify_fd;
            fds[nfds++].events = POLLIN;
        }
        if (d.own_fd >= 0) {
            idx_own = nfds;
            fds[nfds].fd = d.own_fd;
            fds[nfds++].events = POLLIN;
        }
        if (d.fusion_device && d.uinput_fd >= 0) {
            idx_uinput = nfds;
            fds[nfds].fd = d.uinput_fd;
            fds[nfds++].events = POLLIN;
        }
        for (int i = 0; i < FUSION_MAX_PADS; i++) {
            if (d.pads[i].fd < 0)
                continue;
            idx_pad[i] = nfds;
            fds[nfds].fd = d.pads[i].fd;
            fds[nfds++].events = POLLIN;
        }
        control_start = nfds;
        nfds += control_pollfds(d.control, fds + nfds,
                                (int)ARRAY_SIZE(fds) - nfds);

        timeout = d.mcu_fd >= 0 && d.good_frames ? PROP_REFRESH_MS : 500;
        rc = poll(fds, (nfds_t)nfds, timeout);
        if (rc < 0) {
            if (errno == EINTR)
                continue;
            ah_logf(AH_LOG_ERROR, "poll: %s", strerror(errno));
            break;
        }
        if (rc == 0)
            continue;

        if (idx_notify >= 0 && (fds[idx_notify].revents & POLLIN))
            drain_inotify(&d);
        if (idx_own >= 0) {
            if (fds[idx_own].revents & POLLIN)
                drain_self(&d, d.own_fd);
            if (fds[idx_own].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                close(d.own_fd);
                d.own_fd = -1;
            }
        }
        if (idx_uinput >= 0) {
            if (fds[idx_uinput].revents & POLLIN)
                drain_self(&d, d.uinput_fd);
            if (fds[idx_uinput].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                ah_logf(AH_LOG_ERROR, "uinput device error, recreating");
                close(d.uinput_fd);
                d.uinput_fd = -1;
            }
        }

        if (idx_mcu >= 0) {
            if (fds[idx_mcu].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                ah_logf(AH_LOG_ERROR,
                        "MCU UART error (revents=0x%x), reconnecting",
                        fds[idx_mcu].revents);
                mcu_release_state(&d);
                close(d.mcu_fd);
                d.mcu_fd = -1;
            } else if (fds[idx_mcu].revents & POLLIN) {
                size_t used = d.rx_len <= sizeof(d.rx) ? d.rx_len : 0;
                size_t room = sizeof(d.rx) - used;
                ssize_t n;

                if (room == 0) {
                    used = 0;
                    room = sizeof(d.rx);
                }
                d.rx_len = used;
                n = read(d.mcu_fd, d.rx + used, room);

                if (n < 0) {
                    if (errno != EINTR && errno != EAGAIN) {
                        ah_logf(AH_LOG_ERROR, "MCU read: %s", strerror(errno));
                        mcu_release_state(&d);
                        close(d.mcu_fd);
                        d.mcu_fd = -1;
                    }
                } else if (n == 0) {
                    ah_logf(AH_LOG_ERROR, "MCU UART hung up");
                    mcu_release_state(&d);
                    close(d.mcu_fd);
                    d.mcu_fd = -1;
                } else {
                    d.rx_len += (size_t)n;
                    mcu_parse(&d);
                }
            }
        }

        for (int i = 0; i < FUSION_MAX_PADS; i++) {
            short revents;

            if (idx_pad[i] < 0)
                continue;
            revents = fds[idx_pad[i]].revents;
            if (!revents)
                continue;
            if (revents & (POLLHUP | POLLERR | POLLNVAL)) {
                pad_drop(&d, i, "unplugged");
                continue;
            }
            if (revents & POLLIN && !pad_read(&d, i))
                pad_drop(&d, i, "read error");
        }

        if (nfds > control_start)
            control_dispatch(d.control, fds + control_start,
                             nfds - control_start);

        /* Deltas only, one SYN per batch; suppressed while a tester owns
         * GRAB ON. */
        if (d.fusion_device && d.uinput_fd >= 0 && !d.grabbed)
            fusion_flush(&d.fusion, sink_emit, &d);
    }

    ah_logf(AH_LOG_INFO, "stopping");
    if (d.uinput_fd >= 0) {
        if (d.fusion_device && !d.grabbed)
            fusion_release(&d.fusion, sink_emit, &d);
        else
            mcu_release(&d);
    }
    pad_drop_all(&d);
    control_destroy(d.control);
    if (d.own_fd >= 0)
        close(d.own_fd);
    if (d.notify_fd >= 0)
        close(d.notify_fd);
    if (d.uinput_fd >= 0) {
        ioctl(d.uinput_fd, UI_DEV_DESTROY);
        close(d.uinput_fd);
    }
    return 0;
}
