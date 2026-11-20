/*
 * AshFall RP5 input: construction of the daemon's uinput device.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "uinput_dev.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>

#include <linux/input.h>
#include <linux/uinput.h>

void uinput_dev_axis_range(uint16_t code, int *minimum, int *maximum)
{
    if (!minimum || !maximum)
        return;
    if (code == ABS_BRAKE || code == ABS_GAS) {
        *minimum = 0;
        *maximum = 255;
    } else if (code == ABS_HAT0X || code == ABS_HAT0Y) {
        *minimum = -1;
        *maximum = 1;
    } else {
        *minimum = -32768;
        *maximum = 32767;
    }
}

static int set_bit(const struct uinput_dev_ops *ops, int fd,
                   unsigned long request, unsigned int bit)
{
    return ops->ioctl(ops->ctx, fd, request, (unsigned long)bit);
}

static long call_ptr(const struct uinput_dev_ops *ops, int fd,
                     unsigned long request, const void *arg)
{
    return ops->ioctl(ops->ctx, fd, request, (unsigned long)(uintptr_t)arg);
}

static void absinfo_for(uint16_t code, struct input_absinfo *info)
{
    int minimum = 0;
    int maximum = 0;

    memset(info, 0, sizeof(*info));
    uinput_dev_axis_range(code, &minimum, &maximum);
    info->minimum = minimum;
    info->maximum = maximum;
}

/*
 * One attempt at the whole sequence.  `rumble` is per attempt, not per spec:
 * the caller retries without FF when the kernel will not take it.
 */
static int build(const struct uinput_dev_ops *ops,
                 const struct uinput_dev_spec *spec, bool rumble,
                 bool *created_rumble)
{
    int fd = ops->open(ops->ctx);

    if (fd < 0)
        return -1;

    if (set_bit(ops, fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        (spec->axis_count > 0 &&
         set_bit(ops, fd, UI_SET_EVBIT, EV_ABS) < 0) ||
        (spec->rel_count > 0 &&
         set_bit(ops, fd, UI_SET_EVBIT, EV_REL) < 0) ||
        set_bit(ops, fd, UI_SET_EVBIT, EV_SYN) < 0)
        goto fail;

    for (int i = 0; i < spec->key_count; i++) {
        /* Best effort: one code a kernel does not know must not cost the
         * whole gamepad (the MCU keymap has holes for the D-pad bits). */
        if (spec->keys[i] == 0)
            continue;
        set_bit(ops, fd, UI_SET_KEYBIT, spec->keys[i]);
    }

    if (rumble) {
        /* Fatal on purpose: EV_FF without FF_RUMBLE would advertise rumble
         * the relay cannot produce, and EV_FF without ff_effects_max is the
         * UI_DEV_CREATE EINVAL from #156.  The caller retries without FF. */
        if (set_bit(ops, fd, UI_SET_EVBIT, EV_FF) < 0 ||
            set_bit(ops, fd, UI_SET_FFBIT, FF_RUMBLE) < 0)
            goto fail;
    }

    for (int i = 0; i < spec->axis_count; i++) {
        struct input_absinfo absinfo;

        if (spec->absinfo) {
            /* Caller-supplied range, parallel to the code list (#250). */
            absinfo = spec->absinfo[i];
        } else {
            absinfo_for(spec->axes[i], &absinfo);
        }
        if (set_bit(ops, fd, UI_SET_ABSBIT, spec->axes[i]) < 0)
            continue; /* an axis the kernel refuses is simply not advertised */
#ifdef UI_ABS_SETUP
        {
            struct uinput_abs_setup setup;

            memset(&setup, 0, sizeof(setup));
            setup.code = spec->axes[i];
            setup.absinfo = absinfo;
            call_ptr(ops, fd, UI_ABS_SETUP, &setup);
        }
#endif
    }

    /* Relative axes and input properties are the external-display pointer's (#131):
     * no range setup exists for EV_REL, and INPUT_PROP_POINTER is what makes
     * Android treat the device as a mouse instead of a gamepad.  Best effort,
     * like the key bits: a code this kernel does not know must not cost the
     * whole device. */
    /* REL_X is code 0, so unlike the MCU key table this list has no hole
     * sentinel. */
    for (int i = 0; i < spec->rel_count; i++)
        set_bit(ops, fd, UI_SET_RELBIT, spec->rels[i]);
    for (int i = 0; i < spec->prop_count; i++)
        set_bit(ops, fd, UI_SET_PROPBIT, spec->props[i]);

#ifdef UI_DEV_SETUP
    {
        struct uinput_setup setup;

        memset(&setup, 0, sizeof(setup));
        setup.id.bustype = spec->bustype;
        setup.id.vendor = spec->vendor;
        setup.id.product = spec->product;
        setup.id.version = spec->version;
        snprintf(setup.name, sizeof(setup.name), "%s",
                 spec->name ? spec->name : "");
        /* Never zero while EV_FF is set: that is the EINVAL above. */
        setup.ff_effects_max = rumble ? UINPUT_DEV_FF_EFFECTS : 0;
        if (call_ptr(ops, fd, UI_DEV_SETUP, &setup) < 0)
            goto fail;
    }
#else
    {
        struct uinput_user_dev dev;

        memset(&dev, 0, sizeof(dev));
        snprintf(dev.name, sizeof(dev.name), "%s", spec->name ? spec->name : "");
        dev.id.bustype = spec->bustype;
        dev.id.vendor = spec->vendor;
        dev.id.product = spec->product;
        dev.id.version = spec->version;
        dev.ff_effects_max = rumble ? UINPUT_DEV_FF_EFFECTS : 0;
        for (int i = 0; i < spec->axis_count; i++) {
            uint16_t code = spec->axes[i];

            if (code > ABS_MAX)
                continue;
            if (spec->absinfo) {
                dev.absmin[code] = spec->absinfo[i].minimum;
                dev.absmax[code] = spec->absinfo[i].maximum;
            } else {
                uinput_dev_axis_range(code, &dev.absmin[code],
                                      &dev.absmax[code]);
            }
        }
        if (ops->write && ops->write(ops->ctx, fd, &dev, sizeof(dev)) < 0)
            goto fail;
    }
#endif

    if (ops->ioctl(ops->ctx, fd, UI_DEV_CREATE, 0) < 0)
        goto fail;

    if (created_rumble)
        *created_rumble = rumble;
    return fd;

fail:
    {
        int saved = errno;

        /* Keep the kernel's reason: the caller logs strerror(errno). */
        ops->close(ops->ctx, fd);
        errno = saved;
    }
    return -1;
}

int uinput_dev_create(const struct uinput_dev_ops *ops,
                      const struct uinput_dev_spec *spec, bool *rumble)
{
    if (rumble)
        *rumble = false;
    if (!ops || !spec || !ops->open || !ops->ioctl || !ops->close)
        return -1;
    if (spec->rumble) {
        int fd = build(ops, spec, true, rumble);

        if (fd >= 0)
            return fd;
        /* The device is still the controller without rumble; a pad with no
         * force feedback is far better than no pad at all. */
    }
    return build(ops, spec, false, rumble);
}
