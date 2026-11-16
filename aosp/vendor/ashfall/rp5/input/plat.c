/*
 * AshFall RP5 input daemon: platform helpers.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "plat.h"

#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

/* ASHFALL_STANDALONE builds a self-contained binary for /data/local/tmp
 * experiments (no liblog); it then logs to stderr. */
#if defined(__ANDROID__) && !defined(ASHFALL_STANDALONE)
#include <android/log.h>
#define AH_HAVE_ANDROID_LOG 1
#endif

static const char *g_tag = "ashfall-rp5-input";

void ah_log_set_tag(const char *tag)
{
    if (tag && tag[0])
        g_tag = tag;
}

void ah_logf(int prio, const char *fmt, ...)
{
    va_list ap;

    va_start(ap, fmt);
#if defined(AH_HAVE_ANDROID_LOG)
    __android_log_vprint(prio, g_tag, fmt, ap);
    va_end(ap);
    /*
     * Mirror to stderr as well: init redirects it to the kernel log
     * (`stdio_to_kmsg` in the service rc).  This daemon is started by
     * class_start core, i.e. possibly before logd accepts writes, so without
     * the mirror its startup lines (LED backend, thermal watch set,
     * calibration source) would be lost - exactly the lines needed to verify
     * the hardware on a fresh boot.
     */
    va_start(ap, fmt);
    fprintf(stderr, "%s/%s: ", prio >= AH_LOG_ERROR ? "E"
                              : prio >= AH_LOG_WARN ? "W"
                                                    : "I",
            g_tag);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
#else
    {
        const char *level = prio >= AH_LOG_ERROR ? "E"
                          : prio >= AH_LOG_WARN  ? "W"
                                                 : "I";
        fprintf(stderr, "%s/%s: ", level, g_tag);
        vfprintf(stderr, fmt, ap);
        fputc('\n', stderr);
    }
#endif
    va_end(ap);
}

int64_t ah_now_ms(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

#if defined(__ANDROID__)
struct prop_snapshot {
    char value[PROP_VALUE_MAX];
    uint32_t serial;
};

static void prop_capture(void *cookie, const char *name, const char *value,
                         uint32_t serial)
{
    struct prop_snapshot *snapshot = cookie;

    (void)name;
    snprintf(snapshot->value, sizeof(snapshot->value), "%s", value);
    snapshot->serial = serial;
}
#endif

int prop_get_str_serial(const char *name, char *buf, size_t len,
                        uint32_t *serial)
{
    if (!buf || len == 0)
        return -1;
    buf[0] = '\0';
    if (serial)
        *serial = 0;
#if defined(__ANDROID__)
    {
        const prop_info *info = __system_property_find(name);
        struct prop_snapshot snapshot;

        if (!info)
            return -1;
        memset(&snapshot, 0, sizeof(snapshot));
        __system_property_read_callback(info, prop_capture, &snapshot);
        snprintf(buf, len, "%s", snapshot.value);
        if (serial)
            *serial = snapshot.serial;
        return (int)strlen(buf);
    }
#else
    {
        const char *value = getenv(name);

        if (!value)
            return -1;
        snprintf(buf, len, "%s", value);
        return (int)strlen(buf);
    }
#endif
}

int prop_get_str(const char *name, char *buf, size_t len)
{
    return prop_get_str_serial(name, buf, len, NULL);
}

int prop_get_int(const char *name, int fallback)
{
    char buf[64];
    char *end = NULL;
    long value;

    if (prop_get_str(name, buf, sizeof(buf)) < 0)
        return fallback;
    errno = 0;
    value = strtol(buf, &end, 10);
    if (errno != 0 || end == buf)
        return fallback;
    while (end && (*end == ' ' || *end == '\t'))
        end++;
    if (end && *end != '\0')
        return fallback;
    if (value < INT_MIN || value > INT_MAX)
        return fallback;
    return (int)value;
}

int prop_get_int_range(const char *name, int fallback, int lo, int hi)
{
    /* Log a bad value once per distinct value, not once per tick. */
    static const char *logged_name[16];
    static int logged_value[16];
    int value = prop_get_int(name, fallback);
    int slot = -1;

    if (value >= lo && value <= hi)
        return value;

    for (int i = 0; i < 16; i++) {
        if (logged_name[i] == name) {
            slot = i;
            break;
        }
        if (!logged_name[i] && slot < 0) {
            slot = i;
            logged_name[i] = name;
            logged_value[i] = INT_MIN;
            break;
        }
    }
    if (slot >= 0) {
        if (logged_value[slot] != value) {
            logged_value[slot] = value;
            ah_logf(AH_LOG_WARN, "%s=%d outside %d..%d, using default %d", name,
                    value, lo, hi, fallback);
        }
    }
    return fallback;
}

void prop_set_str(const char *name, const char *value)
{
    if (!name || !value)
        return;
#if defined(__ANDROID__)
    if (__system_property_set(name, value) != 0)
        ah_logf(AH_LOG_WARN, "cannot set %s=%s", name, value);
#else
    ah_logf(AH_LOG_INFO, "prop %s=%s", name, value);
#endif
}
