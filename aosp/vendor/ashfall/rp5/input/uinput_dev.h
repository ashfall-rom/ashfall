/*
 * AshFall RP5 input: construction of the daemon's uinput device.
 *
 * The daemon's gamepad device identity follows fusion (docs/phase6.md §9.8):
 * fusion off is the phase-5 "AshFall RP5 Gamepad" with the MCU keymap, fusion
 * on is the Xbox-layout pad with the merged key/axis capability set plus force
 * feedback, so rumble can be relayed to the physical pads.  The
 * external-display pointer adds a second, separate "AshFall RP5 Pointer" device
 * (#131) built from the same spec, so the gamepad capabilities never gain
 * relative axes.
 *
 * The ioctl sequence lives behind struct uinput_dev_ops instead of in the
 * hardware loop because the kernel reports every mistake at the same place:
 * UI_DEV_CREATE fails with EINVAL long after the offending bit was set.  The
 * host tests capture the sequence and assert bits and order (#156).
 *
 * The rule that cost us a build (drivers/input/misc/uinput.c,
 * uinput_create_device()):
 *
 *     if (test_bit(EV_FF, dev->evbit) && !udev->ff_effects_max) {
 *             printk("ff_effects_max should be non-zero when FF_BIT is set");
 *             return -EINVAL;
 *     }
 *
 * so EV_FF is only ever set together with a non-zero
 * uinput_setup.ff_effects_max, and the device is rebuilt without FF when the
 * kernel refuses the rumble bit rather than retrying a device that can never
 * be created.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_UINPUT_DEV_H
#define ASHFALL_UINPUT_DEV_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Effect slots advertised by the fusion device; also bounds the daemon's
 * upload/playback bookkeeping. */
#define UINPUT_DEV_FF_EFFECTS 16

/*
 * Everything the device needs, so the caller keeps the identity and the
 * capability tables (mcu_keymap / fusion_key_caps) next to the MCU code.
 */
struct uinput_dev_spec {
    const char *name;
    uint16_t bustype;
    uint16_t vendor;
    uint16_t product;
    uint16_t version;
    const uint16_t *keys; /* EV_KEY codes; 0 entries are skipped */
    int key_count;
    const uint16_t *axes; /* EV_ABS codes, advertised in this order */
    int axis_count;
    const uint16_t *rels; /* EV_REL codes (external-display pointer, #131) */
    int rel_count;
    const uint16_t *props; /* INPUT_PROP_* bits (UI_SET_PROPBIT) */
    int prop_count;
    bool rumble; /* EV_FF + UI_SET_FFBIT(FF_RUMBLE) */
};

/*
 * Syscall seam.  `arg` carries the int argument of the bit-setting ioctls and
 * the pointer of UI_DEV_SETUP/UI_ABS_SETUP, so the recorder sees one call per
 * request in the order the kernel saw it.
 */
struct uinput_dev_ops {
    void *ctx;
    int (*open)(void *ctx);
    int (*ioctl)(void *ctx, int fd, unsigned long request, unsigned long arg);
    int (*write)(void *ctx, int fd, const void *buf, size_t len);
    void (*close)(void *ctx, int fd);
};

/* Absolute range advertised for an axis code.  Shared with the fusion merge so
 * a pad's own range is rescaled to exactly what this device reports. */
void uinput_dev_axis_range(uint16_t code, int *minimum, int *maximum);

/*
 * Open, configure and create the device.  Returns the fd, or -1 (the caller
 * logs and retries).  When the kernel refuses EV_FF/FF_RUMBLE the device is
 * created again without them and *rumble is false, so the daemon skips the
 * relay instead of logging a warning per effect.
 */
int uinput_dev_create(const struct uinput_dev_ops *ops,
                      const struct uinput_dev_spec *spec, bool *rumble);

#endif /* ASHFALL_UINPUT_DEV_H */
