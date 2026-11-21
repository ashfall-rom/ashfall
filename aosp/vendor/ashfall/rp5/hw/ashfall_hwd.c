/*
 * ashfall-hwd - AshFall RP5 hardware daemon: fan and stick lighting.
 *
 * The AshFall Parts app only writes persist.ashfall.* properties; this daemon
 * is the single writer of the hardware.  It applies settings at boot and at
 * runtime, keeps the fan inside the measured useful duty range, runs the
 * always-on overheat safety override and publishes ashfall.status.* values.
 *
 * Contract: docs/phase5.md §2.  Device notes: docs/rp5/hw.md.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "cpu.h"
#include "fan.h"
#include "gpu.h"
#include "hwd.h"
#include "led.h"
#include "mem.h"
#include "plat.h"
#include "thermal.h"
#include "throttle.h"

#include <limits.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* The preview curve watch is a full curve string, not a heartbeat token. */
_Static_assert(HWD_VALUE_WATCH_MAX >= FAN_CURVE_MAX_LEN + 1,
               "HWD_VALUE_WATCH_MAX must hold a whole fan curve");

#define PROP_FAN_MODE "persist.ashfall.fan.mode"
#define PROP_FAN_MANUAL "persist.ashfall.fan.manual_pct"
#define PROP_FAN_CURVE "persist.ashfall.fan.curve"
#define PROP_FAN_KICK "persist.ashfall.fan.kick"
#define PROP_FAN_KICK_PCT "persist.ashfall.fan.kick_pct"
#define PROP_FAN_SCREEN_OFF "persist.ashfall.fan.screen_off"
#define PROP_FAN_MIN_PCT "persist.ashfall.fan.min_pct"
#define PROP_FAN_MAX_PCT "persist.ashfall.fan.max_pct"

#define PROP_LED_MODE "persist.ashfall.led.mode"
#define PROP_LED_LEFT "persist.ashfall.led.color_left"
#define PROP_LED_RIGHT "persist.ashfall.led.color_right"
#define PROP_LED_SYNC "persist.ashfall.led.sync"
#define PROP_LED_BRIGHTNESS "persist.ashfall.led.brightness"
#define PROP_LED_SPEED "persist.ashfall.led.speed"

#define PROP_UI_FAN_LIVE "ashfall.ui.fan_live"
#define PROP_UI_POWER_LIVE "ashfall.ui.power_live"
#define PROP_UI_FAN_PREVIEW "ashfall.ui.fan_preview_curve"
#define PROP_UI_LED_OVERRIDE "ashfall.ui.led_override"
#define PROP_UI_LED_OVERRIDE_LIVE "ashfall.ui.led_override_live"

#define PROP_PERF_OVERRIDE "ashfall.perf.throttle_override"
#define PROP_PERF_PERMANENT "persist.ashfall.perf.throttle_override_permanent"
#define PROP_GPU_MAX_MHZ "persist.ashfall.perf.gpu_max_mhz"
#define PROP_GPU_IDLE_MS "persist.ashfall.perf.gpu_idle_ms"
#define PROP_CPU_MAX_SILVER "persist.ashfall.perf.cpu_max_silver"
#define PROP_CPU_MAX_GOLD "persist.ashfall.perf.cpu_max_gold"
#define PROP_CPU_MAX_PRIME "persist.ashfall.perf.cpu_max_prime"
#define PROP_CPU_GOV "persist.ashfall.perf.cpu_gov"

#define PROP_MEM_SWAP_MB "persist.ashfall.mem.swap_mb"

#define STATUS_FAN_TEMP "ashfall.status.fan.temp"
#define STATUS_FAN_PCT "ashfall.status.fan.pct"
#define STATUS_FAN_RPM "ashfall.status.fan.rpm"
#define STATUS_FAN_SAFETY "ashfall.status.fan.safety"
#define STATUS_PERF_THROTTLE "ashfall.status.perf.throttle"
#define STATUS_GPU_MAX_MHZ "ashfall.status.perf.gpu_max_mhz"
#define STATUS_GPU_OC "ashfall.status.perf.gpu_oc"
#define STATUS_PERF_GPU_MHZ "ashfall.status.perf.gpu_mhz"

/* Phase 6 telemetry (docs/phase6.md §9.3, docs/rp5/hw.md §12/§13). */
#define STATUS_PERF_CPU_SILVER "ashfall.status.perf.cpu_mhz_silver"
#define STATUS_PERF_CPU_GOLD "ashfall.status.perf.cpu_mhz_gold"
#define STATUS_PERF_CPU_PRIME "ashfall.status.perf.cpu_mhz_prime"
#define STATUS_PERF_CPU_LOAD "ashfall.status.perf.cpu_load_pct"
#define STATUS_PERF_GPU_LOAD "ashfall.status.perf.gpu_load_pct"
#define STATUS_MEM_SWAP_MB "ashfall.status.mem.swap_mb"
#define STATUS_MEM_SWAP_TOTAL_KB "ashfall.status.mem.swap_total_kb"
#define STATUS_MEM_SWAP_USED_KB "ashfall.status.mem.swap_used_kb"

/* IDLE_WAIT_MS also bounds how late a liveness property change is noticed, so
 * it must stay below STATUS_LIVE_MS.  The status period itself is chosen by
 * hwd_status_interval_ms() in hwd.h. */
#define IDLE_WAIT_MS 500
/* Panel off (#233): no page can be reading ashfall.status.*, so the loop may
 * sleep as long as the fan/thermal tick (FAN_SAMPLE_MS) allows.  Property
 * changes - including the screen transition itself - still wake the daemon
 * through prop_wait_any(), so this only bounds the "nothing happened" case. */
#define IDLE_WAIT_MS_SCREEN_OFF 2000
/* How often the status properties are refreshed while the panel is off.  They
 * only feed the Parts pages, so the tick exists purely to keep the first
 * publish after a wake cheap; the screen-on transition forces one anyway. */
#define STATUS_SCREEN_OFF_MS 30000
/* Retry period for LED writes that failed (vendor init chmod race, node not
 * there yet): lighting must not stay dark for the whole boot. */
#define LED_RETRY_MS 500
/* Full thermal sweep period: re-picks the hottest zones (a few temp reads,
 * see thermal.h for why the daemon does not read all 16 zones per tick). */
#define THERMAL_SWEEP_MS 300000
/* Retry while the kgsl devfreq node is not up yet (vendor init/class core). */
#define GPU_RETRY_MS 5000
/* Retry while cpufreq / /data is not up yet (class core ordering). */
#define CPU_RETRY_MS 5000
#define MEM_RETRY_MS 5000
/* Bounded retries (60 s) for a memory request that is not in effect yet. */
#define MEM_RETRY_BUDGET 12

static volatile sig_atomic_t g_running = 1;

static void on_signal(int sig)
{
    (void)sig;
    g_running = 0;
}

struct status_cache {
    int temp;
    int pct;
    int rpm;
    int safety;
    bool valid;
};

struct gpu_status_cache {
    int mhz;
    int oc;
    bool valid;
};

static void read_fan_settings(fan_settings *out, bool *changed)
{
    fan_settings next;
    fan_settings defaults;
    char buf[FAN_CURVE_MAX_LEN + 1];
    static bool logged_limits;

    fan_settings_default(&defaults);
    next = defaults;

    if (prop_get_str(PROP_FAN_MODE, buf, sizeof(buf)) > 0) {
        fan_mode mode;

        if (fan_parse_mode(buf, &mode))
            next.mode = mode;
    }
    /* Out-of-range values are invalid per docs/phase5.md §2 and fall back to
     * the documented default instead of being clamped to the boundary. */
    next.manual_pct =
        prop_get_int_range(PROP_FAN_MANUAL, defaults.manual_pct, 0, 100);
    next.kick = prop_get_bool(PROP_FAN_KICK, defaults.kick);
    next.kick_pct = prop_get_int_range(PROP_FAN_KICK_PCT, defaults.kick_pct,
                                       FAN_KICK_MIN_PCT, FAN_KICK_MAX_PCT);
    if (prop_get_str(PROP_FAN_SCREEN_OFF, buf, sizeof(buf)) > 0)
        next.off_when_screen_off = strcmp(buf, "run") != 0;
    next.min_pct = prop_get_int_range(PROP_FAN_MIN_PCT, defaults.min_pct, 0, 100);
    next.max_pct = prop_get_int_range(PROP_FAN_MAX_PCT, defaults.max_pct, 0, 100);
    /* min > max is an invalid pair even when both values are in range: use
     * the defaults for both and say so once (docs/phase5.md §7.1). */
    if (!fan_limits_normalise(&next.min_pct, &next.max_pct) && !logged_limits) {
        ah_logf(AH_LOG_WARN,
                "fan: invalid min_pct/max_pct pair; using the defaults (%d/%d)",
                next.min_pct, next.max_pct);
        logged_limits = true;
    }
    if (prop_get_str(PROP_FAN_CURVE, buf, sizeof(buf)) > 0) {
        fan_curve curve;

        if (fan_parse_curve(buf, &curve))
            next.curve = curve;
    }

    if (memcmp(&next, out, sizeof(next)) != 0) {
        *out = next;
        *changed = true;
    }
}

/* --- ashfall.ui.* publisher heartbeat (issue #94) ------------------------
 *
 * ashfall.ui.* values are written by the AshFall Parts app only.  The app
 * normally clears them when the page closes, but a force-stop or a crash
 * skips that path and would otherwise latch the value (a stuck LED override,
 * a fan preview curve that outlives its editor).  Publishers therefore
 * refresh a companion liveness property every HWD_UI_HEARTBEAT_MS with a
 * token that changes on every write, and the daemon ignores the value once
 * the last token change is older than HWD_UI_HEARTBEAT_TIMEOUT_MS.
 *
 * The token is opaque (the app writes SystemClock.elapsedRealtime()); only
 * "different from the previous sample" matters.  This keeps liveness visible
 * in the value itself instead of depending on the property service bumping a
 * write serial for an identical value, which is an implementation detail
 * (issue #180).  The clock is the daemon's own CLOCK_MONOTONIC (ah_now_ms):
 * only observation times are compared, never the app's clock.
 */
static bool ui_heartbeat_sample(struct hwd_ui_heartbeat *beat,
                                const char *prop, int64_t now_ms)
{
    char token[HWD_UI_HEARTBEAT_TOKEN_MAX];

    if (prop_get_str(prop, token, sizeof(token)) <= 0) {
        /* Empty/absent: no heartbeat (and forget the token, so a later
         * re-write of the same value counts as a change). */
        hwd_ui_heartbeat_seed(beat, "");
        return false;
    }
    return hwd_ui_heartbeat_update(beat, token, now_ms);
}

/* Record the token already present without counting it as a live beat. */
static void ui_heartbeat_seed(struct hwd_ui_heartbeat *beat, const char *prop)
{
    char token[HWD_UI_HEARTBEAT_TOKEN_MAX];

    if (prop_get_str(prop, token, sizeof(token)) <= 0)
        token[0] = '\0';
    hwd_ui_heartbeat_seed(beat, token);
}

/* ashfall.ui.fan_preview_curve: live curve editing.  While it is non-empty,
 * valid and the owning UI's heartbeat is fresh, smart mode follows it instead
 * of persist.ashfall.fan.curve.  The app clears the property when the editor
 * closes; the heartbeat covers the paths where it cannot (force-stop).
 * *present reports a non-empty property so the caller can tell an expiry
 * (log it) from the app's normal clear (do not). */
static void read_fan_preview(fan_state *fan, bool live_fresh, bool *present,
                             bool *changed)
{
    fan_curve curve;
    char buf[FAN_CURVE_MAX_LEN + 1];
    bool valid = false;

    memset(&curve, 0, sizeof(curve));
    *present = false;
    if (prop_get_str(PROP_UI_FAN_PREVIEW, buf, sizeof(buf)) > 0 && buf[0]) {
        *present = true;
        if (live_fresh)
            valid = fan_parse_curve(buf, &curve);
    }

    if (valid != fan->preview_valid ||
        (valid && memcmp(&curve, &fan->preview, sizeof(curve)) != 0)) {
        fan->preview = curve;
        fan->preview_valid = valid;
        *changed = true;
    }
}

/* ashfall.ui.led_override is honoured only while its publisher heartbeat
 * (ashfall.ui.led_override_live) is fresh; empty = normal lighting.
 * *present reports a non-empty property so the caller can tell an expiry
 * (log it) from the app's normal clear (do not). */

static void read_led_settings(led_settings *out, bool override_live,
                              bool *present, bool *changed)
{
    led_settings next;
    led_settings defaults;
    char buf[64];
    rgb_t colour;

    led_settings_default(&defaults);
    next = defaults;

    if (prop_get_str(PROP_LED_MODE, buf, sizeof(buf)) > 0) {
        led_mode mode;

        if (led_parse_mode(buf, &mode))
            next.mode = mode;
    }
    if (prop_get_str(PROP_LED_LEFT, buf, sizeof(buf)) > 0 &&
        led_parse_colour(buf, &colour))
        next.left = colour;
    if (prop_get_str(PROP_LED_RIGHT, buf, sizeof(buf)) > 0 &&
        led_parse_colour(buf, &colour))
        next.right = colour;
    next.sync = prop_get_bool(PROP_LED_SYNC, defaults.sync);
    next.brightness =
        prop_get_int_range(PROP_LED_BRIGHTNESS, defaults.brightness, 0, 100);
    next.speed = prop_get_int_range(PROP_LED_SPEED, defaults.speed, 1, 10);

    /* ashfall.ui.led_override="RRGGBB:RRGGBB" (left:right, final colours incl.
     * intensity), used by the gamepad tester.  Empty = normal lighting.  A
     * non-empty value without a fresh heartbeat is stale: the app that owned
     * it is gone (force-stop/crash) and the configured lighting returns. */
    next.override_valid = false;
    *present = false;
    if (prop_get_str(PROP_UI_LED_OVERRIDE, buf, sizeof(buf)) > 0 && buf[0]) {
        *present = true;
        if (override_live && strlen(buf) == 13 && buf[6] == ':') {
            char left[7];
            char right[7];
            rgb_t l;
            rgb_t r;

            memcpy(left, buf, 6);
            left[6] = '\0';
            memcpy(right, buf + 7, 6);
            right[6] = '\0';
            if (led_parse_colour(left, &l) && led_parse_colour(right, &r)) {
                next.override[0] = l;
                next.override[1] = r;
                next.override_valid = true;
            }
        }
    }

    if (memcmp(&next, out, sizeof(next)) != 0) {
        *out = next;
        *changed = true;
    }
}

/* ashfall.perf.throttle_override is non-persistent.  Returns true when the
 * property is present; *requested is its value. */
static bool perf_override_requested(bool *requested)
{
    char buf[16];

    if (prop_get_str(PROP_PERF_OVERRIDE, buf, sizeof(buf)) <= 0)
        return false;
    *requested = !strcmp(buf, "1");
    return true;
}

/* --- phase 6: CPU caps / governor and memory settings --------------------- */

struct cpu_settings {
    int max_mhz[CPU_CLUSTER_COUNT];
    char governor[CPU_GOV_LEN];
};

struct mem_settings {
    int swap_mb;
};

static void cpu_settings_default(struct cpu_settings *out)
{
    memset(out, 0, sizeof(*out));
    snprintf(out->governor, sizeof(out->governor), "schedutil");
}

/* Everything is re-read and clamped every loop like the fan settings; the
 * daemon is the only writer of cpufreq and a property can come from adb. */
static void read_cpu_settings(struct cpu_settings *out, bool *changed)
{
    struct cpu_settings next;
    char buf[CPU_GOV_LEN];

    cpu_settings_default(&next);
    next.max_mhz[CPU_CLUSTER_SILVER] =
        prop_get_int_range(PROP_CPU_MAX_SILVER, 0, 0, CPU_MAX_MHZ);
    next.max_mhz[CPU_CLUSTER_GOLD] =
        prop_get_int_range(PROP_CPU_MAX_GOLD, 0, 0, CPU_MAX_MHZ);
    next.max_mhz[CPU_CLUSTER_PRIME] =
        prop_get_int_range(PROP_CPU_MAX_PRIME, 0, 0, CPU_MAX_MHZ);
    if (prop_get_str(PROP_CPU_GOV, buf, sizeof(buf)) > 0 && buf[0])
        snprintf(next.governor, sizeof(next.governor), "%s", buf);

    if (memcmp(&next, out, sizeof(next)) != 0) {
        *out = next;
        *changed = true;
    }
}

static void mem_settings_default(struct mem_settings *out)
{
    memset(out, 0, sizeof(*out));
}

/* A swapfile request that is still not in effect: /data was not mounted, or
 * the allocation/write failed.  Retried on MEM_RETRY_MS for a bounded number of
 * attempts so a permanent failure (a policy denial, a full /data) cannot turn
 * into a 5 s retry loop that re-allocates a swapfile. */
static bool mem_needs_retry(const struct mem_settings *set, const mem_state *mem)
{
    return set->swap_mb > 0 && mem->swap_mb < 0;
}

static void read_mem_settings(struct mem_settings *out, bool *changed)
{
    struct mem_settings next;

    mem_settings_default(&next);
    /* Read unclamped: mem_clamp_swap() does the real clamping (16384 MB and
     * the /data free-space reserve), so a request above the UI cap is clamped
     * rather than falling back to 0 = off. */
    next.swap_mb = prop_get_int(PROP_MEM_SWAP_MB, 0);
    if (next.swap_mb < 0)
        next.swap_mb = 0;

    if (memcmp(&next, out, sizeof(next)) != 0) {
        *out = next;
        *changed = true;
    }
}

/* Publish an int only when it changed.  The caller's cache starts at INT_MIN,
 * so the first tick always publishes (including the -1 unavailable values). */
static void publish_int(const char *name, int value, int *cache)
{
    if (*cache == value)
        return;
    prop_set_int(name, value);
    *cache = value;
}

static void publish_cpu_status(const cpu_state *cpu, int *cache)
{
    publish_int(STATUS_PERF_CPU_SILVER, cpu_status_mhz(cpu, CPU_CLUSTER_SILVER),
                &cache[0]);
    publish_int(STATUS_PERF_CPU_GOLD, cpu_status_mhz(cpu, CPU_CLUSTER_GOLD),
                &cache[1]);
    publish_int(STATUS_PERF_CPU_PRIME, cpu_status_mhz(cpu, CPU_CLUSTER_PRIME),
                &cache[2]);
}

static void publish_mem_status(const mem_state *mem, int *cache)
{
    int swap_total = -1;
    int swap_used = -1;

    mem_status_swap(&swap_total, &swap_used);

    publish_int(STATUS_MEM_SWAP_MB, mem->swap_mb, &cache[0]);
    publish_int(STATUS_MEM_SWAP_TOTAL_KB, swap_total, &cache[1]);
    publish_int(STATUS_MEM_SWAP_USED_KB, swap_used, &cache[2]);
}

static void publish_gpu_status(const gpu_state *gpu,
                               struct gpu_status_cache *cache)
{
    int mhz = gpu_status_mhz(gpu);
    int oc = gpu_status_oc(gpu);

    if (!cache->valid || cache->mhz != mhz) {
        prop_set_int(STATUS_GPU_MAX_MHZ, mhz);
        cache->mhz = mhz;
    }
    if (!cache->valid || cache->oc != oc) {
        prop_set_int(STATUS_GPU_OC, oc);
        cache->oc = oc;
    }
    cache->valid = true;
}

/* --- suspend diagnostics (issue #233) ------------------------------------
 * The AP is kept awake by a userspace alarmtimer the kernel only names
 * "[timerfd]"; naming the owner needs /proc/<pid>/fd and debugfs, i.e. root,
 * which a field device does not have.  The daemon already runs as root, so a
 * shell-settable token makes it write a one-shot snapshot to its log, which
 * `adb shell` can read without any file-permission or sepolicy work:
 *
 *     adb shell setprop debug.ashfall.wakeups 1
 *     adb shell logcat -d -s ashfall-hwd | grep 'wakeups|'
 *
 * Any new value triggers a new dump, so the property can be re-used to sample
 * a later window. */
#define PROP_DEBUG_WAKEUPS "debug.ashfall.wakeups"
#define PROP_DEBUG_TRACE "debug.ashfall.trace"
/* One-shot power experiments (issue #233): the suspects in the ~140 mA gap
 * against stock are all root-only sysfs, so the daemon applies them and the
 * drain harness does the measuring.
 *   wifi-pm  - let the WLAN PCIe endpoint use runtime PM (it is pinned on)
 *   usb2-off - unbind the secondary dwc3 controller (runtime-active, idle)
 *   reboot undoes usb2-off; a reboot or "wifi-pm-on" undoes the first. */
#define PROP_DEBUG_POWERTEST "debug.ashfall.powertest"

#if defined(__ANDROID__)
#include <dirent.h>
#include <errno.h>
#include <sys/mount.h>

static void wakeup_dump_sources(void)
{
    char line[512];
    FILE *in;

    in = fopen("/sys/kernel/debug/wakeup_sources", "re");
    if (!in) {
        ah_logf(AH_LOG_WARN, "wakeups| no wakeup_sources (%s)", strerror(errno));
        return;
    }
    while (fgets(line, sizeof(line), in) != NULL) {
        line[strcspn(line, "\n")] = '\0';
        if (line[0] != '\0')
            ah_logf(AH_LOG_INFO, "wakeups| %s", line);
    }
    fclose(in);
}

/* Every process holding a timerfd: the kernel reports those as the wakeup
 * source "[timerfd]" and never says which one. */
static void wakeup_dump_fds(void)
{
    DIR *proc = opendir("/proc");
    struct dirent *pid;

    if (!proc) {
        ah_logf(AH_LOG_WARN, "wakeups| no /proc (%s)", strerror(errno));
        return;
    }
    while ((pid = readdir(proc)) != NULL) {
        char fd_dir[PATH_MAX];
        DIR *fds;
        struct dirent *fd;

        if (pid->d_name[0] < '0' || pid->d_name[0] > '9')
            continue;
        snprintf(fd_dir, sizeof(fd_dir), "/proc/%s/fd", pid->d_name);
        fds = opendir(fd_dir);
        if (!fds)
            continue;
        while ((fd = readdir(fds)) != NULL) {
            char path[PATH_MAX], link[PATH_MAX], comm[128] = "-";
            char cmd[400] = "-";
            ssize_t len;
            FILE *f;

            snprintf(path, sizeof(path), "%s/%s", fd_dir, fd->d_name);
            len = readlink(path, link, sizeof(link) - 1);
            if (len <= 0)
                continue;
            link[len] = '\0';
            if (!strstr(link, "timerfd"))
                continue;
            snprintf(path, sizeof(path), "/proc/%s/comm", pid->d_name);
            f = fopen(path, "re");
            if (f) {
                if (fgets(comm, sizeof(comm), f))
                    comm[strcspn(comm, "\n")] = '\0';
                fclose(f);
            }
            snprintf(path, sizeof(path), "/proc/%s/cmdline", pid->d_name);
            f = fopen(path, "re");
            if (f) {
                size_t n = fread(cmd, 1, sizeof(cmd) - 1, f);
                size_t i;

                cmd[n] = '\0';
                for (i = 0; i < n; i++)
                    if (cmd[i] == '\0')
                        cmd[i] = ' ';
                fclose(f);
            }
            /* fdinfo names the timerfd clock: only CLOCK_BOOTTIME_ALARM (7)
             * is a wakeup source, and it_value is how long until it fires. */
            {
                char path2[PATH_MAX], info[256] = "-", line[128];

                snprintf(path2, sizeof(path2), "/proc/%s/fdinfo/%s",
                         pid->d_name, fd->d_name);
                f = fopen(path2, "re");
                if (f) {
                    size_t used = 0;

                    while (fgets(line, sizeof(line), f) != NULL &&
                           used < sizeof(info) - 1) {
                        char *value = strchr(line, ':');
                        int n;

                        line[strcspn(line, "\n")] = '\0';
                        /* ticks is the expiration counter: the timerfd that
                         * matches the wake rate in wakeup_sources is the
                         * owner, and this is the only place it is visible. */
                        if (strncmp(line, "clockid", 7) != 0 &&
                            strncmp(line, "ticks", 5) != 0 &&
                            strncmp(line, "it_value", 8) != 0)
                            continue;
                        if (value != NULL)
                            *value = '=';
                        n = snprintf(info + used, sizeof(info) - used, "%s%s",
                                     used ? " " : "", line);
                        if (n > 0)
                            used += (size_t)n;
                    }
                    fclose(f);
                }
                ah_logf(AH_LOG_INFO, "wakeups| pid %s fd %s -> %s (%s) [%s] %s",
                        pid->d_name, fd->d_name, link, comm, cmd, info);
            }
        }
        closedir(fds);
    }
    closedir(proc);
}

/* Wakeup-source tracing (issue #233): the fd scan names every alarm timerfd
 * but cannot say which one fires, and the kernel resets a timerfd's own
 * counter when its owner reads it.  ftrace has the answer and it needs root to
 * switch on:
 *
 *     adb shell setprop debug.ashfall.trace on
 *     ... wait 30 s ...
 *     adb shell setprop debug.ashfall.trace off
 *     adb shell logcat -d -s ashfall-hwd | grep 'wakeuptrace|'
 *
 * The trace line carries the task that activated the source, so the timerfd
 * owner names itself. */
#define TRACEFS "/sys/kernel/tracing"
#define TRACE_MAX_LINES 120

static const char *const trace_events[] = {
    "wakeup_source_activate",
    "wakeup_source_deactivate",
};

static void trace_set(bool on)
{
    char path[PATH_MAX];
    size_t i;

    mount("none", TRACEFS, "tracefs", 0, NULL);
    for (i = 0; i < sizeof(trace_events) / sizeof(trace_events[0]); i++) {
        FILE *f;

        snprintf(path, sizeof(path), TRACEFS "/events/power/%s/enable",
                 trace_events[i]);
        f = fopen(path, "we");
        if (f == NULL) {
            ah_logf(AH_LOG_WARN, "trace: cannot %s %s (%s)",
                    on ? "enable" : "disable", trace_events[i],
                    strerror(errno));
            continue;
        }
        fputs(on ? "1" : "0", f);
        fclose(f);
    }
    ah_logf(AH_LOG_INFO, "trace: wakeup events %s", on ? "on" : "off");
}

static void trace_clear(void)
{
    FILE *f = fopen(TRACEFS "/trace", "we");

    if (f != NULL)
        fclose(f);
}

static void trace_dump(void)
{
    char line[512];
    int shown = 0;
    FILE *f;

    f = fopen(TRACEFS "/trace", "re");
    if (f == NULL) {
        ah_logf(AH_LOG_WARN, "trace: cannot read trace (%s)", strerror(errno));
        return;
    }
    while (fgets(line, sizeof(line), f) != NULL) {
        line[strcspn(line, "\n")] = '\0';
        if (strstr(line, "timerfd") == NULL)
            continue;
        ah_logf(AH_LOG_INFO, "wakeuptrace| %s", line);
        if (++shown >= TRACE_MAX_LINES)
            break;
    }
    fclose(f);
    ah_logf(AH_LOG_INFO, "wakeuptrace| %d timerfd lines", shown);
}

static void powertest_apply(const char *what)
{
    if (!strcmp(what, "wifi-pm")) {
        sysfs_write_str("/sys/bus/pci/devices/0000:01:00.0/power/control", "auto");
        sysfs_write_str("/sys/bus/pci/devices/0000:00:00.0/power/control", "auto");
    } else if (!strcmp(what, "wifi-pm-on")) {
        sysfs_write_str("/sys/bus/pci/devices/0000:01:00.0/power/control", "on");
        sysfs_write_str("/sys/bus/pci/devices/0000:00:00.0/power/control", "on");
    } else if (!strcmp(what, "usb2-off")) {
        /* The gadget controller is a600000.dwc3; the earlier probe wrote the
         * wrong name and silently did nothing (issue #233). */
        int rc = sysfs_write_str("/sys/bus/platform/drivers/dwc3/unbind",
                                 "a600000.dwc3");

        ah_logf(AH_LOG_INFO, "powertest: unbind a600000.dwc3 rc=%d bound=%d", rc,
                access("/sys/bus/platform/devices/a600000.dwc3/driver", F_OK) == 0);
    } else if (!strcmp(what, "usb2-on")) {
        int rc = sysfs_write_str("/sys/bus/platform/drivers/dwc3/bind",
                                 "a600000.dwc3");

        ah_logf(AH_LOG_INFO, "powertest: rebind a600000.dwc3 rc=%d", rc);
    } else if (!strcmp(what, "usb-pm")) {
        int rc = sysfs_write_str("/sys/bus/platform/devices/a600000.dwc3/power/control",
                                 "auto");

        ah_logf(AH_LOG_INFO, "powertest: a600000.dwc3 control=auto rc=%d", rc);
    } else if (!strcmp(what, "usb-pm-on")) {
        sysfs_write_str("/sys/bus/platform/devices/a600000.dwc3/power/control", "on");
    } else {
        ah_logf(AH_LOG_WARN, "powertest: unknown '%s'", what);
        return;
    }
    ah_logf(AH_LOG_INFO, "powertest: applied '%s'", what);
}

/* Log a root-only debugfs file line by line (#233). */
static void dump_file(const char *path, const char *tag, int max_lines)
{
    char line[512];
    int shown = 0;
    FILE *f = fopen(path, "re");

    if (f == NULL) {
        ah_logf(AH_LOG_WARN, "wakeups| [%s] %s: %s", tag, path, strerror(errno));
        return;
    }
    while (fgets(line, sizeof(line), f) != NULL && shown < max_lines) {
        line[strcspn(line, "\n")] = '\0';
        if (line[0] == '\0')
            continue;
        ah_logf(AH_LOG_INFO, "wakeups| [%s] %s", tag, line);
        shown++;
    }
    fclose(f);
}

/* Only clocks that are actually enabled say anything about the idle draw. */
static void dump_enabled_clocks(void)
{
    char line[512], name[128];
    int count = 0, shown = 0;
    FILE *f = fopen("/sys/kernel/debug/clk/clk_summary", "re");

    if (f == NULL) {
        ah_logf(AH_LOG_WARN, "wakeups| [clk] unavailable: %s", strerror(errno));
        return;
    }
    while (fgets(line, sizeof(line), f) != NULL && shown < 40) {
        if (sscanf(line, " %127s %d", name, &count) != 2)
            continue;
        if (count <= 0)
            continue;
        line[strcspn(line, "\n")] = '\0';
        ah_logf(AH_LOG_INFO, "wakeups| [clk] %s", line);
        shown++;
    }
    fclose(f);
}

static void wakeup_dump(void)
{
    /* debugfs is not mounted by default. */
    mount("none", "/sys/kernel/debug", "debugfs", 0, NULL);
    ah_logf(AH_LOG_INFO, "wakeups| ==== snapshot ====");
    wakeup_dump_sources();
    dump_file("/sys/kernel/debug/pm_genpd/pm_genpd_summary", "pd", 45);
    dump_file("/sys/kernel/debug/regulator/regulator_summary", "reg", 45);
    dump_file("/sys/kernel/debug/suspend_stats/success_count", "ss", 1);
    dump_file("/sys/kernel/debug/suspend_stats/fail_count", "ss", 1);
    dump_file("/sys/kernel/debug/suspend_stats/last_failed_dev", "ss", 1);
    dump_enabled_clocks();
    wakeup_dump_fds();
    ah_logf(AH_LOG_INFO, "wakeups| ==== end ====");
}
#else
static void wakeup_dump(void) {}
static void powertest_apply(const char *what) { (void)what; }
static void trace_set(bool on) { (void)on; }
static void trace_clear(void) {}
static void trace_dump(void) {}
#endif

static void publish_throttle_status(const throttle_state *throttle,
                                    int *cache)
{
    int value = throttle_status(throttle);

    if (value != *cache) {
        prop_set_int(STATUS_PERF_THROTTLE, value);
        *cache = value;
    }
}

/* ashfall.ui.fan_live is "0" (or empty) with no fan UI and an opaque changing
 * heartbeat token otherwise (issue #180), so the fast status cadence only
 * needs "non-zero".  prop_get_bool() would read the token as false. */
static bool fan_ui_visible(void)
{
    char buf[HWD_UI_HEARTBEAT_TOKEN_MAX];

    if (prop_get_str(PROP_UI_FAN_LIVE, buf, sizeof(buf)) <= 0)
        return false;
    return buf[0] && strcmp(buf, "0") != 0;
}

/* Display state published by framework DisplayPowerController: 1 = off,
 * 2 = on, 3/4 = doze, 5 = VR, 6 = on (suspended).  Anything else (or a
 * missing property, e.g. very early boot) is treated as "screen on". */
static bool screen_is_on(void)
{
    char buf[16];
    int state;

    if (prop_get_str("debug.tracing.screen_state", buf, sizeof(buf)) < 0)
        return true;
    state = atoi(buf);
    return state != 1;
}

static void publish_status(const fan_state *fan, int temp_tenths,
                           struct status_cache *cache, int64_t *next_ms,
                           int64_t now, int interval)
{
    struct status_cache next;

    next.temp = temp_tenths;
    next.pct = fan->out_pct; /* -1 = unknown (sysfs write failed) */
    next.rpm = fan->rpm;
    next.safety = fan->safety ? 1 : 0;
    next.valid = true;

    if (!cache->valid || cache->temp != next.temp)
        prop_set_int(STATUS_FAN_TEMP, next.temp);
    if (!cache->valid || cache->pct != next.pct)
        prop_set_int(STATUS_FAN_PCT, next.pct);
    if (!cache->valid || cache->rpm != next.rpm)
        prop_set_int(STATUS_FAN_RPM, next.rpm);
    if (!cache->valid || cache->safety != next.safety)
        prop_set_int(STATUS_FAN_SAFETY, next.safety);

    *cache = next;
    *next_ms = now + interval;
}

int main(void)
{
    struct sigaction sa;
    thermal_set thermal;
    fan_state fan;
    led_state led;
    led_screen screen;
    throttle_state throttle;
    gpu_state gpu;
    cpu_state cpu;
    mem_state mem;
    struct cpu_settings cpu_settings;
    struct mem_settings mem_settings;
    struct status_cache status;
    struct gpu_status_cache gpu_status;
    int gpu_clock_cache = INT_MIN;
    int cpu_status_cache[CPU_CLUSTER_COUNT];
    int cpu_load_cache = INT_MIN;
    int gpu_load_cache = INT_MIN;
    int mem_status_cache[3];
    int throttle_status_cache = -2; /* force the first publish */
    int gpu_request = GPU_STOCK_MHZ;
    bool gpu_override = false;
    int64_t next_gpu_ms = 0;
    int64_t next_cpu_ms = 0;
    int64_t next_mem_ms = 0;
    int mem_retry_left = MEM_RETRY_BUDGET;
    char wakeup_token[HWD_VALUE_WATCH_MAX] = "";
    char trace_token[HWD_VALUE_WATCH_MAX] = "";
    char powertest_token[HWD_VALUE_WATCH_MAX] = "";
    int64_t now;
    int64_t next_fan_ms;
    int64_t next_led_ms = 0;
    int64_t next_status_ms = 0;
    int64_t next_thermal_ms;
    int last_temp = -1;
    bool force_led = true;
    bool screen_on = screen_is_on();
    bool led_want_on = false;
    bool fan_live = fan_ui_visible();
    bool power_live = prop_get_bool(PROP_UI_POWER_LIVE, false);
    struct hwd_ui_heartbeat led_beat = {.token = "",
                                        .seen_ms = 0,
                                        .observed = false};
    struct hwd_ui_heartbeat fan_live_beat = {.token = "",
                                             .seen_ms = 0,
                                             .observed = false};
    struct hwd_value_watch preview_watch = {.value = "", .have = false};
    bool led_expiry_logged = false;
    bool preview_expiry_logged = false;
    /* A preview that lost its lease (its publisher died) is not honoured
     * again until the editor publishes a *different* curve.  Identical
     * rewrites are indistinguishable without the property serial, and are
     * harmless: while a preview is published the editor has already saved the
     * same curve to persist.ashfall.fan.curve.  This stops another fan page
     * from reviving a leftover curve just by refreshing the shared fan_live
     * heartbeat. */
    bool preview_needs_rewrite = false;
    cpu_load cpu_load;

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal; /* no SA_RESTART: futex waits are interrupted */
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    ah_log_set_tag("ashfall-hwd");
    ah_logf(AH_LOG_INFO, "starting (fan=%s led=%s)", FAN_SYSFS_DIR,
            "/sys/class/sn3112[lr]/led");

    fan_state_init(&fan);
    now = ah_now_ms();
    led_state_init(&led, now);
    led_screen_init(&screen, screen_on, now);
    thermal_scan(&thermal);
    memset(&status, 0, sizeof(status));
    memset(&gpu_status, 0, sizeof(gpu_status));
    gpu_state_init(&gpu);
    cpu_state_init(&cpu);
    cpu_load_init(&cpu_load);
    mem_state_init(&mem);
    cpu_settings_default(&cpu_settings);
    mem_settings_default(&mem_settings);
    for (size_t i = 0; i < sizeof(cpu_status_cache) / sizeof(cpu_status_cache[0]); i++)
        cpu_status_cache[i] = INT_MIN;
    for (size_t i = 0; i < sizeof(mem_status_cache) / sizeof(mem_status_cache[0]); i++)
        mem_status_cache[i] = INT_MIN;

    /* Apply the persisted settings once at boot: the daemon is the only
     * writer of the fan/LED nodes, so nothing else restores them. */
    {
        bool changed = false;
        bool present = false;
        char preview[FAN_CURVE_MAX_LEN + 1];

        /* Seed the heartbeat observations; a value left over from a previous
         * app run is only honoured once its publisher writes again.  A
         * non-empty preview without a live editor is stale, so it must be
         * rewritten by the editor before it is followed again. */
        ui_heartbeat_seed(&led_beat, PROP_UI_LED_OVERRIDE_LIVE);
        ui_heartbeat_seed(&fan_live_beat, PROP_UI_FAN_LIVE);
        if (prop_get_str(PROP_UI_FAN_PREVIEW, preview, sizeof(preview)) <= 0)
            preview[0] = '\0';
        /* The re-arm gate compares the whole curve, not a heartbeat token. */
        hwd_value_watch_seed(&preview_watch, preview);
        if (!hwd_ui_heartbeat_fresh(&fan_live_beat, now) && preview[0])
            preview_needs_rewrite = true;

        read_fan_settings(&fan.settings, &changed);
        read_fan_preview(&fan, hwd_ui_heartbeat_fresh(&fan_live_beat, now) &&
                                  !preview_needs_rewrite,
                         &present, &changed);
        present = false;
        read_led_settings(&led.settings,
                          hwd_ui_heartbeat_fresh(&led_beat, now), &present,
                          &changed);
    }
    last_temp = thermal_hottest_tenths(&thermal);
    next_thermal_ms = now + THERMAL_SWEEP_MS;
    fan_control_tick(&fan, last_temp, now, screen_on);
    next_fan_ms = now + FAN_SAMPLE_MS;
    publish_status(&fan, last_temp, &status, &next_status_ms, now,
                   hwd_status_interval_ms(fan_live, power_live));

    /* Performance override: the non-persistent property wins when present (a
     * service restart in the same boot), otherwise the hidden permanent
     * switch decides whether it is applied at this boot. */
    throttle_scan(&throttle);
    {
        bool requested = false;

        if (!perf_override_requested(&requested))
            requested = prop_get_bool(PROP_PERF_PERMANENT, false);
        if (requested || throttle.active)
            throttle_set(&throttle, requested);
        publish_throttle_status(&throttle, &throttle_status_cache);
    }

    /* GPU cap: apply the persisted request once at boot, clamped to the DT
     * table and to 670 MHz while the throttle override is active. */
    gpu_request = prop_get_int_range(PROP_GPU_MAX_MHZ, GPU_STOCK_MHZ,
                                     GPU_MIN_MHZ, GPU_MAX_MHZ);
    gpu_override = throttle.active;
    gpu_apply(&gpu, gpu_request, gpu_override);
    publish_gpu_status(&gpu, &gpu_status);
    next_gpu_ms = now + (gpu.available && gpu.applied ? 0 : GPU_RETRY_MS);

    /* CPU caps/governor and memory (#124, #141): the kernel resets
     * scaling_max_freq/governor at boot and the swapfile is runtime state, so
     * the daemon re-applies the persisted settings here, clamped exactly like
     * a runtime property change. */
    {
        bool unused = false;

        read_cpu_settings(&cpu_settings, &unused);
        read_mem_settings(&mem_settings, &unused);
    }
    cpu_apply(&cpu, cpu_settings.max_mhz[CPU_CLUSTER_SILVER],
              cpu_settings.max_mhz[CPU_CLUSTER_GOLD],
              cpu_settings.max_mhz[CPU_CLUSTER_PRIME], cpu_settings.governor);
    next_cpu_ms = cpu_needs_retry(&cpu) ? now + CPU_RETRY_MS : 0;
    mem_apply_swap(&mem, mem_settings.swap_mb);
    next_mem_ms = mem_needs_retry(&mem_settings, &mem) && mem_retry_left > 0
                      ? now + MEM_RETRY_MS
                      : 0;

    while (g_running) {
        bool changed = false;
        bool fan_due;
        bool led_due;
        bool have_raw;
        bool want_on;
        bool led_reapply;
        int wait_ms;
        int64_t deadline;
        int64_t screen_ms;

        now = ah_now_ms();
        /* Power experiments (#233). */
        {
            char token[HWD_VALUE_WATCH_MAX];

            if (prop_get_str(PROP_DEBUG_POWERTEST, token, sizeof(token)) > 0 &&
                strcmp(token, powertest_token) != 0) {
                snprintf(powertest_token, sizeof(powertest_token), "%s", token);
                powertest_apply(token);
            }
        }
        /* Wakeup-source tracing on/off (#233). */
        {
            char token[HWD_VALUE_WATCH_MAX];

            if (prop_get_str(PROP_DEBUG_TRACE, token, sizeof(token)) > 0 &&
                strcmp(token, trace_token) != 0) {
                snprintf(trace_token, sizeof(trace_token), "%s", token);
                if (!strcmp(token, "on")) {
                    trace_set(true);
                    trace_clear();
                } else {
                    trace_set(false);
                    trace_dump();
                }
            }
        }
        /* One-shot suspend snapshot (#233): a new token in
         * debug.ashfall.wakeups writes the snapshot to the log. */
        {
            char token[HWD_VALUE_WATCH_MAX];

            if (prop_get_str(PROP_DEBUG_WAKEUPS, token, sizeof(token)) > 0 &&
                strcmp(token, wakeup_token) != 0) {
                snprintf(wakeup_token, sizeof(wakeup_token), "%s", token);
                wakeup_dump();
            }
        }
        /* ashfall.ui.* lease (issue #94): sample the heartbeats before the
         * values are read, so a value is only accepted together with a
         * refresh the daemon has actually observed. */
        ui_heartbeat_sample(&led_beat, PROP_UI_LED_OVERRIDE_LIVE, now);
        ui_heartbeat_sample(&fan_live_beat, PROP_UI_FAN_LIVE, now);
        {
            char preview[FAN_CURVE_MAX_LEN + 1];

            if (prop_get_str(PROP_UI_FAN_PREVIEW, preview,
                             sizeof(preview)) <= 0)
                preview[0] = '\0';
            /* Full-string comparison: two curves sharing a long prefix must
             * still count as a new preview (issue #184). */
            if (hwd_value_watch_update(&preview_watch, preview))
                preview_needs_rewrite = false;
        }
        read_fan_settings(&fan.settings, &changed);
        {
            bool live = hwd_ui_heartbeat_fresh(&fan_live_beat, now);
            bool had_preview = fan.preview_valid;
            bool present = false;

            read_fan_preview(&fan, live && !preview_needs_rewrite, &present,
                             &changed);
            if (had_preview && !fan.preview_valid) {
                changed = true;
                if (!live && present) {
                    preview_needs_rewrite = true;
                    if (!preview_expiry_logged) {
                        ah_logf(AH_LOG_WARN,
                                "ui: ashfall.ui.fan_preview_curve expired (no "
                                "ashfall.ui.fan_live for %d ms); using "
                                "persist.ashfall.fan.curve",
                                HWD_UI_HEARTBEAT_TIMEOUT_MS);
                        preview_expiry_logged = true;
                    }
                }
            } else if (fan.preview_valid) {
                preview_expiry_logged = false;
            }
        }
        {
            led_settings before = led.settings;
            bool live = hwd_ui_heartbeat_fresh(&led_beat, now);
            bool had_override = led.settings.override_valid;
            bool present = false;

            read_led_settings(&led.settings, live, &present, &changed);
            if (memcmp(&before, &led.settings, sizeof(before)) != 0)
                force_led = true;
            if (had_override && !led.settings.override_valid) {
                changed = true;
                force_led = true;
                if (!live && present && !led_expiry_logged) {
                    ah_logf(AH_LOG_WARN,
                            "ui: ashfall.ui.led_override expired (no "
                            "ashfall.ui.led_override_live for %d ms); back "
                            "to the configured lighting",
                            HWD_UI_HEARTBEAT_TIMEOUT_MS);
                    led_expiry_logged = true;
                }
            } else if (led.settings.override_valid) {
                led_expiry_logged = false;
            }
        }
        if (changed)
            force_led = true;

        /* Screen transitions are property changes, so prop_wait_any() wakes
         * us; both the fan hold and the LED re-apply below follow from it.
         * The decision is debounced: debug.tracing.screen_state can flicker
         * during the power/keyguard transition, and the HTR3212 controllers
         * are not ready the instant the panel comes back (issue #109). */
        {
            bool woke = false;

            if (led_screen_update(&screen, screen_is_on(), now, &woke)) {
                screen_on = led_screen_on(&screen);
                changed = true;
                force_led = true;
                /* Status is not published while the panel is off (#233), so
                 * force a fresh tick on the transition: the page that opens
                 * right after a wake must not show pre-suspend numbers. */
                next_status_ms = 0;
                if (woke) {
                    /* The lighting block below rewrites both attributes now,
                     * at +0.5 s and at +1.5 s, regardless of led.applied. */
                    ah_logf(AH_LOG_INFO,
                            "screen on: re-applying LED/fan settings");
                } else {
                    ah_logf(AH_LOG_INFO,
                            "screen off: holding fan and LEDs");
                }
            }
        }

        /* --- performance override ---------------------------------------- */
        {
            bool requested = false;

            if (perf_override_requested(&requested) &&
                requested != throttle.active)
                throttle_set(&throttle, requested);
            publish_throttle_status(&throttle, &throttle_status_cache);
        }

        /* --- GPU cap ------------------------------------------------------
         * Re-evaluated when the property or the throttle override changes,
         * and retried while the kgsl devfreq node is not available yet. */
        {
            int request = prop_get_int_range(PROP_GPU_MAX_MHZ, GPU_STOCK_MHZ,
                                             GPU_MIN_MHZ, GPU_MAX_MHZ);
            bool override = throttle.active;
            bool retry = (!gpu.available || !gpu.applied) && now >= next_gpu_ms;

            if (request != gpu_request || override != gpu_override || retry) {
                gpu_apply(&gpu, request, override);
                gpu_request = request;
                gpu_override = override;
                next_gpu_ms = now + ((gpu.available && gpu.applied)
                                         ? 0 : GPU_RETRY_MS);
                publish_gpu_status(&gpu, &gpu_status);
            }

            /* KGSL idle timer from the applied preset (#130), same rule.  It
             * is a control write, so it must not live in the status tick:
             * that one is 30 s apart while the panel is off (#233) and a
             * thermal override has to park the GPU on the stock timer now.
             * gpu_apply_idle_ms() is idempotent and writes only on change. */
            gpu_apply_idle_ms(&gpu, throttle.active ? 0
                                  : prop_get_int_range(PROP_GPU_IDLE_MS, 0, 0,
                                                       GPU_IDLE_MAX_MS));
        }

        /* --- CPU caps / governor and memory ------------------------------
         * Same re-evaluate-on-change rule as the GPU cap, plus a retry while
         * cpufreq or /data is not available yet. */
        {
            bool cpu_changed = false;
            bool mem_changed = false;
            bool retry;

            read_cpu_settings(&cpu_settings, &cpu_changed);
            read_mem_settings(&mem_settings, &mem_changed);
            retry = cpu_needs_retry(&cpu) && now >= next_cpu_ms;
            if (cpu_changed || retry) {
                cpu_apply(&cpu, cpu_settings.max_mhz[CPU_CLUSTER_SILVER],
                          cpu_settings.max_mhz[CPU_CLUSTER_GOLD],
                          cpu_settings.max_mhz[CPU_CLUSTER_PRIME],
                          cpu_settings.governor);
                next_cpu_ms = now + (cpu_needs_retry(&cpu) ? CPU_RETRY_MS : 0);
            }
            retry = mem_needs_retry(&mem_settings, &mem) &&
                    now >= next_mem_ms && mem_retry_left > 0;
            if (mem_changed || retry) {
                if (retry && mem_retry_left > 0)
                    mem_retry_left--;
                mem_apply_swap(&mem, mem_settings.swap_mb);
                next_mem_ms = now + (mem_needs_retry(&mem_settings, &mem) &&
                                             mem_retry_left > 0
                                         ? MEM_RETRY_MS
                                         : 0);
            }
        }

        /* A settings, curve-preview or screen change must re-evaluate the
         * smart target immediately, even when the temperature sits inside the
         * 3 C hysteresis band; reset the anchor (the EMA is temperature-only
         * and stays). */
        if (changed)
            fan.anchor_c = -1;

        fan_due = now >= next_fan_ms;
        if (fan_due) {
            /* Re-pick the watched zones periodically, and immediately while
             * no zone is readable (they can appear after class core starts). */
            if (last_temp < 0 || now >= next_thermal_ms) {
                last_temp = thermal_sweep(&thermal);
                next_thermal_ms =
                    now + (last_temp < 0 ? 0 : THERMAL_SWEEP_MS);
            } else {
                last_temp = thermal_hottest_tenths(&thermal);
            }
            fan_control_tick(&fan, last_temp, now, screen_on);
            next_fan_ms = now + FAN_SAMPLE_MS;
        } else if (changed) {
            /* A settings or screen change is applied immediately, using the
             * last temperature sample. */
            fan_control_tick(&fan, last_temp, now, screen_on);
        } else if (fan.kick_until_ms > 0 && now >= fan.kick_until_ms) {
            /* The spin-up kick is over: drop to the requested speed. */
            fan_apply_pct(&fan, fan.pending_pct, now);
        }

        /* --- lighting ----------------------------------------------------
         * LEDs are always off while the screen is off and re-applied (both
         * attributes rewritten) on screen-on; persist.ashfall.led.screen_off
         * was removed in phase 5 v2.  No animation runs while off; a failed
         * off/on write is retried every LED_RETRY_MS. */
        have_raw = (led.settings.mode != LED_MODE_OFF ||
                    led.settings.override_valid) &&
                   led.backend != LED_BACKEND_NONE;
        want_on = screen_on && have_raw;
        led_due = want_on && led_is_animated(&led) && now >= next_led_ms;
        led_reapply = led_screen_reapply_due(&screen, now);
        if (led_reapply) {
            /* The controllers may have dropped the first transfers after
             * wake-up: forget what was written and rewrite both attributes
             * instead of trusting applied/shown. */
            led_invalidate(&led);
        }
        if (force_led || !led.applied || led_due || want_on != led_want_on ||
            led_reapply) {
            rgb_t frame[2];

            led_frame(&led, now, frame);
            led_apply(&led, frame, want_on);
            led_want_on = want_on;
            force_led = false;
            if (!led.applied)
                next_led_ms = now + LED_RETRY_MS;
            else if (want_on && led_is_animated(&led))
                next_led_ms = now + LED_FRAME_MS;
            else
                next_led_ms = 0;
        }

        /* --- status ------------------------------------------------------
         * One tick for all telemetry (docs/phase6.md §9.3).  The period is
         * 500 ms while a live page is visible (ashfall.ui.fan_live from the
         * Fan page, ashfall.ui.power_live from the Power page) and 2 s
         * otherwise; a change of either property re-schedules immediately. */
        {
            bool fan = fan_ui_visible();
            bool power = prop_get_bool(PROP_UI_POWER_LIVE, false);

            if (fan != fan_live || power != power_live) {
                fan_live = fan;
                power_live = power;
                next_status_ms = 0; /* switch 2 s <-> 500 ms immediately */
            }
        }
        if (now >= next_status_ms) {
            if (screen_on) {
                fan.rpm = fan_read_rpm();
                publish_status(&fan, last_temp, &status, &next_status_ms, now,
                               hwd_status_interval_ms(fan_live, power_live));
                publish_int(STATUS_PERF_GPU_MHZ, gpu_clock_mhz(),
                            &gpu_clock_cache);
                publish_int(STATUS_PERF_CPU_LOAD, cpu_load_pct(&cpu_load),
                            &cpu_load_cache);
                publish_int(STATUS_PERF_GPU_LOAD, gpu_load_pct(), &gpu_load_cache);
                publish_cpu_status(&cpu, cpu_status_cache);
                publish_mem_status(&mem, mem_status_cache);
            } else {
                /* Panel off (#233): every ashfall.status.* write bumps the
                 * global property serial and wakes property listeners in
                 * other processes, and nothing can read them until the panel
                 * is back.  Skip the whole tick; the screen-on transition
                 * sets next_status_ms = 0 for an immediate fresh publish. */
                next_status_ms = now + STATUS_SCREEN_OFF_MS;
            }
        }

        /* --- sleep until something happens ------------------------------- */
        deadline = next_fan_ms;
        if (next_status_ms > 0 && next_status_ms < deadline)
            deadline = next_status_ms;
        if (next_cpu_ms > 0 && next_cpu_ms < deadline)
            deadline = next_cpu_ms;
        if (next_mem_ms > 0 && next_mem_ms < deadline)
            deadline = next_mem_ms;
        if (next_led_ms > 0 && next_led_ms < deadline)
            deadline = next_led_ms;
        screen_ms = led_screen_next_ms(&screen);
        if (screen_ms > 0 && screen_ms < deadline)
            deadline = screen_ms;
        if (fan.kick_until_ms > 0 && fan.kick_until_ms < deadline)
            deadline = fan.kick_until_ms;
        wait_ms = (int)(deadline - now);
        if (wait_ms > (screen_on ? IDLE_WAIT_MS : IDLE_WAIT_MS_SCREEN_OFF))
            wait_ms = screen_on ? IDLE_WAIT_MS : IDLE_WAIT_MS_SCREEN_OFF;
        if (wait_ms < 0)
            wait_ms = 0;
        prop_wait_any(wait_ms);
    }

    ah_logf(AH_LOG_INFO, "stopping");
    led_disable(&led);
    /* Release the performance override: the kernel trips must never stay
     * raised without the daemon watching over them. */
    if (throttle.active)
        throttle_set(&throttle, false);
    /* Leave the fan at its current duty: the safety override only lives as
     * long as this daemon, so stopping it must not also stop cooling.  init
     * restarts the service and re-applies persist.ashfall.fan.*. */
    return 0;
}
