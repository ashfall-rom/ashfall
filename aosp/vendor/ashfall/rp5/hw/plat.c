/*
 * AshFall RP5 hardware daemon: platform helpers (properties, sysfs, logging).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "plat.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

/* ASHFALL_STANDALONE builds the daemon as a self-contained binary for
 * /data/local/tmp experiments (no liblog); it then logs to stderr. */
#if defined(__ANDROID__) && !defined(ASHFALL_STANDALONE)
#include <android/log.h>
#define AH_HAVE_ANDROID_LOG 1
#endif

static const char *g_tag = "ashfall-hwd";

#ifdef ASHFALL_HOST
/* Host builds exercise the same code paths against a scratch directory. */
static char g_root[PATH_MAX];
static bool g_root_ready;

static void host_root_init(void)
{
    const char *root;

    if (g_root_ready)
        return;
    g_root_ready = true;
    root = getenv("ASHFALL_SYSFS_ROOT");
    if (root && root[0]) {
        snprintf(g_root, sizeof(g_root), "%s", root);
        /* Strip a trailing slash so "%s/%s" never yields a double slash. */
        size_t n = strlen(g_root);
        while (n > 1 && g_root[n - 1] == '/')
            g_root[--n] = '\0';
    }
}
#endif

static void host_path(const char *path, char *out, size_t len)
{
#ifdef ASHFALL_HOST
    host_root_init();
    snprintf(out, len, "%s%s", g_root, path);
#else
    snprintf(out, len, "%s", path);
#endif
}

const char *plat_resolve(const char *path, char *buf, size_t len)
{
    host_path(path, buf, len);
    return buf;
}

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

void ah_sleep_ms(int ms)
{
    if (ms <= 0)
        return;
    poll(NULL, 0, ms);
}

/* --- properties ---------------------------------------------------------- */

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

int prop_get_int_range(const char *name, int fallback, int lo, int hi)
{
    /* Log a bad value once per distinct value, not once per daemon tick. */
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

bool prop_get_bool(const char *name, bool fallback)
{
    char buf[64];

    if (prop_get_str(name, buf, sizeof(buf)) < 0)
        return fallback;
    if (!strcmp(buf, "1") || !strcmp(buf, "true") || !strcmp(buf, "on") ||
        !strcmp(buf, "yes"))
        return true;
    if (!strcmp(buf, "0") || !strcmp(buf, "false") || !strcmp(buf, "off") ||
        !strcmp(buf, "no"))
        return false;
    return fallback;
}

void prop_set_str(const char *name, const char *value)
{
#if defined(__ANDROID__)
    if (__system_property_set(name, value) != 0)
        ah_logf(AH_LOG_WARN, "cannot set %s=%s", name, value);
#else
    ah_logf(AH_LOG_INFO, "prop %s=%s", name, value);
#endif
}

void prop_set_int(const char *name, int value)
{
    char buf[32];

    snprintf(buf, sizeof(buf), "%d", value);
    prop_set_str(name, buf);
}

void prop_wait_any(int timeout_ms)
{
#if defined(__ANDROID__)
    /* The serial has to be remembered between calls: __system_property_wait
     * is a futex wait on that exact value, so a stale one returns at once.
     * The first call has no serial yet and returns immediately once, which
     * also teaches us the current value. */
    static uint32_t serial;
    struct timespec ts;
    struct timespec *tsp = NULL;

    if (timeout_ms < 50)
        timeout_ms = 50;
    ts.tv_sec = timeout_ms / 1000;
    ts.tv_nsec = (long)(timeout_ms % 1000) * 1000000L;
    tsp = &ts;
    /* NULL prop_info: wait for the global serial, i.e. any property change. */
    __system_property_wait(NULL, serial, &serial, tsp);
#else
    /* Host: nothing to wait for; behave like a sleep so timers still run. */
    ah_sleep_ms(timeout_ms);
#endif
}

/* --- sysfs --------------------------------------------------------------- */

int sysfs_read_str(const char *path, char *buf, size_t len)
{
    char real[PATH_MAX];
    ssize_t n;
    int fd;

    if (!buf || len == 0)
        return -1;
    buf[0] = '\0';
    host_path(path, real, sizeof(real));
    fd = open(real, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return -1;
    n = read(fd, buf, len - 1);
    close(fd);
    if (n < 0)
        return -1;
    buf[n] = '\0';
    /* sysfs always terminates attributes with a newline. */
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r' ||
                     buf[n - 1] == ' '))
        buf[--n] = '\0';
    return (int)n;
}

int sysfs_read_int(const char *path, int *out)
{
    char buf[64];
    char *end = NULL;
    long value;

    if (sysfs_read_str(path, buf, sizeof(buf)) < 0)
        return -1;
    errno = 0;
    value = strtol(buf, &end, 10);
    if (errno != 0 || end == buf)
        return -1;
    if (value < INT_MIN || value > INT_MAX)
        return -1;
    if (out)
        *out = (int)value;
    return 0;
}

int sysfs_write_str(const char *path, const char *value)
{
    char real[PATH_MAX];
    size_t len = strlen(value);
    ssize_t n;
    int fd;

    host_path(path, real, sizeof(real));
    /* O_TRUNC is a no-op on sysfs but keeps the host test files sane. */
    fd = open(real, O_WRONLY | O_CLOEXEC | O_TRUNC);
    if (fd < 0) {
        ah_logf(AH_LOG_WARN, "open %s for write: %s", path, strerror(errno));
        return -1;
    }
    n = write(fd, value, len);
    if (n < 0) {
        ah_logf(AH_LOG_WARN, "write %s: %s", path, strerror(errno));
        close(fd);
        return -1;
    }
    close(fd);
    return (size_t)n == len ? 0 : -1;
}

int sysfs_write_int(const char *path, int value)
{
    char buf[32];

    snprintf(buf, sizeof(buf), "%d", value);
    return sysfs_write_str(path, buf);
}

bool sysfs_present(const char *path)
{
    char real[PATH_MAX];

    host_path(path, real, sizeof(real));
    return access(real, F_OK) == 0;
}

bool sysfs_writable(const char *path)
{
    char real[PATH_MAX];

    host_path(path, real, sizeof(real));
    return access(real, W_OK) == 0;
}

bool sysfs_exists(const char *path)
{
    char real[PATH_MAX];
    char buf[8];

    host_path(path, real, sizeof(real));
    return access(real, F_OK) == 0 && sysfs_read_str(path, buf, sizeof(buf)) >= 0;
}
