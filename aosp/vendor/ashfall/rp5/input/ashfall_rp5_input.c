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
 * External-display pointer (#131, #250) is additive and opt-in:
 * persist.ashfall.pad.pointer=1 creates a second uinput device, "AshFall RP5
 * Pointer" (EV_REL + BTN_LEFT/RIGHT/MIDDLE + INPUT_PROP_POINTER), and drives
 * it from the right stick at a fixed 125 Hz.  Since #250 every pad can drive
 * it: the sources are the internal MCU plus every node fusion_node_mergeable()
 * accepts, with the scan/open policy fusion.enabled || pointer.on.  With
 * fusion off each external pad is grabbed and re-published through its own
 * pass-through shadow device (shadow.c); with fusion on the pointer-owned
 * inputs are consumed before they reach the merge.  While the pointer device
 * exists no source reaches the gamepad/fusion device with its R1, R2, left
 * stick or right stick; every other button passes through unchanged.  The
 * gamepad capability set and the RAW bit layout never change.
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
#include "pointer.h"
#include "session.h"
#include "shadow.h"
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

/* The MCU's Home and Back buttons are system keys, not gamepad buttons.  The
 * layouts Android applies to the gamepad identities (the Xbox 360 .kl for the
 * fusion device in particular) leave KEY_HOMEPAGE and KEY_BACK unmapped, so a
 * pad device drops them.  They are injected through a small keyboard device of
 * their own, which always falls back to Generic.kl (#185). */
#define MCU_BIT_HOME 6
#define MCU_BIT_BACK 7
#define MCU_SYSKEY_MASK \
    ((uint16_t)((1u << MCU_BIT_HOME) | (1u << MCU_BIT_BACK)))

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
 * fds, the per-pad axis classification, the rumble relay bookkeeping and -
 * since #250 - everything the pointer feed and the pass-through shadow need:
 * the source ranges, and the shadow's own fd, node path and output state. */
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
    char name[FUSION_NAME_MAX];
    struct input_id id;
    char key[FUSION_KEY_MAX];
    int axis_slot[FUSION_ABS_MAX];
    uint16_t axis_src[FUSION_AXIS_COUNT]; /* slot -> ABS code */
    /* Source range of every ABS code (probe_pad), for the pointer feed and
     * the shadow rescale. */
    struct fusion_absinfo absinfo[FUSION_ABS_MAX];
    uint8_t keybits[FUSION_BITS_BYTES(KEY_MAX + 1)];
    uint8_t absbits[FUSION_BITS_BYTES(ABS_MAX + 1)];
    struct ff_relay ff[FUSION_FF_MAX];
    /* Pass-through shadow (#250): valid while pointer.on and fusion off. */
    int shadow_fd;
    char shadow_node[64];
    struct shadow_state shadow_st;
    struct shadow_spec_buf shadow_buf;
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
    int pointer_fd; /* external-display pointer device, -1 while off (#131) */
    int syskey_fd;  /* Home/Back/Menu keyboard device (#185) */
    int own_fd;    /* our own event node, for EV_FF play events */
    int notify_fd; /* inotify watch on /dev/input */

    bool fusion_device; /* the uinput device currently is the fusion pad */
    bool grabbed;       /* a tester owns GRAB ON */
    bool rumble;        /* the uinput device really advertises EV_FF */

    /* External-display gamepad pointer (#131): mapping logic plus the switch
     * latch it shares with persist.ashfall.pad.pointer.  #250 gives every
     * source - the MCU and each pad slot - its own state, so two pads share
     * no buttons, velocity or scroll repeat; the one pointer device is
     * guarded by the shared mouse-button bus. */
    struct pointer_state pointer;
    struct pointer_state pointer_pads[FUSION_MAX_PADS];
    struct pointer_shared pointer_shared;
    bool pointer_consume_prev; /* last pointer_consume() state, for reconcile */

    uint8_t rx[4096];
    size_t rx_len;
    uint16_t prev_keys;
    uint16_t syskey_prev; /* Home/Back state last injected (#185) */
    int prev_axes[AXIS_COUNT];
    int good_frames;

    int cal_retries;
    int64_t last_cal_ms;
    int64_t last_props_ms;
    int64_t last_mcu_ms;
    int64_t last_uinput_ms;
    int64_t last_pointer_ms;
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

/* --- external-display pointer device (#131) ------------------------------- */

/*
 * A second device, created only while persist.ashfall.pad.pointer=1 and
 * destroyed when the switch goes off.  It is separate on purpose: the gamepad
 * capability set must never grow relative axes or mouse buttons, and with no
 * device present no app can see a mouse at all.
 */
static int create_pointer_uinput(struct daemon *d)
{
    struct uinput_dev_spec spec;
    bool rumble = false;
    int fd;

    memset(&spec, 0, sizeof(spec));
    spec.name = POINTER_NAME;
    spec.bustype = BUS_USB;
    spec.vendor = POINTER_VID;
    spec.product = POINTER_PID;
    spec.version = 1;
    spec.keys = pointer_keys;
    spec.key_count = pointer_key_count;
    spec.rels = pointer_rels;
    spec.rel_count = pointer_rel_count;
    spec.props = pointer_props;
    spec.prop_count = pointer_prop_count;

    fd = uinput_dev_create(&uinput_ops, &spec, &rumble);
    if (fd < 0) {
        ah_logf(AH_LOG_ERROR, "uinput %s: %s", spec.name, strerror(errno));
        return -1;
    }
    ah_logf(AH_LOG_INFO, "uinput %s created (%04x:%04x, external-display pointer)",
            spec.name, spec.vendor, spec.product);
    /* The kernel device starts with no state: clear every source so the next
     * tick re-reports whatever is physically held instead of assuming the
     * button that was pressed while the device was missing is already down. */
    pointer_release(&d->pointer, NULL, NULL, &d->pointer_shared);
    for (int i = 0; i < FUSION_MAX_PADS; i++)
        pointer_release(&d->pointer_pads[i], NULL, NULL, &d->pointer_shared);
    pointer_shared_clear(&d->pointer_shared);
    return fd;
}

/* --- Home/Back keyboard device (#185) ------------------------------------- */

/*
 * A keyboard device for the MCU's Home and Back buttons.  Generic.kl maps
 * KEY_HOMEPAGE (172) and KEY_BACK (158) for it, so the two system buttons work
 * whatever keylayout Android picks for the gamepad identity - the Xbox 360
 * layout used by the fusion device has no entries for them.  Created once and
 * kept for the daemon's lifetime; no app ever sees this device as a gamepad.
 */
static int create_syskey_uinput(struct daemon *d)
{
    static const uint16_t syskey_keys[] = {KEY_HOMEPAGE, KEY_BACK};
    struct uinput_dev_spec spec;
    bool rumble = false;
    int fd;

    memset(&spec, 0, sizeof(spec));
    spec.name = SYSKEY_NAME;
    spec.bustype = BUS_USB;
    spec.vendor = SYSKEY_VID;
    spec.product = SYSKEY_PID;
    spec.version = 1;
    spec.keys = syskey_keys;
    spec.key_count = (int)ARRAY_SIZE(syskey_keys);

    fd = uinput_dev_create(&uinput_ops, &spec, &rumble);
    if (fd < 0) {
        ah_logf(AH_LOG_ERROR, "uinput %s: %s", spec.name, strerror(errno));
        return -1;
    }
    ah_logf(AH_LOG_INFO, "uinput %s created (%04x:%04x, Home/Back keys)",
            spec.name, spec.vendor, spec.product);
    d->syskey_prev = 0;
    return fd;
}

static void pointer_destroy(struct daemon *d)
{
    if (!d || d->pointer_fd < 0)
        return;
    ioctl(d->pointer_fd, UI_DEV_DESTROY);
    close(d->pointer_fd);
    d->pointer_fd = -1;
}

/* Emit into the pointer device only.  GRAB ON suppresses this exactly like the
 * gamepad sink, so a grabbed tester never moves the system cursor.  A write
 * error (the kernel dropped the device) tears it down; daemon_tick recreates it
 * on the next retry while the pointer switch is still on. */
static void pointer_emit(void *ctx, uint16_t type, uint16_t code, int32_t value)
{
    struct daemon *d = ctx;
    struct input_event ev;

    if (!d || d->pointer_fd < 0 || d->grabbed)
        return;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write_all(d->pointer_fd, &ev, sizeof(ev)) < 0) {
        ah_logf(AH_LOG_ERROR, "pointer uinput write failed: %s; recreating",
                strerror(errno));
        pointer_destroy(d);
        d->last_pointer_ms = ah_now_ms();
    }
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

/* --- external pads and the pointer (#250) ---------------------------------- */

/*
 * The pointer consumes its inputs from every source only while the device it
 * writes exists: with /dev/uinput missing, no source loses inputs to a
 * pointer that is not there (docs/rp5/input.md).  GRAB does not change this -
 * it suppresses the pointer's output, not the consumption.
 */
static bool pointer_consume(const struct daemon *d)
{
    return d->pointer.on && d->pointer_fd >= 0;
}

/* Pads are opened while Gamepad Fusion merges them or while the desktop
 * pointer needs a source (#250): the scan/open policy is
 * fusion.enabled || pointer.on.  Motion-only nodes are never opened by either
 * (the fusion classify rule). */
static bool pads_wanted(const struct daemon *d)
{
    return d->fusion.enabled || d->pointer.on;
}

/* Ungrab, close and forget one pad slot (no registry or log side effects). */
static void pad_release_fd(struct pad_handle *pad)
{
    int zero = 0;

    ioctl(pad->fd, EVIOCGRAB, &zero);
    close(pad->fd);
    memset(pad, 0, sizeof(*pad));
    pad->fd = -1;
}

/* The event node of one of our own shadows must never be opened as a pad: it
 * re-publishes what the daemon writes, and reading it back would feed the
 * pad's own output into the merge or into another shadow. */
static int pad_shadow_by_node(const struct daemon *d, const char *node)
{
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        if (d->pads[i].shadow_fd >= 0 && !strcmp(d->pads[i].shadow_node, node))
            return i;
    }
    return -1;
}

/* Node names present in /dev/input right now; 32 is plenty for this class of
 * device. */
struct node_snapshot {
    char names[32][24];
    int count;
};

static int pad_by_node(const struct daemon *d, const char *node);

static void node_snapshot_take(struct node_snapshot *snap)
{
    DIR *dir = opendir(INPUT_DIR);
    struct dirent *entry;

    snap->count = 0;
    if (!dir)
        return;
    for (entry = readdir(dir); entry; entry = readdir(dir)) {
        if (strncmp(entry->d_name, "event", 5) != 0)
            continue;
        if (snap->count >= (int)ARRAY_SIZE(snap->names))
            break;
        snprintf(snap->names[snap->count], sizeof(snap->names[0]), "%s",
                 entry->d_name);
        snap->count++;
    }
    closedir(dir);
}

static bool node_snapshot_has(const struct node_snapshot *snap,
                              const char *name)
{
    for (int i = 0; i < snap->count; i++) {
        if (!strcmp(snap->names[i], name))
            return true;
    }
    return false;
}

/*
 * The shadow's own event node, found by identity (the shadow mirrors the
 * pad's) among the nodes that did not exist before UI_DEV_CREATE.  ueventd
 * creates the node asynchronously, hence the bounded retry.  A node that
 * never shows up fails the attach: no untracked clone of a pad is ever left
 * in /dev/input for the next scan to mistake for a pad.
 */
static bool shadow_find_node(const struct daemon *d,
                             const struct node_snapshot *snap,
                             const struct input_id *id, const char *name,
                             char *out, size_t len)
{
    for (int attempt = 0; attempt < 50; attempt++) {
        DIR *dir = opendir(INPUT_DIR);
        struct dirent *entry;

        if (!dir)
            return false;
        for (entry = readdir(dir); entry; entry = readdir(dir)) {
            struct input_id node_id;
            char node_name[FUSION_NAME_MAX];
            char path[64];
            int fd;
            bool name_ok;

            if (strncmp(entry->d_name, "event", 5) != 0)
                continue;
            if (node_snapshot_has(snap, entry->d_name))
                continue;
            join_path(path, sizeof(path), INPUT_DIR, entry->d_name);
            fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
            if (fd < 0)
                continue;
            memset(&node_id, 0, sizeof(node_id));
            node_name[0] = '\0';
            if (name && name[0]) {
                name_ok = ioctl(fd, EVIOCGNAME(sizeof(node_name)),
                                node_name) > 0 &&
                          !strcmp(node_name, name);
            } else {
                name_ok = true; /* the pad itself has no name to mirror */
            }
            /* Identity check on the full input_id: a same-model pad
             * hotplugged inside the discovery window is the one residual
             * ambiguity here (both carry the same id and name); matching
             * bustype and version narrows it, and a wrong bind is
             * self-healing - the next scan re-attaches both pads. */
            if (ioctl(fd, EVIOCGID, &node_id) == 0 &&
                node_id.bustype == id->bustype &&
                node_id.vendor == id->vendor &&
                node_id.product == id->product &&
                node_id.version == id->version && name_ok &&
                pad_by_node(d, path) < 0 && pad_shadow_by_node(d, path) < 0) {
                snprintf(out, len, "%s", path);
                close(fd);
                closedir(dir);
                return true;
            }
            close(fd);
        }
        closedir(dir);
        usleep(1000);
    }
    return false;
}

/*
 * Create the shadow for one grabbed pad (#250).  Returns true when the pad
 * is fully attached; on failure the caller releases it and the next scan
 * tries again - a pad visible to Android beats an invisible one.
 */
static bool pad_shadow_attach(struct daemon *d, int slot)
{
    struct pad_handle *pad = &d->pads[slot];
    struct shadow_source src;
    struct node_snapshot snap;
    struct uinput_dev_spec spec;
    bool rumble = false;
    int fd;

    memset(&src, 0, sizeof(src));
    src.name = pad->name;
    src.id.bustype = pad->id.bustype;
    src.id.vendor = pad->id.vendor;
    src.id.product = pad->id.product;
    src.id.version = pad->id.version;
    src.keybits = pad->keybits;
    src.keybits_len = sizeof(pad->keybits);
    src.absbits = pad->absbits;
    src.absbits_len = sizeof(pad->absbits);
    src.absinfo = pad->absinfo;
    src.axis_src = pad->axis_src;

    /* The snapshot is taken before the device exists, so the new node is
     * recognisable even between two identical pads attached back to back. */
    node_snapshot_take(&snap);
    shadow_spec(&src, &pad->shadow_buf, &spec);
    fd = uinput_dev_create(&uinput_ops, &spec, &rumble);
    if (fd < 0) {
        ah_logf(AH_LOG_WARN, "pointer: shadow for %s: %s", pad->key,
                strerror(errno));
        return false;
    }
    if (!shadow_find_node(d, &snap, &pad->id, pad->name, pad->shadow_node,
                          sizeof(pad->shadow_node))) {
        ah_logf(AH_LOG_WARN,
                "pointer: shadow for %s: no event node, pad left alone",
                pad->key);
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
        return false;
    }
    pad->shadow_fd = fd;
    shadow_state_init(&pad->shadow_st);
    ah_logf(AH_LOG_INFO, "pointer: shadow for %s created at %s", pad->key,
            pad->shadow_node);
    return true;
}

/* EV_KEY/EV_ABS/EV_SYN sink into one shadow fd; `failed` tells pad_event to
 * drop the pad instead of leaving Android with a dead shadow device. */
struct shadow_sink {
    int fd;
    bool failed;
};

static void shadow_emit(void *ctx, uint16_t type, uint16_t code, int32_t value)
{
    struct shadow_sink *sink = ctx;
    struct input_event ev;

    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write_all(sink->fd, &ev, sizeof(ev)) < 0) {
        ah_logf(AH_LOG_WARN, "pointer: shadow write failed: %s",
                strerror(errno));
        sink->failed = true;
    }
}

/* Any event from a source keeps its held pointer state trusted: the 2 s
 * frame timeout then only fires for a source that really went away. */
static void pad_pointer_touch(struct pointer_state *ps)
{
    ps->last_frame_ms = ah_now_ms();
    ps->have_frame = true;
}

/* Feed the pointer-owned inputs of one event into the source's pointer
 * state, in canonical space - the same values the merge (or the shadow)
 * would have carried (#250).  The MCU keeps its own calibrated feed. */
static void pad_pointer_key_feed(struct pointer_state *ps,
                                 const struct input_event *ev)
{
    /* External pads carry no other pointer keys than R1. */
    ps->keys = ev->value ? POINTER_KEY_R1 : 0;
    pad_pointer_touch(ps);
}

static void pad_pointer_axis_feed(struct pointer_state *ps,
                                  const struct pad_handle *pad,
                                  const struct input_event *ev, int slot)
{
    int32_t scaled = fusion_pad_rescale(&pad->absinfo[ev->code], slot,
                                        ev->value);

    switch (slot) {
    case FUSION_AXIS_LX:
        ps->lx = scaled;
        break;
    case FUSION_AXIS_LY:
        ps->ly = scaled;
        break;
    case FUSION_AXIS_RX:
        ps->rx = scaled;
        break;
    case FUSION_AXIS_RY:
        ps->ry = scaled;
        break;
    case FUSION_AXIS_R2:
        ps->r2 = scaled;
        break;
    }
    pad_pointer_touch(ps);
}

/* The shadow_source view of one attached pad (pad_read and pad_seed). */
static void pad_source_build(const struct pad_handle *pad,
                             struct shadow_source *src)
{
    memset(src, 0, sizeof(*src));
    src->name = pad->name;
    src->id.bustype = pad->id.bustype;
    src->id.vendor = pad->id.vendor;
    src->id.product = pad->id.product;
    src->id.version = pad->id.version;
    src->keybits = pad->keybits;
    src->keybits_len = sizeof(pad->keybits);
    src->absbits = pad->absbits;
    src->absbits_len = sizeof(pad->absbits);
    src->absinfo = pad->absinfo;
    src->axis_src = pad->axis_src;
}

/*
 * A pad attached mid-press must not lose what the user is already holding:
 * seed the source's pointer state - and, for a shadow, its output - from the
 * kernel's current key/axis state, which stays readable through the grab
 * (#250).  The merge needs no seeding: its registry entries start at rest
 * and the consumed inputs are dropped per event.  Change-only pads never age
 * their held state out, so this is also where a re-attached pad gets its
 * truth back.
 */
static void pad_seed(struct daemon *d, int slot, bool shadow_too)
{
    struct pad_handle *pad = &d->pads[slot];
    struct pointer_state *ps = &d->pointer_pads[slot];
    struct shadow_source src;
    uint8_t keybits[FUSION_BITS_BYTES(KEY_MAX + 1)];

    memset(keybits, 0, sizeof(keybits));
    if (ioctl(pad->fd, EVIOCGKEY(sizeof(keybits)), keybits) == 0 &&
        fusion_bits_test(keybits, FUSION_POINTER_OWNED_KEY)) {
        struct input_event ev = {
            .type = EV_KEY,
            .code = FUSION_POINTER_OWNED_KEY,
            .value = 1,
        };

        pad_pointer_key_feed(ps, &ev);
    }
    for (int code = 0; code <= ABS_MAX; code++) {
        struct input_absinfo info;
        int axis = pad->axis_slot[code];

        if (axis < 0 || !fusion_slot_pointer_owned(axis) ||
            !pad->absinfo[code].present)
            continue;
        memset(&info, 0, sizeof(info));
        if (ioctl(pad->fd, EVIOCGABS(code), &info) == 0) {
            struct input_event ev = {
                .type = EV_ABS,
                .code = code,
                .value = info.value,
            };

            pad_pointer_axis_feed(ps, pad, &ev, axis);
        }
    }
    if (!shadow_too || pad->shadow_fd < 0)
        return;

    /* Held keys and deflected non-owned axes go to the shadow too, through
     * the same routing new events take. */
    pad_source_build(pad, &src);
    {
        struct shadow_sink sink = {
            .fd = pad->shadow_fd,
            .failed = false,
        };

        for (int code = 1; code <= KEY_MAX; code++) {
            if (!fusion_bits_test(keybits, code) ||
                code == FUSION_POINTER_OWNED_KEY)
                continue;
            shadow_route_event(&src, &pad->shadow_st, true, EV_KEY,
                               (uint16_t)code, 1, shadow_emit, &sink);
        }
        for (int code = 0; code <= ABS_MAX; code++) {
            struct input_absinfo info;
            int axis = pad->axis_slot[code];

            if (!pad->absinfo[code].present ||
                (axis >= 0 && fusion_slot_pointer_owned(axis)))
                continue;
            memset(&info, 0, sizeof(info));
            if (ioctl(pad->fd, EVIOCGABS(code), &info) == 0)
                shadow_route_event(&src, &pad->shadow_st, true, EV_ABS,
                                   (uint16_t)code, info.value, shadow_emit,
                                   &sink);
        }
        shadow_route_event(&src, &pad->shadow_st, true, EV_SYN, SYN_REPORT, 0,
                           shadow_emit, &sink);
        if (sink.failed)
            ah_logf(AH_LOG_WARN, "pointer: shadow seed for %s failed",
                    pad->key);
    }
}

/*
 * One event from one physical pad (#250).  The pointer-owned inputs feed the
 * pad's own pointer state whenever the pointer device exists - tracking
 * continues while GRAB suppresses the output, so GRAB OFF resumes from the
 * current physical state - and are then consumed: never entered into the
 * merge (fusion on), reported at rest by the shadow (fusion off).  Everything
 * else flows to the merge or the shadow exactly as before.  Returns false
 * when the shadow stopped accepting writes and the pad must be dropped.
 */
static bool pad_event(struct daemon *d, struct pad_handle *pad,
                      struct pointer_state *ps, const struct shadow_source *src,
                      int index, bool consume, const struct input_event *ev)
{
    struct shadow_sink sink;

    if (ev->type == EV_KEY) {
        if (consume && ev->code == FUSION_POINTER_OWNED_KEY) {
            pad_pointer_key_feed(ps, ev);
            if (index >= 0)
                return true; /* consumed before the merge */
        } else if (index >= 0) {
            /* The device capabilities are fixed at creation; merging a code
             * we never advertised would only add phantom state. */
            if (fusion_key_supported(ev->code))
                fusion_key_event(&d->fusion, index, ev->code, ev->value);
            return true;
        }
    } else if (ev->type == EV_ABS) {
        int slot = ev->code <= ABS_MAX ? pad->axis_slot[ev->code] : -1;

        if (slot >= 0 && fusion_slot_pointer_owned(slot)) {
            if (consume) {
                pad_pointer_axis_feed(ps, pad, ev, slot);
                if (index >= 0)
                    return true; /* consumed before the merge */
            } else if (index >= 0) {
                /* No live pointer device: the pad keeps its full mapping. */
                fusion_abs_event(&d->fusion, index, slot, ev->value,
                                 ah_now_ms());
                return true;
            }
        } else if (index >= 0) {
            if (slot >= 0)
                fusion_abs_event(&d->fusion, index, slot, ev->value,
                                 ah_now_ms());
            return true;
        }
    } else if (index >= 0) {
        return true; /* the merge builds its own batches */
    }

    if (pad->shadow_fd < 0)
        return true;
    sink.fd = pad->shadow_fd;
    sink.failed = false;
    shadow_route_event(src, &pad->shadow_st, consume, ev->type, ev->code,
                       ev->value, shadow_emit, &sink);
    return !sink.failed;
}

/*
 * The pointer-owned inputs of every merged pad follow the live pointer
 * device: consumed while it exists (zeroed in the registry), re-seeded from
 * the pads' current state when it does not (#250).  The MCU needs no re-seed
 * - it streams every axis on every frame - and the shadow pads carry no
 * registry state at all.
 */
static void pointer_consume_reconcile(struct daemon *d, bool on)
{
    if (!d->fusion_device)
        return;
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        struct pad_handle *pad = &d->pads[i];
        int index;

        if (pad->fd < 0)
            continue;
        index = fusion_pad_index(&d->fusion, pad->key);
        if (index < 0)
            continue;
        if (on) {
            fusion_pad_consume_pointer_inputs(&d->fusion, index);
            /* The pointer starts consuming mid-press: its state must know
             * what the pad is already holding. */
            pad_seed(d, i, false);
            continue;
        }
        /* EVIOCGRAB does not change the kernel's idea of the axis state, so
         * the current values are readable even mid-press. */
        for (int code = 0; code <= ABS_MAX; code++) {
            struct input_absinfo info;
            int slot = pad->axis_slot[code];

            if (slot < 0 || !fusion_slot_pointer_owned(slot))
                continue;
            memset(&info, 0, sizeof(info));
            if (ioctl(pad->fd, EVIOCGABS(code), &info) == 0)
                fusion_abs_event(&d->fusion, index, slot, info.value,
                                 ah_now_ms());
        }
        {
            uint8_t keybits[FUSION_BITS_BYTES(KEY_MAX + 1)];

            memset(keybits, 0, sizeof(keybits));
            if (ioctl(pad->fd, EVIOCGKEY(sizeof(keybits)), keybits) == 0 &&
                fusion_bits_test(keybits, FUSION_POINTER_OWNED_KEY))
                fusion_key_event(&d->fusion, index,
                                 FUSION_POINTER_OWNED_KEY, 1);
        }
    }
    fusion_refresh(&d->fusion);
}

/* --- external pad registry ------------------------------------------------ */

struct pad_probe {
    struct input_id id;
    char name[FUSION_NAME_MAX];
    enum fusion_connection connection;
    struct fusion_absinfo absinfo[FUSION_ABS_MAX];
    uint16_t axis_src[FUSION_AXIS_COUNT];
    /* Capability bitmaps, kept for the per-pad shadow (#250): the shadow
     * mirrors the pad's key/abs capabilities. */
    uint8_t keybits[FUSION_BITS_BYTES(KEY_MAX + 1)];
    uint8_t absbits[FUSION_BITS_BYTES(ABS_MAX + 1)];
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

    /* The capability bitmaps outlive this probe in the pad handle: the
     * shadow mirrors them (#250). */
    memcpy(out->keybits, keybits, sizeof(out->keybits));
    memcpy(out->absbits, absbits, sizeof(out->absbits));

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
    /* A pad unplugged mid-press releases everything, including the pointer
     * buttons it held: the shared bus only lifts the device's button when no
     * other source still holds it (#250). */
    pointer_release(&d->pointer_pads[slot], pointer_emit, d,
                    &d->pointer_shared);
    if (pad->shadow_fd >= 0) {
        ioctl(pad->shadow_fd, UI_DEV_DESTROY);
        close(pad->shadow_fd);
        ah_logf(AH_LOG_INFO, "pointer: shadow for %s removed", pad->key);
    }
    /* Explicit release before close: close() would do it too, but an explicit
     * ungrab keeps the intent visible and runs before the fd disappears. */
    ioctl(pad->fd, EVIOCGRAB, &zero);
    close(pad->fd);
    if (pad->key[0]) {
        bool merged = fusion_pad_remove(&d->fusion, pad->key);

        if (merged)
            ah_logf(AH_LOG_INFO, "fusion: pad %s removed%s%s", pad->key,
                    reason ? " (" : "", reason ? reason : "");
        else if (!d->fusion.enabled)
            ah_logf(AH_LOG_INFO, "pointer: pad %s released%s%s", pad->key,
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

static void pad_scan(struct daemon *d)
{
    DIR *dir;
    struct dirent *entry;

    if (!pads_wanted(d))
        return;
    /* Pointer-only mode attaches a pad only when there is a live pointer
     * device to consume for: grabbing a pad with no shadow and no merge
     * would make it invisible to Android for nothing.  The next scan (or
     * pointer_retry) attaches it. */
    if (!d->fusion.enabled && d->pointer_fd < 0)
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
        if (pad_by_node(d, node) >= 0 || pad_shadow_by_node(d, node) >= 0)
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
        if (d->fusion.enabled) {
            /*
             * Grab before the pad reaches the merge.  A pad we cannot grab
             * must not be merged either: apps would see the fusion pad *and*
             * the physical one - exactly the double input fusion exists to
             * remove.
             */
            int one = 1;

            if (ioctl(fd, EVIOCGRAB, &one) < 0) {
                ah_logf(AH_LOG_WARN,
                        "fusion: cannot grab %04x:%04x (%s), not merged: %s",
                        probe.id.vendor, probe.id.product, node,
                        strerror(errno));
                close(fd);
                continue;
            }

            /* The key the registry decided on (vendor:product:connection),
             * logged by fusion_pad_add, so a BT and a USB session of the same
             * pad keep distinct, stable keys. */
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
            snprintf(handle->name, sizeof(handle->name), "%s", probe.name);
            handle->id = probe.id;
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
            memcpy(handle->axis_src, probe.axis_src, sizeof(handle->axis_src));
            memcpy(handle->absinfo, probe.absinfo, sizeof(handle->absinfo));
            memcpy(handle->keybits, probe.keybits, sizeof(handle->keybits));
            memcpy(handle->absbits, probe.absbits, sizeof(handle->absbits));
            /* A re-attached pad starts with a clean pointer state; pads are
             * change-only sources, so they never age held state out. */
            pointer_defaults(&d->pointer_pads[slot]);
            d->pointer_pads[slot].on = d->pointer.on;
            d->pointer_pads[slot].streams_frames = false;
            if (pointer_consume(d))
                pad_seed(d, slot, false);
            ah_logf(AH_LOG_INFO, "fusion: pad %s \"%s\" grabbed (%s)",
                    pad->key, pad->name, writable ? "rw" : "ro");
            continue;
        }

        /*
         * Pointer shadow mode (#250): grab the pad and re-publish it through
         * its own pass-through uinput device; it never enters the fusion
         * registry.  A pad we cannot grab or cannot shadow is left alone -
         * an ungrabbed pad visible to Android beats an invisible one.
         */
        {
            int one = 1;

            if (ioctl(fd, EVIOCGRAB, &one) < 0) {
                ah_logf(AH_LOG_WARN,
                        "pointer: cannot grab %04x:%04x (%s): %s",
                        probe.id.vendor, probe.id.product, node,
                        strerror(errno));
                close(fd);
                continue;
            }
        }

        handle = &d->pads[slot];
        memset(handle, 0, sizeof(*handle));
        handle->fd = fd;
        handle->writable = writable;
        snprintf(handle->node, sizeof(handle->node), "%s", node);
        snprintf(handle->name, sizeof(handle->name), "%s", probe.name);
        handle->id = probe.id;
        snprintf(handle->key, sizeof(handle->key), "%s", probe.key);
        for (int i = 0; i < FUSION_ABS_MAX; i++)
            handle->axis_slot[i] = -1;
        for (int axis = 0; axis < FUSION_AXIS_COUNT; axis++) {
            uint16_t code = probe.axis_src[axis];

            if (code == 0 || code > ABS_MAX)
                continue;
            handle->axis_slot[code] = axis;
        }
        memcpy(handle->axis_src, probe.axis_src, sizeof(handle->axis_src));
        memcpy(handle->absinfo, probe.absinfo, sizeof(handle->absinfo));
        memcpy(handle->keybits, probe.keybits, sizeof(handle->keybits));
        memcpy(handle->absbits, probe.absbits, sizeof(handle->absbits));
        /* A re-attached pad starts with a clean pointer state; pads are
         * change-only sources, so they never age held state out. */
        pointer_defaults(&d->pointer_pads[slot]);
        d->pointer_pads[slot].on = d->pointer.on;
        d->pointer_pads[slot].streams_frames = false;
        if (!pad_shadow_attach(d, slot)) {
            pad_release_fd(handle);
            continue;
        }
        pad_seed(d, slot, true);
        ah_logf(AH_LOG_INFO, "pointer: pad %s \"%s\" grabbed (shadow)",
                handle->key, handle->name);
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
    struct shadow_source src;
    struct pointer_state *ps = &d->pointer_pads[slot];
    int index = fusion_pad_index(&d->fusion, pad->key);
    bool consume = pointer_consume(d);
    ssize_t n;

    pad_source_build(pad, &src);

    while ((n = read(pad->fd, events, sizeof(events))) > 0) {
        size_t count = (size_t)n / sizeof(events[0]);

        for (size_t i = 0; i < count; i++) {
            if (!pad_event(d, pad, ps, &src, index, consume, &events[i]))
                return false; /* shadow lost: drop the pad, not the loop */
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
                          uint16_t *keys_out, int sticks[4])
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

    /* The pointer applies its own fixed 5 % deadzone, so it gets the
     * calibrated values before the user deadzone is folded in. */
    if (sticks) {
        sticks[0] = lx;
        sticks[1] = ly;
        sticks[2] = rx;
        sticks[3] = ry;
    }

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

/*
 * Home and Back go through their own device (#185): the gamepad layouts
 * Android matches by name or vendor/product have no HOME/BACK entries, so the
 * events would be dropped there.  Edges only, one SYN per change, and the
 * state is tracked separately from prev_keys because the fusion path keeps its
 * own per-pad state.
 */
static void syskey_route(struct daemon *d, uint16_t keys)
{
    const uint16_t changed =
            (uint16_t)((keys ^ d->syskey_prev) & MCU_SYSKEY_MASK);

    if (changed && d->syskey_fd >= 0) {
        if (changed & (1u << MCU_BIT_HOME))
            emit_event(d->syskey_fd, EV_KEY, KEY_HOMEPAGE,
                       (keys >> MCU_BIT_HOME) & 1);
        if (changed & (1u << MCU_BIT_BACK))
            emit_event(d->syskey_fd, EV_KEY, KEY_BACK,
                       (keys >> MCU_BIT_BACK) & 1);
        emit_sync(d->syskey_fd);
    }
    d->syskey_prev =
            (uint16_t)((d->syskey_prev & ~MCU_SYSKEY_MASK) | (keys & MCU_SYSKEY_MASK));
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
    if (!d->grabbed)
        pointer_release(&d->pointer, pointer_emit, d, &d->pointer_shared);
    syskey_route(d, 0);
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
        if (i == MCU_BIT_HOME || i == MCU_BIT_BACK)
            continue; /* injected through the system-keys device (#185) */
        fusion_key_event(&d->fusion, index, mcu_keymap[i],
                         (keys >> i) & 1);
    }
    for (int i = 0; i < AXIS_COUNT; i++)
        fusion_abs_event(&d->fusion, index, i, axes[i], now_ms);
}

/*
 * While the external-display pointer is on, the MCU pad's R1, R2, left stick
 * and right stick drive the mouse.  Forward a masked copy of the frame to the
 * gamepad so a game cannot see a click, a trigger pull or a stick move that the
 * pointer already consumed.  L1/L2/face buttons/D-pad/Start/Select pass
 * through, and only the internal pad is masked: external pads merged by
 * Gamepad Fusion keep their full mapping.
 *
 * The mask follows the live pointer device, not the property: if /dev/uinput is
 * missing and the device could not be (re)created, the pad keeps its full
 * mapping instead of losing inputs to a pointer that is not there.
 */
static void pointer_route(const struct daemon *d, uint16_t *keys,
                          int axes[AXIS_COUNT])
{
    if (!d->pointer.on || d->pointer_fd < 0)
        return;
    *keys &= (uint16_t)~POINTER_CONSUMED_KEYS;
    axes[AXIS_LX] = 0;
    axes[AXIS_LY] = 0;
    axes[AXIS_RX] = 0;
    axes[AXIS_RY] = 0;
    axes[AXIS_R2] = 0;
}

static void handle_status(struct daemon *d, const uint8_t *data, size_t len)
{
    struct raw_sample raw;
    int axes[AXIS_COUNT] = {0};
    int pad_axes[AXIS_COUNT] = {0};
    int sticks[4] = {0};
    uint16_t keys = 0;
    uint16_t pad_keys;
    int64_t now_ms;
    int changed = 0;

    if (len < 14)
        return;

    now_ms = ah_now_ms();
    decode_status(data, len, &d->ctx, &raw, axes, &keys, sticks);
    control_stream_raw(d->control, now_ms, &raw);

    /* Track every frame, grabbed or not: a switch flip or GRAB OFF must resume
     * from the current physical state, never a stale one.  pointer_tick() only
     * emits while the pointer is on and outside GRAB. */
    pointer_frame(&d->pointer, keys, sticks[0], sticks[1], sticks[2], sticks[3],
                  axes[AXIS_R2], now_ms);

    memcpy(pad_axes, axes, sizeof(pad_axes));
    pad_keys = keys;
    pointer_route(d, &pad_keys, pad_axes);

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

    syskey_route(d, pad_keys);

    if (d->fusion_device) {
        mcu_to_fusion(d, pad_keys, pad_axes, now_ms);
        d->good_frames++;
        return;
    }

    if (pad_keys != d->prev_keys) {
        uint16_t key_changes = (uint16_t)(pad_keys ^ d->prev_keys);

        for (int i = 0; i < 16; i++) {
            if (!(key_changes & (1u << i)))
                continue;
            if (mcu_keymap[i] == 0)
                continue; /* D-pad bit, reported through ABS_HAT0X/Y */
            if (i == MCU_BIT_HOME || i == MCU_BIT_BACK)
                continue; /* injected through the system-keys device (#185) */
            emit_event(d->uinput_fd, EV_KEY, mcu_keymap[i],
                       (pad_keys >> i) & 1);
        }
        d->prev_keys = pad_keys;
        changed = 1;
    }

    for (int i = 0; i < AXIS_COUNT; i++) {
        if (pad_axes[i] == d->prev_axes[i])
            continue;
        emit_event(d->uinput_fd, EV_ABS, axis_codes[i], pad_axes[i]);
        d->prev_axes[i] = pad_axes[i];
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
 * grabbed and merged.  Fusion off: none of that, exactly the phase 5 device -
 * but with the pointer on, the pads come back grabbed and shadowed (#250).
 * Both directions drop the pads first: their role (merge vs shadow) changes. */
static void apply_fusion_mode(struct daemon *d, bool on)
{
    /* Release what the old device still shows before it is destroyed. */
    if (d->fusion_device && !d->grabbed)
        fusion_release(&d->fusion, sink_emit, d);
    else if (!d->fusion_device && !d->grabbed)
        mcu_release(d);

    pad_drop_all(d);
    fusion_clear_pads(&d->fusion);
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
        pad_scan(d);
        ah_logf(AH_LOG_INFO, "fusion ON: %d pad(s) merged",
                d->fusion.pad_count);
    } else {
        ah_logf(AH_LOG_INFO, "fusion OFF: physical pads visible again");
        if (pads_wanted(d))
            pad_scan(d); /* the pointer re-attaches them as shadows */
    }
}

static void fusion_rescan(struct daemon *d)
{
    if (d->uinput_fd < 0 || !d->fusion.enabled)
        return;
    fusion_check_missing(d);
    pad_scan(d);
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
        /* Still allowed to emit: d->grabbed flips after this.  GRAB ON must not
         * leave a mouse button held on the pointer device either, from any
         * source (#250). */
        pointer_release(&d->pointer, pointer_emit, d, &d->pointer_shared);
        for (int i = 0; i < FUSION_MAX_PADS; i++)
            pointer_release(&d->pointer_pads[i], pointer_emit, d,
                            &d->pointer_shared);
        ah_logf(AH_LOG_INFO, "GRAB ON: uinput output suppressed");
    } else {
        d->prev_keys = 0;
        for (int i = 0; i < AXIS_COUNT; i++)
            d->prev_axes[i] = INT_MIN;
        fusion_reset_reported(&d->fusion);
        pointer_release(&d->pointer, NULL, NULL, &d->pointer_shared);
        for (int i = 0; i < FUSION_MAX_PADS; i++)
            pointer_release(&d->pointer_pads[i], NULL, NULL,
                            &d->pointer_shared);
        pointer_shared_clear(&d->pointer_shared);
        ah_logf(AH_LOG_INFO, "GRAB OFF: uinput output resumed");
    }
    d->grabbed = now;
}

/*
 * Apply a live pointer switch change (#131, #250).  A switch is a hard reset
 * for input: every button held on the gamepad and on the pointer is released
 * from every source (the next MCU frame re-reports the physical state), the
 * pointers' velocity/fraction/scroll state is dropped, and the pointer uinput
 * device is created or destroyed - so with the switch off there is no mouse
 * device at all.  With fusion off the pads opened for the pointer are
 * dropped again (ungrabbed, closed, shadows destroyed); with fusion on the
 * merged pads' consumed inputs are zeroed in the registry, and re-seeded
 * from the pads on the way off.  A property change is never suppressed by
 * GRAB: while grabbed everything here happens silently.
 */
static void apply_pointer_switch(struct daemon *d)
{
    bool on = d->pointer.on;

    if (!d->grabbed) {
        if (d->fusion_device)
            fusion_release(&d->fusion, sink_emit, d);
        else
            mcu_release(d);
        pointer_release(&d->pointer, pointer_emit, d, &d->pointer_shared);
    } else {
        /* A tester owns the input: nothing may be injected, but the state must
         * still be clean for GRAB OFF. */
        pointer_release(&d->pointer, NULL, NULL, &d->pointer_shared);
    }
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        pointer_release(&d->pointer_pads[i], d->grabbed ? NULL : pointer_emit,
                        d, &d->pointer_shared);
    }
    pointer_shared_clear(&d->pointer_shared);

    if (on) {
        if (d->pointer_fd < 0) {
            d->pointer_fd = create_pointer_uinput(d);
            d->last_pointer_ms = ah_now_ms();
        }
        if (d->pointer_fd >= 0) {
            pointer_consume_reconcile(d, true);
            if (!d->fusion.enabled) {
                d->last_scan_ms = 0;
                pad_scan(d); /* attach shadow pads right away */
            }
        }
    } else {
        /* Re-seed the merged pads before anything is released: the physical
         * state is still readable through the grabbed fds. */
        pointer_consume_reconcile(d, false);
        if (!d->fusion.enabled)
            pad_drop_all(d); /* the shadows die with their pads */
        pointer_destroy(d);
    }
    for (int i = 0; i < FUSION_MAX_PADS; i++)
        pointer_set_enabled(&d->pointer_pads[i], on);
    d->pointer_consume_prev = pointer_consume(d);
}

/* /dev/uinput can be missing for a moment after boot; retry rather than
 * leaving the pointer switch on without a device. */
static void pointer_retry(struct daemon *d)
{
    int64_t now = ah_now_ms();

    if (!d->pointer.on || d->pointer_fd >= 0)
        return;
    if (now - d->last_pointer_ms < MCU_OPEN_MS)
        return;
    d->last_pointer_ms = now;
    d->pointer_fd = create_pointer_uinput(d);
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
        pad_scan(d);
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

static int pad_attached_count(const struct daemon *d)
{
    int count = 0;

    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        if (d->pads[i].fd >= 0)
            count++;
    }
    return count;
}

/* Milliseconds until any pointer source wants to emit, or -1 (#250). */
static int pointer_sources_timeout_ms(struct daemon *d, int64_t now)
{
    int best = pointer_timeout_ms(&d->pointer, now);

    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        int ms = pointer_timeout_ms(&d->pointer_pads[i], now);

        if (ms >= 0 && (best < 0 || ms < best))
            best = ms;
    }
    return best;
}

/* Everything that must happen even when no fd is ready. */
static void daemon_tick(struct daemon *d)
{
    int64_t now = ah_now_ms();

    if (now - d->last_props_ms >= PROP_REFRESH_MS) {
        deadzones_read(&d->ctx.dz);
        retry_calibration(&d->ctx, &d->cal_retries, &d->last_cal_ms);
        fusion_settings_read(&d->fusion);
        if (pointer_switch_read(&d->pointer)) {
            apply_pointer_switch(d);
            ah_logf(AH_LOG_INFO, "pointer: %s (property)",
                    d->pointer.on ? "on" : "off");
        }
        d->last_props_ms = now;
    }

    uinput_retry(d);

    pointer_retry(d);
    {
        /* The pointer-owned inputs follow the live pointer device: reconcile
         * the merged pads on every transition, including the ones pointer_retry
         * and the write-error teardown cause (#250). */
        bool consume = pointer_consume(d);

        if (consume != d->pointer_consume_prev) {
            d->pointer_consume_prev = consume;
            pointer_consume_reconcile(d, consume);
        }
    }
    if (!d->grabbed) {
        /* Every source gets its own tick; they share only the mouse buttons. */
        pointer_tick(&d->pointer, now, pointer_emit, d, &d->pointer_shared);
        for (int i = 0; i < FUSION_MAX_PADS; i++) {
            if (d->pads[i].fd >= 0)
                pointer_tick(&d->pointer_pads[i], now, pointer_emit, d,
                             &d->pointer_shared);
        }
    }

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
            pad_scan(d);
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
    } else if (d->pointer.on) {
        /* Pointer-only mode: the same 2 s / hotplug cadence, attaching and
         * re-attaching shadow pads while the pointer device exists. */
        if (d->uinput_fd >= 0 &&
            (d->last_scan_ms == 0 || now - d->last_scan_ms >= FUSION_SCAN_MS)) {
            int attached = pad_attached_count(d);

            d->last_scan_ms = now;
            fusion_check_missing(d);
            pad_scan(d);
            if (pad_attached_count(d) != attached)
                ah_logf(AH_LOG_INFO, "pointer: scan: %d pad(s) attached",
                        pad_attached_count(d));
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
    d.mcu_fd = d.uinput_fd = d.pointer_fd = d.own_fd = d.notify_fd = -1;
    d.syskey_fd = -1;
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

    /* External-display pointer (#131): off by default; the AshFall Parts switch
     * in the Fusion QS dialog is the only writer.  #250 gives every source its
     * own pointer state under one shared mouse-button bus. */
    pointer_defaults(&d.pointer);
    pointer_switch_read(&d.pointer);
    for (int i = 0; i < FUSION_MAX_PADS; i++)
        pointer_defaults(&d.pointer_pads[i]);
    pointer_shared_init(&d.pointer_shared);

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
    d.syskey_fd = create_syskey_uinput(&d);
    d.fusion_device = d.fusion.enabled;
    fusion_reset_reported(&d.fusion);
    for (int i = 0; i < AXIS_COUNT; i++)
        d.prev_axes[i] = INT_MIN;
    if (d.pointer.on) {
        d.pointer_fd = create_pointer_uinput(&d);
        d.last_pointer_ms = ah_now_ms();
        ah_logf(AH_LOG_INFO, "pointer: on (boot)");
    } else {
        ah_logf(AH_LOG_INFO, "pointer: off");
    }
    /* The shadow pads attach after the pointer device exists: consuming for a
     * pointer that is not there is what pads must never lose inputs to. */
    if (d.fusion.enabled) {
        d.own_fd = open_self_node();
        fusion_pad_add(&d.fusion, MCU_KEY, "RP5 internal (MCU)",
                       FUSION_CONN_MCU, ah_now_ms());
        pad_scan(&d);
    } else if (pads_wanted(&d)) {
        pad_scan(&d); /* pointer on: attach the shadow pads from the start */
    }
    d.last_scan_ms = ah_now_ms();
    ah_logf(AH_LOG_INFO, "fusion: %s", d.fusion.enabled ? "on" : "off");
    d.pointer_consume_prev = pointer_consume(&d);

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
        /* The pointer emits on a fixed 125 Hz tick, so the loop must wake for
         * it even when no MCU frame arrives - for every source (#250). */
        if (d.pointer_fd >= 0 && !d.grabbed) {
            int pointer_ms = pointer_sources_timeout_ms(&d, ah_now_ms());

            if (pointer_ms >= 0 && pointer_ms < timeout)
                timeout = pointer_ms;
        }
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
    /* No stuck mouse button when the daemon restarts, from any source. */
    pointer_release(&d.pointer, d.grabbed ? NULL : pointer_emit, &d,
                    &d.pointer_shared);
    for (int i = 0; i < FUSION_MAX_PADS; i++)
        pointer_release(&d.pointer_pads[i], d.grabbed ? NULL : pointer_emit,
                        &d, &d.pointer_shared);
    pointer_destroy(&d);
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
    if (d.syskey_fd >= 0) {
        ioctl(d.syskey_fd, UI_DEV_DESTROY);
        close(d.syskey_fd);
    }
    return 0;
}
