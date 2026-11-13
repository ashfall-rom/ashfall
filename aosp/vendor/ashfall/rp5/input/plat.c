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

int prop_get_str(const char *name, char *buf, size_t len)
{
    if (!buf || len == 0)
        return -1;
    buf[0] = '\0';
#if defined(__ANDROID__)
    {
        char value[PROP_VALUE_MAX];

        if (__system_property_get(name, value) <= 0)
            return -1;
        snprintf(buf, len, "%s", value);
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
