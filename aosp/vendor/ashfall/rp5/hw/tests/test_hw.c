/*
 * Host-side tests for the ashfall-hwd logic: fan curve, safety override,
 * spin-up kick, LED effects and thermal zone selection.  No device, no root:
 * sysfs is faked under $ASHFALL_SYSFS_ROOT.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "cpu.h"
#include "fan.h"
#include "gpu.h"
#include "led.h"
#include "mem.h"
#include "plat.h"
#include "thermal.h"
#include "throttle.h"

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static char g_root[PATH_MAX];
static int g_failures;

/* Phase 6: the daemon opens the resolved device paths, never the
 * /sys/class/kgsl symlinks (issue #115).  Mirror that layout in the fake tree. */
#define FAKE_KGSL_DEV "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0"
#define FAKE_KGSL_DEVFREQ FAKE_KGSL_DEV "/devfreq/3d00000.qcom,kgsl-3d0"
#define FAKE_KGSL_LEGACY "/sys/devices/soc/3d00000.qcom,kgsl-3d0"
#define FAKE_KGSL_DEVFREQ_LEGACY FAKE_KGSL_LEGACY "/devfreq/3d00000.qcom,kgsl-3d0"
#define FAKE_KGSL_LEGACY_KGSL FAKE_KGSL_LEGACY "/kgsl/kgsl-3d0"

#define CHECK(cond)                                                            \
    do {                                                                       \
        if (!(cond)) {                                                         \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);    \
            g_failures++;                                                      \
        }                                                                      \
    } while (0)

#define CHECK_EQ(a, b)                                                         \
    do {                                                                       \
        long _a = (long)(a), _b = (long)(b);                                    \
        if (_a != _b) {                                                        \
            fprintf(stderr, "FAIL %s:%d: %s == %ld, want %ld\n", __FILE__,     \
                    __LINE__, #a, _a, _b);                                     \
            g_failures++;                                                      \
        }                                                                      \
    } while (0)

static void mkdir_p(const char *relative)
{
    char tmp[PATH_MAX];
    char *p;

    snprintf(tmp, sizeof(tmp), "%s%s", g_root, relative);
    for (p = tmp + 1; *p; p++) {
        if (*p != '/')
            continue;
        *p = '\0';
        mkdir(tmp, 0777);
        *p = '/';
    }
    mkdir(tmp, 0777);
}

static void write_file(const char *relative, const char *content)
{
    char path[PATH_MAX];
    int fd;

    snprintf(path, sizeof(path), "%s%s", g_root, relative);
    fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) {
        fprintf(stderr, "cannot create %s: %s\n", path, strerror(errno));
        exit(2);
    }
    if (write(fd, content, strlen(content)) < 0) {
        fprintf(stderr, "cannot write %s: %s\n", path, strerror(errno));
        exit(2);
    }
    close(fd);
}

static int read_file(const char *relative, char *buf, size_t len)
{
    char path[PATH_MAX];
    int fd;
    ssize_t n;

    snprintf(path, sizeof(path), "%s%s", g_root, relative);
    fd = open(path, O_RDONLY);
    if (fd < 0)
        return -1;
    n = read(fd, buf, len - 1);
    close(fd);
    if (n < 0)
        return -1;
    buf[n] = '\0';
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == ' '))
        buf[--n] = '\0';
    return 0;
}

static int read_int(const char *relative)
{
    char buf[64];

    if (read_file(relative, buf, sizeof(buf)) < 0)
        return INT_MIN;
    return atoi(buf);
}

static long long read_ll(const char *relative)
{
    char buf[64];

    if (read_file(relative, buf, sizeof(buf)) < 0)
        return LLONG_MIN;
    return strtoll(buf, NULL, 10);
}

static long long file_size(const char *relative)
{
    char path[PATH_MAX];
    struct stat st;

    plat_resolve(relative, path, sizeof(path));
    if (stat(path, &st) != 0)
        return -1;
    return (long long)st.st_size;
}

static bool read_at(const char *relative, off_t offset, void *buf, size_t len)
{
    char path[PATH_MAX];
    int fd;
    ssize_t n;

    plat_resolve(relative, path, sizeof(path));
    fd = open(path, O_RDONLY);
    if (fd < 0)
        return false;
    n = pread(fd, buf, len, offset);
    close(fd);
    return n == (ssize_t)len;
}

static bool file_is(const char *relative, const char *expected)
{
    char buf[64];

    return read_file(relative, buf, sizeof(buf)) == 0 && !strcmp(buf, expected);
}

#define FAKE_CPUFREQ "/sys/devices/system/cpu/cpufreq"

static void make_cpu_policy(const char *name, const char *cpus,
                            const char *freqs, const char *cur_khz)
{
    char dir[128];
    char file[160];
    char top[32];
    size_t n = strcspn(freqs, " \n");

    snprintf(dir, sizeof(dir), FAKE_CPUFREQ "/%s", name);
    mkdir_p(dir);
    snprintf(file, sizeof(file), "%s/affected_cpus", dir);
    write_file(file, cpus);
    snprintf(file, sizeof(file), "%s/scaling_available_frequencies", dir);
    write_file(file, freqs);
    snprintf(file, sizeof(file), "%s/scaling_available_governors", dir);
    write_file(file, "walt conservative powersave performance schedutil\n");
    snprintf(file, sizeof(file), "%s/scaling_governor", dir);
    write_file(file, "walt\n");
    snprintf(file, sizeof(file), "%s/scaling_cur_freq", dir);
    write_file(file, cur_khz);
    snprintf(top, sizeof(top), "%.*s\n", (int)n, freqs);
    snprintf(file, sizeof(file), "%s/scaling_max_freq", dir);
    write_file(file, top);
}

static int make_sysfs(void)
{
    char template[] = "/tmp/ashfall-hw-test.XXXXXX";

    if (!mkdtemp(template))
        return -1;
    snprintf(g_root, sizeof(g_root), "%s", template);
    setenv("ASHFALL_SYSFS_ROOT", g_root, 1);

    mkdir_p("/sys/class/gpio5_pwm2");
    write_file("/sys/class/gpio5_pwm2/state", "0\n");
    write_file("/sys/class/gpio5_pwm2/duty", "0\n");
    write_file("/sys/class/gpio5_pwm2/speed", "2400\n");

    mkdir_p("/sys/class/sn3112l/led");
    mkdir_p("/sys/class/sn3112r/led");
    write_file("/sys/class/sn3112l/led/enable", "0\n");
    write_file("/sys/class/sn3112l/led/brightness", "0-0:0:0\n");
    write_file("/sys/class/sn3112r/led/enable", "0\n");
    write_file("/sys/class/sn3112r/led/brightness", "0-0:0:0\n");

    /* GPU devfreq cap (phase 6): stock DT table, 670 MHz top. */
    mkdir_p(FAKE_KGSL_DEVFREQ);
    mkdir_p(FAKE_KGSL_DEV "/kgsl/kgsl-3d0");
    write_file(FAKE_KGSL_DEVFREQ "/max_freq", "670000000\n");
    write_file(FAKE_KGSL_DEVFREQ "/min_freq", "305000000\n");
    write_file(FAKE_KGSL_DEV "/kgsl/kgsl-3d0/gpu_available_frequencies",
               "670000000 587000000 525000000 490000000 441600000 400000000 "
               "305000000\n");
    write_file(FAKE_KGSL_DEV "/kgsl/kgsl-3d0/gpuclk", "305000000\n");

    /* CPU frequency policies (phase 6, #124), kona layout and steps. */
    make_cpu_policy("policy0", "0 1 2 3\n",
                    "1804800 1478400 1171200 691200 300000\n", "1478400\n");
    make_cpu_policy("policy4", "4 5 6\n",
                    "2419200 1958400 1478400 902400 710400 300000\n",
                    "1958400\n");
    make_cpu_policy("policy7", "7\n",
                    "2841600 2419200 1958400 1516800 1171200 940800 844800\n",
                    "1958400\n");

    /* Memory (phase 6, #141): 8 GB unit, zram module loaded, 0 MB applied. */
    mkdir_p("/proc");
    write_file("/proc/meminfo",
               "MemTotal:        8192000 kB\nMemFree:         4096000 kB\n"
               "SwapTotal:             0 kB\nSwapFree:              0 kB\n");
    mkdir_p("/sys/block/zram0");
    write_file("/sys/block/zram0/disksize", "0\n");
    write_file("/sys/block/zram0/reset", "0\n");
    write_file("/sys/block/zram0/comp_algorithm", "lzo\n");
    write_file("/sys/block/zram0/comp_available", "lzo lzo-rle lz4\n");
    write_file("/sys/block/zram0/mm_stat", "0 0 0 0 0 0 0\n");
    mkdir_p("/dev/block");
    write_file("/dev/block/zram0", "");
    mkdir_p("/data");

    mkdir_p("/sys/class/thermal/thermal_zone0");
    mkdir_p("/sys/class/thermal/thermal_zone1");
    mkdir_p("/sys/class/thermal/thermal_zone2");
    write_file("/sys/class/thermal/thermal_zone0/type", "pm8150_tz\n");
    write_file("/sys/class/thermal/thermal_zone0/temp", "90000\n");
    write_file("/sys/class/thermal/thermal_zone1/type", "cpu0-usr\n");
    write_file("/sys/class/thermal/thermal_zone1/temp", "55000\n");
    write_file("/sys/class/thermal/thermal_zone2/type", "gpuss-0-usr\n");
    write_file("/sys/class/thermal/thermal_zone2/temp", "62000\n");
    /* GPU clock trip (95 C) and the higher protective trips. */
    write_file("/sys/class/thermal/thermal_zone2/trip_point_0_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_0_temp", "95000\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_1_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_1_temp", "115000\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_2_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_2_temp", "125000\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_3_type", "critical\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_3_temp", "155000\n");
    return 0;
}

static void remove_rel(const char *relative)
{
    char cmd[PATH_MAX + 32];

    snprintf(cmd, sizeof(cmd), "rm -rf '%s%s'", g_root, relative);
    if (system(cmd) != 0)
        fprintf(stderr, "warning: could not remove %s\n", relative);
}

/* --- fan ----------------------------------------------------------------- */

static void test_curve(void)
{
    fan_curve curve;
    char buf[FAN_CURVE_MAX_LEN + 1];

    CHECK(fan_parse_curve("45:0,55:30,65:55,75:80,82:100", &curve));
    CHECK_EQ(curve.count, 5);
    CHECK_EQ(fan_curve_pct(&curve, 40.0), 0);
    CHECK_EQ(fan_curve_pct(&curve, 45.0), 0);
    CHECK_EQ(fan_curve_pct(&curve, 50.0), 15);
    CHECK_EQ(fan_curve_pct(&curve, 65.0), 55);
    CHECK_EQ(fan_curve_pct(&curve, 80.0), 94);
    CHECK_EQ(fan_curve_pct(&curve, 82.0), 100);
    CHECK_EQ(fan_curve_pct(&curve, 95.0), 100);

    /* Invalid values must be rejected (the daemon then keeps the default). */
    CHECK(!fan_parse_curve("50:0", &curve));
    CHECK(!fan_parse_curve("60:1,50:2", &curve));
    CHECK(!fan_parse_curve("60:1,60:2", &curve));
    CHECK(!fan_parse_curve("abc:1,70:2", &curve));
    CHECK(!fan_parse_curve("60:1,70:101", &curve));
    CHECK(!fan_parse_curve("", &curve));
    CHECK(!fan_parse_curve("10:0,20:5,30:10,40:20,50:30,60:40,70:50,80:60,90:70",
                           &curve)); /* 9 points > FAN_MAX_POINTS */

    CHECK(fan_parse_curve("45:0,55:30,65:55,75:80,82:100", &curve));
    fan_curve_format(&curve, buf, sizeof(buf));
    CHECK(!strcmp(buf, "45:0,55:30,65:55,75:80,82:100"));
}

static void test_duty(void)
{
    CHECK_EQ(fan_pct_to_duty(0), 0);
    CHECK_EQ(fan_pct_to_duty(1), FAN_DUTY_MIN_NS + (FAN_DUTY_MAX_NS - FAN_DUTY_MIN_NS) / 100);
    CHECK_EQ(fan_pct_to_duty(50), FAN_DUTY_MIN_NS + (FAN_DUTY_MAX_NS - FAN_DUTY_MIN_NS) / 2);
    CHECK_EQ(fan_pct_to_duty(100), FAN_DUTY_MAX_NS);
    CHECK_EQ(fan_pct_to_duty(200), FAN_DUTY_MAX_NS);
    CHECK_EQ(fan_duty_to_pct(0), 0);
    CHECK_EQ(fan_duty_to_pct(FAN_DUTY_MAX_NS), 100);
    CHECK(fan_duty_to_pct(fan_pct_to_duty(50)) >= 49);
    CHECK(fan_duty_to_pct(fan_pct_to_duty(50)) <= 51);
}

static void test_apply_and_kick(void)
{
    fan_state fan;
    int64_t now = 100000;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 10;

    /* 0 -> nonzero with a low target: spin-up kick at kick_pct (30 %), not
     * 100 % (docs/phase5.md §6.1). */
    CHECK_EQ(fan_control_tick(&fan, 400, now, true), FAN_KICK_DEFAULT_PCT);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(FAN_KICK_DEFAULT_PCT));
    CHECK(fan_kick_pending(&fan, now + 100));

    /* A settings tick inside the kick window must report the level that is
     * actually on the hardware, not the requested target or 100 %. */
    fan.settings.manual_pct = 50;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 100, true),
             FAN_KICK_DEFAULT_PCT);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(FAN_KICK_DEFAULT_PCT));
    fan.settings.manual_pct = 10;

    /* After the kick the target speed is applied. */
    CHECK_EQ(fan_control_tick(&fan, 400, now + FAN_KICK_MS + 1, true), 10);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(10));

    /* A target at or above the kick level is applied directly. */
    CHECK_EQ(fan_control_tick(&fan, 400, now + 10000, true), 10);
    fan.settings.manual_pct = 50;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 20000, true), 50);
    CHECK(!fan_kick_pending(&fan, now + 20000));

    /* kick=0: no spin-up kick at all. */
    fan.settings.mode = FAN_MODE_OFF;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 30000, true), 0); /* off */
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 10;
    fan.settings.kick = false;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 40000, true), 10);
    CHECK(!fan_kick_pending(&fan, now + 40000));

    /* Targets above the kick cap (61..100) must run at their real duty. */
    fan.settings.mode = FAN_MODE_OFF;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 45000, true), 0);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 100;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 46000, true), 100);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(100));

    /* off disables the fan. */
    fan.settings.mode = FAN_MODE_OFF;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 50000, true), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), 0);
}

static void test_screen_off(void)
{
    fan_state fan;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_QUIET;
    fan.settings.kick = true;
    fan.settings.kick_pct = 30;
    fan.settings.off_when_screen_off = true;
    fan.settings.min_pct = 0; /* limits off: raw mode resolution (see test_limits) */

    /* Screen off: the fan stops. */
    CHECK_EQ(fan_control_tick(&fan, 400, 1000, false), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);

    /* Screen on: quiet resumes through the kick. */
    CHECK_EQ(fan_control_tick(&fan, 400, 2000, true), 30);
    CHECK_EQ(fan_control_tick(&fan, 400, 2000 + FAN_KICK_MS + 1, true),
             FAN_PRESET_QUIET_PCT);

    /* screen_off=run keeps the fan running with the screen off. */
    fan.settings.off_when_screen_off = false;
    CHECK_EQ(fan_control_tick(&fan, 400, 9000, false),
             FAN_PRESET_QUIET_PCT);

    /* The fixed safety override wins over the screen-off hold. */
    fan.settings.off_when_screen_off = true;
    CHECK_EQ(fan_control_tick(&fan, 900, 12000, false), 100);
    CHECK(fan.safety);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(100));
    CHECK_EQ(fan_control_tick(&fan, 810, 13000, false), 100);
    CHECK_EQ(fan_control_tick(&fan, 800, 14000, false), 0);
    CHECK(!fan.safety);
}

static void test_presets(void)
{
    fan_state fan;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_QUIET;
    fan.settings.min_pct = 0; /* Quiet (2 %) runs: see test_limits for the cut-off */
    CHECK_EQ(fan_control_tick(&fan, 400, 1000, true),
             FAN_KICK_DEFAULT_PCT); /* kick, never 100 % */
    CHECK_EQ(fan_control_tick(&fan, 400, 1000 + FAN_KICK_MS + 1, true),
             FAN_PRESET_QUIET_PCT);

    fan.settings.mode = FAN_MODE_MAX;
    CHECK_EQ(fan_control_tick(&fan, 400, 5000, true), FAN_PRESET_MAX_PCT);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(100));
}

static void test_safety(void)
{
    fan_state fan;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_OFF;

    /* 90.0 C in off mode -> full speed, safety latched (fixed, no setting). */
    CHECK_EQ(fan_control_tick(&fan, 900, 1000, true), 100);
    CHECK(fan.safety);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(100));

    /* Just below the threshold: still latched (release is at 80 C). */
    CHECK_EQ(fan_control_tick(&fan, 810, 5000, true), 100);
    CHECK(fan.safety);

    /* 80.0 C -> release, back to off. */
    CHECK_EQ(fan_control_tick(&fan, 800, 9000, true), 0);
    CHECK(!fan.safety);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);
}

/* Output limits (docs/phase5.md §7.1): persist.ashfall.fan.{min_pct,max_pct}
 * are applied to the percentage the mode/curve/screen-off hold resolved, right
 * before the duty is written.  The 90 C rule and the no-sensor fail-safe are
 * exempt. */
static void test_limits(void)
{
    fan_state fan;
    int min;
    int max;

    /* Defaults and the invalid-pair fallback (min > max -> both defaults). */
    fan_state_init(&fan);
    CHECK_EQ(fan.settings.min_pct, FAN_MIN_PCT_DEFAULT);
    CHECK_EQ(fan.settings.max_pct, FAN_MAX_PCT_DEFAULT);
    CHECK_EQ(FAN_MIN_PCT_DEFAULT, 5);
    CHECK_EQ(FAN_MAX_PCT_DEFAULT, 100);
    min = 60;
    max = 40;
    CHECK(!fan_limits_normalise(&min, &max));
    CHECK_EQ(min, FAN_MIN_PCT_DEFAULT);
    CHECK_EQ(max, FAN_MAX_PCT_DEFAULT);

    /* In-range pairs are kept, including the two "disabled" ends. */
    min = 0;
    max = 0;
    CHECK(fan_limits_normalise(&min, &max)); /* max 0 caps everything at 0 */
    CHECK_EQ(min, 0);
    CHECK_EQ(max, 0);
    min = 0;
    max = 100;
    CHECK(fan_limits_normalise(&min, &max));
    CHECK_EQ(min, 0);
    CHECK_EQ(max, 100);

    /* Out-of-range values are not a valid pair either. */
    min = 40;
    max = 101;
    CHECK(!fan_limits_normalise(&min, &max));
    CHECK_EQ(min, FAN_MIN_PCT_DEFAULT);
    CHECK_EQ(max, FAN_MAX_PCT_DEFAULT);

    /* manual 2 % with the default min 5: the fan stops completely - state=0
     * and duty=0, never a partial duty - and no kick runs for that target. */
    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 50;
    CHECK_EQ(fan_control_tick(&fan, 400, 1000, true), 50); /* above the kick */
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(50));

    fan.settings.manual_pct = 2;
    CHECK_EQ(fan_control_tick(&fan, 400, 4000, true), 0);
    CHECK_EQ(fan.out_pct, 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), 0);
    CHECK(!fan_kick_pending(&fan, 4000));
    CHECK_EQ(fan.kick_until_ms, 0);

    /* Exactly min (5) is allowed: it runs (via the kick, then at 5 %). */
    fan.settings.manual_pct = 5;
    CHECK_EQ(fan_control_tick(&fan, 400, 6000, true), FAN_KICK_DEFAULT_PCT);
    CHECK_EQ(fan_control_tick(&fan, 400, 6000 + FAN_KICK_MS + 1, true), 5);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(5));

    /* max_pct 40 caps manual 100, and the status output is the capped value. */
    fan.settings.max_pct = 40;
    fan.settings.manual_pct = 100;
    CHECK_EQ(fan_control_tick(&fan, 400, 9000, true), 40);
    CHECK_EQ(fan.out_pct, 40);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(40));

    /* Quiet (2 %) stops the fan under the shipped defaults and runs again
     * with min_pct = 0. */
    fan.settings.mode = FAN_MODE_QUIET;
    fan.settings.max_pct = FAN_MAX_PCT_DEFAULT;
    fan.settings.min_pct = FAN_MIN_PCT_DEFAULT;
    CHECK_EQ(fan_control_tick(&fan, 400, 11000, true), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);
    fan.settings.min_pct = 0;
    CHECK_EQ(fan_control_tick(&fan, 400, 13000, true), FAN_KICK_DEFAULT_PCT);
    CHECK_EQ(fan_control_tick(&fan, 400, 13000 + FAN_KICK_MS + 1, true),
             FAN_PRESET_QUIET_PCT);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);

    /* max_pct 100 disables the cap. */
    fan.settings.mode = FAN_MODE_MAX;
    fan.settings.max_pct = 100;
    CHECK_EQ(fan_control_tick(&fan, 400, 16000, true), 100);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(100));

    /* The 90 C last-resort rule is never capped: max 40 still gives 100 %. */
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 2;
    fan.settings.max_pct = 40;
    CHECK_EQ(fan_control_tick(&fan, 900, 18000, true), 100);
    CHECK(fan.safety);
    CHECK_EQ(fan.out_pct, 100);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(100));

    /* The no-sensor fail-safe (balanced 46 %) is exempt as well. */
    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 2;
    CHECK_EQ(fan_control_tick(&fan, -1, 22000, true),
             FAN_PRESET_BALANCED_PCT);
    CHECK(fan.sensor_fault);
    CHECK(fan.safety);
    CHECK_EQ(fan.out_pct, FAN_PRESET_BALANCED_PCT);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(FAN_PRESET_BALANCED_PCT));

    /* Back to a readable sensor: the limits apply again (manual 2 < min 5). */
    CHECK_EQ(fan_control_tick(&fan, 400, 24000, true), 0);
    CHECK(!fan.sensor_fault);
    CHECK(!fan.safety);
    CHECK_EQ(fan.out_pct, 0);

    /* The spin-up kick is capped by max_pct: with max 10 and the default kick
     * level of 30 the fan starts at 10, not 30 (and no kick window opens,
     * because the clamped level does not raise the target). */
    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 10;
    fan.settings.max_pct = 10;
    fan.settings.kick = true;
    fan.settings.kick_pct = FAN_KICK_DEFAULT_PCT;
    CHECK_EQ(fan_control_tick(&fan, 400, 26000, true), 10);
    CHECK_EQ(fan.out_pct, 10);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(10));
    CHECK(!fan_kick_pending(&fan, 26000));
    CHECK_EQ(fan.kick_until_ms, 0);

    /* max 100 leaves the kick exactly as before: 30 % for a 5 % target. */
    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 5;
    fan.settings.kick = true;
    fan.settings.kick_pct = FAN_KICK_DEFAULT_PCT;
    CHECK_EQ(fan_control_tick(&fan, 400, 28000, true), FAN_KICK_DEFAULT_PCT);
    CHECK(fan_kick_pending(&fan, 28000));
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(FAN_KICK_DEFAULT_PCT));
    CHECK_EQ(fan_control_tick(&fan, 400, 28000 + FAN_KICK_MS + 1, true), 5);

    /* The safety path is exempt from the kick clamp as well: 90 C with
     * max 10 still goes to 100 % at once. */
    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 2;
    fan.settings.max_pct = 10;
    fan.settings.kick = true;
    fan.settings.kick_pct = FAN_KICK_DEFAULT_PCT;
    CHECK_EQ(fan_control_tick(&fan, 900, 32000, true), 100);
    CHECK(fan.safety);
    CHECK_EQ(fan.out_pct, 100);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(100));
}

static void test_smart(void)
{
    fan_state fan;
    int target;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_SMART;
    fan_parse_curve("45:0,55:30,65:55,75:80,82:100", &fan.settings.curve);

    fan_control_tick(&fan, 650, 1000, true);
    target = fan.target_pct;
    CHECK_EQ(target, 55);

    /* A single 2 C dip must not lower the target (hysteresis is 3 C and the
     * EMA needs a few samples to move anyway). */
    fan_control_tick(&fan, 630, 3000, true);
    CHECK_EQ(fan.target_pct, target);

    /* A sustained drop does re-evaluate once the smoothed temperature is a
     * full 3 C below the anchor: the curve gives ~47 % at the EMA (~61.8 C)
     * and the new anchor then holds for another 3 C. */
    for (int i = 0; i < 10; i++)
        fan_control_tick(&fan, 600, 5000 + i * 2000, true);
    CHECK(fan.target_pct < target);
    CHECK(fan.target_pct >= 45);
    CHECK(fan.target_pct <= 48);

    /* Rising temperature is followed without waiting for hysteresis (the EMA
     * needs a few samples, then the curve reaches 100 %). */
    for (int i = 0; i < 10; i++)
        fan_control_tick(&fan, 820, 40000 + i * 2000, true);
    CHECK(fan.target_pct >= 99);
}

/* ashfall.ui.fan_preview_curve replaces persist.ashfall.fan.curve while the
 * editor is open (only in smart mode). */
static void test_smart_preview(void)
{
    fan_state fan;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_SMART;
    fan_parse_curve("45:0,55:30,65:55,75:80,82:100", &fan.settings.curve);
    fan_parse_curve("45:0,55:0,65:0,75:0,82:0", &fan.preview);
    fan.preview_valid = true;

    CHECK_EQ(fan_control_tick(&fan, 650, 1000, true), 0);
    CHECK_EQ(fan.target_pct, 0);

    /* Invalid/empty preview: back to the persisted curve. */
    fan.preview_valid = false;
    fan.have_ema = false;
    fan.anchor_c = -1;
    CHECK_EQ(fan_control_tick(&fan, 650, 3000, true), 55);
    CHECK_EQ(fan.target_pct, 55);
}



static void test_led_colour(void)
{
    led_settings set;
    rgb_t colour;

    led_settings_default(&set);
    CHECK(led_parse_colour("FF2020", &colour));
    CHECK_EQ(colour.r, 0xFF);
    CHECK_EQ(colour.g, 0x20);
    CHECK_EQ(colour.b, 0x20);
    CHECK(led_parse_colour("#00ff80", &colour));
    CHECK_EQ(colour.g, 0xFF);
    CHECK(!led_parse_colour("ff20", &colour));
    CHECK(!led_parse_colour("gggggg", &colour));

    CHECK(led_parse_mode("duo", &set.mode));
    CHECK_EQ(set.mode, LED_MODE_DUO);
    CHECK(!led_parse_mode("disco", &set.mode));
    CHECK(!strcmp(led_mode_name(LED_MODE_BREATHING), "breathing"));
}

static void test_led_effects(void)
{
    led_state led;
    rgb_t frame[2];

    led_state_init(&led, 0);
    CHECK_EQ(led.backend, LED_BACKEND_SN3112);

    /* static at 60 %: FF20 20 -> 99 13 13 */
    led.settings.mode = LED_MODE_STATIC;
    led.settings.brightness = 60;
    led_frame(&led, 0, frame);
    CHECK_EQ(frame[0].r, 153);
    CHECK_EQ(frame[0].g, 19);
    CHECK_EQ(frame[1].r, 153);
    CHECK(!memcmp(&frame[0], &frame[1], sizeof(frame[0])));

    /* brightness 0 -> black, effect still "on" */
    led.settings.brightness = 0;
    led_frame(&led, 0, frame);
    CHECK_EQ(frame[0].r, 0);
    CHECK_EQ(frame[0].g, 0);
    CHECK_EQ(frame[0].b, 0);
    led.settings.brightness = 100;

    /* sync off: right uses its own colour */
    led.settings.sync = false;
    led.settings.left = (rgb_t){255, 0, 0};
    led.settings.right = (rgb_t){0, 0, 255};
    led_frame(&led, 0, frame);
    CHECK_EQ(frame[0].r, 255);
    CHECK_EQ(frame[0].b, 0);
    CHECK_EQ(frame[1].r, 0);
    CHECK_EQ(frame[1].b, 255);

    /* duo: both sticks show the interpolated colour; at phase 0 it is the
     * left colour, half a period later the right colour. */
    led.settings.mode = LED_MODE_DUO;
    led.settings.speed = 5; /* period 5000 ms / 5 = 1000 ms */
    led.settings.left = (rgb_t){255, 0, 0};
    led.settings.right = (rgb_t){0, 0, 255};
    led_frame(&led, 0, frame);
    CHECK_EQ(frame[0].r, 255);
    CHECK_EQ(frame[0].b, 0);
    CHECK(!memcmp(&frame[0], &frame[1], sizeof(frame[0])));
    led_frame(&led, 250, frame);
    CHECK(frame[0].r >= 127 && frame[0].r <= 128);
    CHECK(frame[0].b >= 127 && frame[0].b <= 128);
    led_frame(&led, 500, frame);
    CHECK_EQ(frame[0].r, 0); /* half a period later: the right colour */
    CHECK_EQ(frame[0].b, 255);
    led_frame(&led, 1000, frame);
    CHECK_EQ(frame[0].r, 255); /* a full period wraps back */

    /* breathing stays between 2 % (gamma curve floor) and 100 % of the base
     * colour, and is smooth (gamma-corrected) rather than a bare cosine. */
    led.settings.mode = LED_MODE_BREATHING;
    led.settings.left = (rgb_t){200, 200, 200};
    led.settings.right = (rgb_t){200, 200, 200};
    for (int t = 0; t <= 800; t += 5) {
        led_frame(&led, t, frame);
        CHECK(frame[0].r >= 3);
        CHECK(frame[0].r <= 200);
    }

    /* rainbow at t=0 is pure red, a third of the (3x slower) period later
     * pure green: speed 5 -> 18 000/5 = 3 600 ms per turn. */
    led.settings.mode = LED_MODE_RAINBOW;
    led.settings.speed = 5;
    led.settings.sync = true;
    led_frame(&led, 0, frame);
    CHECK_EQ(frame[0].r, 255);
    CHECK_EQ(frame[0].g, 0);
    led_frame(&led, 400, frame);
    CHECK(frame[0].g < 255); /* no longer a third of the turn at 400 ms */
    led_frame(&led, 1200, frame);
    CHECK_EQ(frame[0].r, 0);
    CHECK_EQ(frame[0].g, 255);
    CHECK(!memcmp(&frame[0], &frame[1], sizeof(frame[0])));

    /* ashfall.ui.led_override: exact final colours, no mode/brightness
     * processing, and no animation timer. */
    led.settings.mode = LED_MODE_OFF;
    led.settings.brightness = 0;
    led.settings.override_valid = true;
    led.settings.override[0] = (rgb_t){10, 20, 30};
    led.settings.override[1] = (rgb_t){40, 50, 60};
    CHECK(!led_is_animated(&led));
    led_frame(&led, 0, frame);
    CHECK_EQ(frame[0].r, 10);
    CHECK_EQ(frame[0].g, 20);
    CHECK_EQ(frame[0].b, 30);
    CHECK_EQ(frame[1].r, 40);
    CHECK_EQ(frame[1].g, 50);
    CHECK_EQ(frame[1].b, 60);
    led.settings.override_valid = false;
    CHECK(!led_is_animated(&led));
}

static void test_led_apply(void)
{
    led_state led;
    rgb_t frame[2] = {{10, 20, 30}, {40, 50, 60}};
    char buf[64];

    led_state_init(&led, 0);
    led_apply(&led, frame, true);
    CHECK_EQ(read_int("/sys/class/sn3112l/led/enable"), 1);
    CHECK_EQ(read_int("/sys/class/sn3112r/led/enable"), 1);
    CHECK(!read_file("/sys/class/sn3112l/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "1-10:20:30"));
    CHECK(!read_file("/sys/class/sn3112r/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "1-40:50:60"));

    led_disable(&led);
    CHECK_EQ(read_int("/sys/class/sn3112l/led/enable"), 0);
    CHECK_EQ(read_int("/sys/class/sn3112r/led/enable"), 0);

    /* unsupported mode must not touch the hardware */
    led.settings.mode = LED_MODE_OFF;
    led_apply(&led, frame, false);
    CHECK_EQ(read_int("/sys/class/sn3112l/led/enable"), 0);
}

/* --- #109: screen-on debounce and the bounded wake re-apply window ------- */

static void test_led_wake_state(void)
{
    led_screen screen;
    bool woke;

    /* Boot with the screen on: no wake window, no re-apply. */
    led_screen_init(&screen, true, 0);
    CHECK(led_screen_on(&screen));
    CHECK_EQ(led_screen_next_ms(&screen), 0);
    CHECK(!led_screen_reapply_due(&screen, 5000));

    /* A screen-off edge must be stable for the debounce time. */
    woke = true;
    CHECK(!led_screen_update(&screen, false, 1000, &woke));
    CHECK(!woke);
    CHECK(led_screen_on(&screen));
    CHECK_EQ(led_screen_next_ms(&screen), 1000 + LED_WAKE_DEBOUNCE_MS);
    CHECK(!led_screen_update(&screen, false,
                             1000 + LED_WAKE_DEBOUNCE_MS - 1, &woke));
    CHECK(led_screen_update(&screen, false,
                            1000 + LED_WAKE_DEBOUNCE_MS, &woke));
    CHECK(!woke); /* off edge */
    CHECK(!led_screen_on(&screen));
    CHECK_EQ(led_screen_next_ms(&screen), 0);
    CHECK(!led_screen_reapply_due(&screen, 10000));

    /* A short on/off flicker while the screen is off is not a wake. */
    CHECK(!led_screen_update(&screen, true, 2000, &woke));
    CHECK(!led_screen_update(&screen, false, 2100, &woke));
    CHECK(!led_screen_update(&screen, false, 2200, &woke));
    CHECK(!woke);
    CHECK(!led_screen_on(&screen));
    CHECK_EQ(led_screen_next_ms(&screen), 0);

    /* A real wake also has to be stable, then the window opens. */
    CHECK(!led_screen_update(&screen, true, 3000, &woke));
    CHECK_EQ(led_screen_next_ms(&screen), 3000 + LED_WAKE_DEBOUNCE_MS);
    CHECK(!led_screen_update(&screen, true, 3249, &woke));
    CHECK(led_screen_update(&screen, true, 3250, &woke));
    CHECK(woke);
    CHECK(led_screen_on(&screen));
    CHECK_EQ(led_screen_next_ms(&screen), 3250);

    /* Exactly three rewrites: at the edge, +0.5 s and +1.5 s. */
    CHECK(led_screen_reapply_due(&screen, 3250));
    CHECK_EQ(led_screen_next_ms(&screen), 3250 + 500);
    CHECK(!led_screen_reapply_due(&screen, 3250 + 499));
    /* A late loop tick does not shift the remaining schedule. */
    CHECK(led_screen_reapply_due(&screen, 3250 + 542));
    CHECK_EQ(led_screen_next_ms(&screen), 3250 + 1500);
    CHECK(!led_screen_reapply_due(&screen, 3250 + 1499));
    CHECK(led_screen_reapply_due(&screen, 3250 + 1500));
    CHECK_EQ(led_screen_next_ms(&screen), 0);
    CHECK(!led_screen_reapply_due(&screen, 3250 + 5000));
    CHECK(!led_screen_reapply_due(&screen, 3250 + 100000));

    /* A new wake.  An off flicker shorter than the debounce must not cancel
     * the pending rewrite, a committed screen-off must. */
    CHECK(!led_screen_update(&screen, false, 5000, &woke));
    CHECK(led_screen_update(&screen, false, 5250, &woke));
    CHECK(!led_screen_update(&screen, true, 6000, &woke));
    CHECK(led_screen_update(&screen, true, 6250, &woke));
    CHECK(woke);
    CHECK(led_screen_reapply_due(&screen, 6250));
    CHECK_EQ(led_screen_next_ms(&screen), 6250 + 500);

    CHECK(!led_screen_update(&screen, false, 6500, &woke));
    CHECK(!led_screen_update(&screen, true, 6600, &woke)); /* flicker only */
    CHECK_EQ(led_screen_next_ms(&screen), 6250 + 500);
    CHECK(led_screen_reapply_due(&screen, 6250 + 500));
    CHECK_EQ(led_screen_next_ms(&screen), 6250 + 1500);

    CHECK(!led_screen_update(&screen, false, 7000, &woke));
    CHECK(led_screen_update(&screen, false, 7250, &woke));
    CHECK(!woke);
    CHECK_EQ(led_screen_next_ms(&screen), 0);
    CHECK(!led_screen_reapply_due(&screen, 6250 + 1500));
    CHECK(!led_screen_reapply_due(&screen, 9000));
}

/* The wake path invalidates the cache before every rewrite, so both
 * attributes are written again even when the previous write "succeeded". */
static void test_led_wake_rewrite(void)
{
    led_state led;
    rgb_t frame[2] = {{9, 8, 7}, {6, 5, 4}};
    char buf[64];
    char path[PATH_MAX + 64];

    led_state_init(&led, 0);
    CHECK(led_apply(&led, frame, true));
    CHECK(!read_file("/sys/class/sn3112l/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "1-9:8:7"));

    /* The controller lost the latched state (or ignored the transfers) while
     * the daemon's cache still says "written". */
    write_file("/sys/class/sn3112l/led/brightness", "0-0:0:0\n");
    write_file("/sys/class/sn3112l/led/enable", "0\n");
    write_file("/sys/class/sn3112r/led/brightness", "0-0:0:0\n");
    write_file("/sys/class/sn3112r/led/enable", "0\n");

    /* Without invalidation led_apply() is a no-op - this is issue #109. */
    CHECK(led_apply(&led, frame, true));
    CHECK(!read_file("/sys/class/sn3112l/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "0-0:0:0"));
    CHECK_EQ(read_int("/sys/class/sn3112l/led/enable"), 0);

    /* The wake path invalidates first: both attributes are rewritten. */
    led_invalidate(&led);
    CHECK(led_apply(&led, frame, true));
    CHECK(!read_file("/sys/class/sn3112l/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "1-9:8:7"));
    CHECK_EQ(read_int("/sys/class/sn3112l/led/enable"), 1);
    CHECK(!read_file("/sys/class/sn3112r/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "1-6:5:4"));
    CHECK_EQ(read_int("/sys/class/sn3112r/led/enable"), 1);

    /* A real write failure during a wake rewrite still leaves applied=false
     * so the caller's LED_RETRY_MS path runs; the retry then completes. */
    snprintf(path, sizeof(path), "%s/sys/class/sn3112l/led/brightness", g_root);
    unlink(path);
    if (mkdir(path, 0777) != 0) {
        fprintf(stderr, "cannot create %s\n", path);
        exit(2);
    }
    led_invalidate(&led);
    CHECK(!led_apply(&led, frame, true));
    CHECK(!led.applied);
    remove_rel("/sys/class/sn3112l/led/brightness");
    write_file("/sys/class/sn3112l/led/brightness", "0-0:0:0\n");
    CHECK(led_apply(&led, frame, true));
    CHECK(led.applied);
    CHECK(!read_file("/sys/class/sn3112l/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "1-9:8:7"));
}

/* --- thermal ------------------------------------------------------------- */

static void test_thermal(void)
{
    thermal_set set;

    thermal_scan(&set);
    CHECK_EQ(set.count, 2); /* pm8150_tz is not a CPU/GPU zone */
    CHECK_EQ(thermal_hottest_tenths(&set), 620);

    CHECK_EQ(thermal_raw_to_tenths(62000), 620);
    CHECK_EQ(thermal_raw_to_tenths(620), 620);
    CHECK_EQ(thermal_raw_to_tenths(62), 620);
}

/* --- #72: turning off always attempts state=0 ---------------------------- */

static void test_off_writes_state(void)
{
    fan_state fan;
    char path[PATH_MAX];

    memset(&fan, 0, sizeof(fan));
    fan_settings_default(&fan.settings);
    fan.out_pct = 50; /* running */

    /* The duty node write fails (models a denied/failed attribute): state=0
     * must still be written, and the output is unknown so the caller retries
     * on the next tick. */
    snprintf(path, sizeof(path), "%s/sys/class/gpio5_pwm2/duty", g_root);
    unlink(path);
    write_file("/sys/class/gpio5_pwm2/state", "1\n");
    CHECK_EQ(fan_apply_pct(&fan, 0, 0), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);
    CHECK_EQ(fan.out_pct, -1);

    /* duty is back: the retry completes and the cache becomes 0 */
    write_file("/sys/class/gpio5_pwm2/duty", "0\n");
    CHECK_EQ(fan_apply_pct(&fan, 0, 0), 0);
    CHECK_EQ(fan.out_pct, 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), 0);
}

/* --- #73: no thermal sensor must never mean "fan off" -------------------- */

static void test_sensor_fault(void)
{
    fan_state fan;
    thermal_set set;

    memset(&fan, 0, sizeof(fan));
    fan_settings_default(&fan.settings);

    /* No zone at all (zones can also appear after class core starts). */
    remove_rel("/sys/class/thermal/thermal_zone1");
    remove_rel("/sys/class/thermal/thermal_zone2");
    thermal_scan(&set);
    CHECK_EQ(set.count, 0);
    CHECK_EQ(thermal_hottest_tenths(&set), -1);
    CHECK_EQ(thermal_sweep(&set), -1);

    /* No sensor fails safe at the balanced preset (cooling, never off); the
     * kick level is below it, so the first apply goes straight to it. */
    CHECK_EQ(fan_control_tick(&fan, -1, 0, true), FAN_PRESET_BALANCED_PCT);
    CHECK_EQ(fan_control_tick(&fan, -1, 5000, true),
             FAN_PRESET_BALANCED_PCT);
    CHECK(fan.sensor_fault);
    CHECK(fan.safety);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);

    /* Sensors are back and cool: the fault clears and the safety releases. */
    mkdir_p("/sys/class/thermal/thermal_zone1");
    mkdir_p("/sys/class/thermal/thermal_zone2");
    write_file("/sys/class/thermal/thermal_zone1/type", "cpu0-usr\n");
    write_file("/sys/class/thermal/thermal_zone1/temp", "40000\n");
    write_file("/sys/class/thermal/thermal_zone2/type", "gpuss-0-usr\n");
    write_file("/sys/class/thermal/thermal_zone2/temp", "42000\n");
    thermal_scan(&set);
    CHECK_EQ(set.count, 2);
    CHECK_EQ(thermal_hottest_tenths(&set), 420);
    CHECK_EQ(fan_control_tick(&fan, thermal_hottest_tenths(&set), 1000, true),
             0);
    CHECK(!fan.sensor_fault);
    CHECK(!fan.safety);
}

/* --- fan tach must never be read while the fan is disabled --------------- */

static void test_rpm_while_off(void)
{
    write_file("/sys/class/gpio5_pwm2/state", "0\n");
    write_file("/sys/class/gpio5_pwm2/speed", "9999\n");
    CHECK_EQ(fan_read_rpm(), 0); /* no read at all while state=0 */

    write_file("/sys/class/gpio5_pwm2/state", "1\n");
    CHECK_EQ(fan_read_rpm(), 9999);

    remove_rel("/sys/class/gpio5_pwm2/speed");
    CHECK_EQ(fan_read_rpm(), -1);
    write_file("/sys/class/gpio5_pwm2/speed", "2400\n");
}

/* --- #69: LED nodes present but not yet writable, then writable ---------- */

static void test_led_retry(void)
{
    led_state led;
    rgb_t frame[2] = {{1, 2, 3}, {4, 5, 6}};
    char buf[64];
    char path[PATH_MAX];

    /* Model the vendor-chmod race: the attribute exists (present) but opening
     * it for write fails.  A directory in place of the file makes the write
     * fail even for root, which is how this looks to the daemon when the
     * vendor chmod has not run yet. */
    snprintf(path, sizeof(path), "%s/sys/class/sn3112l/led/brightness", g_root);
    unlink(path);
    if (mkdir(path, 0777) != 0) {
        fprintf(stderr, "cannot create %s\n", path);
        exit(2);
    }

    led_state_init(&led, 0);
    CHECK_EQ(led.backend, LED_BACKEND_SN3112);
    CHECK(!led.applied);
    CHECK(!led_apply(&led, frame, true));
    CHECK(!led.applied);

    remove_rel("/sys/class/sn3112l/led/brightness");
    write_file("/sys/class/sn3112l/led/brightness", "0-0:0:0\n");
    CHECK(led_apply(&led, frame, true));
    CHECK(led.applied);
    CHECK_EQ(read_int("/sys/class/sn3112l/led/enable"), 1);
    CHECK(!read_file("/sys/class/sn3112l/led/brightness", buf, sizeof(buf)));
    CHECK(!strcmp(buf, "1-1:2:3"));

    /* A fresh state (boot) must turn off a ring the bootloader/stock left on,
     * even though its own cache is empty. */
    {
        led_state fresh;

        led_state_init(&fresh, 0);
        CHECK(led_disable(&fresh));
        CHECK_EQ(read_int("/sys/class/sn3112l/led/enable"), 0);
        CHECK_EQ(read_int("/sys/class/sn3112r/led/enable"), 0);
        CHECK(!read_file("/sys/class/sn3112l/led/brightness", buf,
                         sizeof(buf)));
        CHECK(!strcmp(buf, "0-0:0:0"));
    }
}

/* --- #74: out-of-range numeric properties use the contract default ------- */

static void test_prop_range(void)
{
    const char *name = "persist.ashfall.test.range";

    unsetenv(name);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 50);
    setenv(name, "42", 1);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 42);
    setenv(name, "0", 1);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 0);
    setenv(name, "100", 1);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 100);
    setenv(name, "200", 1);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 50);
    setenv(name, "-1", 1);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 50);
    setenv(name, "abc", 1);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 50);
    setenv(name, "5 %", 1);
    CHECK_EQ(prop_get_int_range(name, 50, 0, 100), 50);
    unsetenv(name);
}

/* --- thermal: only the hottest few zones are sampled per tick ------------ */

static void test_thermal_watch(void)
{
    thermal_set set;

    /* Two cooler zones that will not be watched after the sweep. */
    mkdir_p("/sys/class/thermal/thermal_zone3");
    mkdir_p("/sys/class/thermal/thermal_zone4");
    write_file("/sys/class/thermal/thermal_zone3/type", "cpu-1-3\n");
    write_file("/sys/class/thermal/thermal_zone3/temp", "30000\n");
    write_file("/sys/class/thermal/thermal_zone4/type", "cpu-0-0\n");
    write_file("/sys/class/thermal/thermal_zone4/temp", "20000\n");

    thermal_scan(&set);
    CHECK_EQ(set.count, 4);
    CHECK_EQ(set.watch_count, THERMAL_MAX_WATCH);
    CHECK_EQ(thermal_hottest_tenths(&set), 420); /* gpuss-0-usr */

    /* A zone outside the watch set is not read per tick (that is the whole
     * point: each read spams the kernel log), a periodic sweep catches it. */
    write_file("/sys/class/thermal/thermal_zone4/temp", "99000\n");
    CHECK_EQ(thermal_hottest_tenths(&set), 420);
    CHECK_EQ(thermal_sweep(&set), 990);
    CHECK_EQ(thermal_hottest_tenths(&set), 990);

    /* Restore the baseline tree for the earlier tests' assumptions. */
    remove_rel("/sys/class/thermal/thermal_zone3");
    remove_rel("/sys/class/thermal/thermal_zone4");
    write_file("/sys/class/thermal/thermal_zone1/temp", "55000\n");
    write_file("/sys/class/thermal/thermal_zone2/temp", "62000\n");
    thermal_scan(&set);
    CHECK_EQ(thermal_hottest_tenths(&set), 620);
}

/* --- #82: soft clock-throttle override ---------------------------------- */

/* Matches the live RP5: the CPU/GPU clock cdevs are bound with
 * `cdevN_trip_point` to the ddr/gpuss trips (95 C) and secondary GPU zones
 * (100 C); the cpu-1-* 75 C trip has no cooling device on this kernel. */
static void test_throttle(void)
{
    throttle_state th;

    /* cpu-1-0: a 75 C reporting trip with NO cdev and the 110 C pause trip. */
    mkdir_p("/sys/class/thermal/thermal_zone5");
    write_file("/sys/class/thermal/thermal_zone5/type", "cpu-1-0\n");
    write_file("/sys/class/thermal/thermal_zone5/temp", "65000\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_0_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_0_temp", "75000\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_1_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_1_temp", "110000\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_2_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_2_temp", "115000\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_3_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone5/trip_point_3_temp", "125000\n");

    /* test_sensor_fault() recreated the GPU zone without its trip/cdev files. */
    write_file("/sys/class/thermal/thermal_zone2/trip_point_0_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_0_temp", "95000\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_1_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_1_temp", "115000\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_2_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_2_temp", "125000\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_3_type", "critical\n");
    write_file("/sys/class/thermal/thermal_zone2/trip_point_3_temp", "155000\n");
    mkdir_p("/sys/class/thermal/thermal_zone2/cdev0");
    write_file("/sys/class/thermal/thermal_zone2/cdev0/type",
               "devfreq-3d00000.qcom,kgsl-3d0\n");
    write_file("/sys/class/thermal/thermal_zone2/cdev0_trip_point", "0\n");

    /* ddr: cpufreq-cpu4 + cpufreq-cpu7 + GPU devfreq + cdsp all on trip 1
     * (95 C).  Only the clock cdevs are targeted; the trip target is the CPU
     * one (105 C). */
    mkdir_p("/sys/class/thermal/thermal_zone6");
    write_file("/sys/class/thermal/thermal_zone6/type", "ddr\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_0_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_0_temp", "125000\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_1_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_1_temp", "95000\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_2_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_2_temp", "100000\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_3_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone6/trip_point_3_temp", "115000\n");
    for (int i = 0; i < 4; i++) {
        char dir[128];
        char file[128];

        snprintf(dir, sizeof(dir), "/sys/class/thermal/thermal_zone6/cdev%d", i);
        mkdir_p(dir);
        snprintf(file, sizeof(file), "%s/type", dir);
        write_file(file, i == 0   ? "cpufreq-cpu4\n"
                         : i == 1 ? "cpufreq-cpu7\n"
                         : i == 2 ? "devfreq-3d00000.qcom,kgsl-3d0\n"
                                  : "cdsp\n");
        snprintf(file, sizeof(file), "%s/cdev%d_trip_point",
                 "/sys/class/thermal/thermal_zone6", i);
        write_file(file, "1\n");
    }

    /* A secondary GPU zone at 100 C (restart must remember 100, not 95). */
    mkdir_p("/sys/class/thermal/thermal_zone7");
    write_file("/sys/class/thermal/thermal_zone7/type", "cwlan\n");
    write_file("/sys/class/thermal/thermal_zone7/trip_point_0_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone7/trip_point_0_temp", "125000\n");
    write_file("/sys/class/thermal/thermal_zone7/trip_point_1_type", "passive\n");
    write_file("/sys/class/thermal/thermal_zone7/trip_point_1_temp", "100000\n");
    mkdir_p("/sys/class/thermal/thermal_zone7/cdev0");
    write_file("/sys/class/thermal/thermal_zone7/cdev0/type",
               "devfreq-3d00000.qcom,kgsl-3d0\n");
    write_file("/sys/class/thermal/thermal_zone7/cdev0_trip_point", "1\n");

    throttle_scan(&th);
    CHECK(th.supported);
    CHECK_EQ(th.trip_count, 3); /* ddr, gpuss-0, cwlan; 75 C has no cdev */
    CHECK_EQ(throttle_status(&th), 0);

    CHECK(throttle_set(&th, true));
    CHECK_EQ(throttle_status(&th), 1);
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone6/trip_point_1_temp"),
             105000); /* CPU clock target */
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone2/trip_point_0_temp"),
             110000); /* GPU clock target */
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone7/trip_point_1_temp"),
             110000);
    /* Protective/reporting trips are never touched. */
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone5/trip_point_0_temp"),
             75000);
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone5/trip_point_1_temp"),
             110000);
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone6/trip_point_2_temp"),
             100000);
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone2/trip_point_3_temp"),
             155000);

    CHECK(throttle_set(&th, false));
    CHECK_EQ(throttle_status(&th), 0);
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone6/trip_point_1_temp"),
             95000);
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone2/trip_point_0_temp"),
             95000);
    CHECK_EQ(read_int("/sys/class/thermal/thermal_zone7/trip_point_1_temp"),
             100000);

    /* An init restart sees the raised trips.  The fresh scan must recognise
     * them and restore each one to its own stock value (95 or 100 C). */
    {
        throttle_state restarted;

        CHECK(throttle_set(&th, true));
        throttle_scan(&restarted);
        CHECK(restarted.supported);
        CHECK_EQ(restarted.trip_count, 3);
        CHECK(restarted.active);
        CHECK(throttle_set(&restarted, false));
        CHECK_EQ(read_int("/sys/class/thermal/thermal_zone6/trip_point_1_temp"),
                 95000);
        CHECK_EQ(read_int("/sys/class/thermal/thermal_zone2/trip_point_0_temp"),
                 95000);
        CHECK_EQ(read_int("/sys/class/thermal/thermal_zone7/trip_point_1_temp"),
                 100000);
    }

    remove_rel("/sys/class/thermal/thermal_zone5");
    remove_rel("/sys/class/thermal/thermal_zone6");
    remove_rel("/sys/class/thermal/thermal_zone7");
    remove_rel("/sys/class/thermal/thermal_zone2/cdev0");
}


/* --- phase 6: GPU max-frequency cap ------------------------------------- */

static void test_gpu(void)
{
    const char *prop = "persist.ashfall.perf.gpu_max_mhz";
    const char *max_freq = FAKE_KGSL_DEVFREQ "/max_freq";
    gpu_state gpu;

    gpu_state_init(&gpu);
    CHECK_EQ(gpu_apply(&gpu, 670, false), 670);
    CHECK_EQ(read_int(max_freq), 670000000);
    CHECK_EQ(gpu_status_mhz(&gpu), 670);
    CHECK_EQ(gpu_status_oc(&gpu), 0);

    /* Stock DT table: 1000 MHz is not a step yet - logged no-op, not a crash. */
    CHECK_EQ(gpu_apply(&gpu, 1000, false), 670);
    CHECK_EQ(read_int(max_freq), 670000000);
    CHECK_EQ(gpu_status_oc(&gpu), 0);

    /* After the OC vendor_boot, the OC steps are selectable. */
    write_file(FAKE_KGSL_DEV "/kgsl/kgsl-3d0/gpu_available_frequencies",
               "1000000000 940000000 850000000 765000000 670000000 587000000 "
               "525000000 490000000 441600000 400000000 305000000\n");
    gpu_state_init(&gpu);
    CHECK_EQ(gpu_apply(&gpu, 1000, false), 1000);
    CHECK_EQ(read_int(max_freq), 1000000000);
    CHECK_EQ(gpu_status_mhz(&gpu), 1000);
    CHECK_EQ(gpu_status_oc(&gpu), 1);

    /* Highest available step <= the request. */
    CHECK_EQ(gpu_apply(&gpu, 900, false), 850);
    CHECK_EQ(read_int(max_freq), 850000000);
    CHECK_EQ(gpu_apply(&gpu, 441, false), 400); /* 441.6 MHz is above 441 */
    CHECK_EQ(gpu_apply(&gpu, 305, false), 305);
    CHECK_EQ(read_int(max_freq), 305000000);
    CHECK_EQ(gpu_apply(&gpu, 670, false), 670);

    /* The phase-5 throttle override clamps the cap to stock 670 MHz. */
    CHECK_EQ(gpu_apply(&gpu, 1000, true), 670);
    CHECK_EQ(read_int(max_freq), 670000000);
    CHECK_EQ(gpu_status_mhz(&gpu), 670);
    CHECK_EQ(gpu_status_oc(&gpu), 0);
    /* ... and leaving the override re-applies the persisted request. */
    CHECK_EQ(gpu_apply(&gpu, 1000, false), 1000);
    CHECK_EQ(read_int(max_freq), 1000000000);

    /* The property is 305..1000, default 670, invalid -> 670. */
    unsetenv(prop);
    CHECK_EQ(prop_get_int_range(prop, GPU_STOCK_MHZ, GPU_MIN_MHZ, GPU_MAX_MHZ),
             670);
    setenv(prop, "305", 1);
    CHECK_EQ(prop_get_int_range(prop, GPU_STOCK_MHZ, GPU_MIN_MHZ, GPU_MAX_MHZ),
             305);
    setenv(prop, "940", 1);
    CHECK_EQ(prop_get_int_range(prop, GPU_STOCK_MHZ, GPU_MIN_MHZ, GPU_MAX_MHZ),
             940);
    setenv(prop, "1000", 1);
    CHECK_EQ(prop_get_int_range(prop, GPU_STOCK_MHZ, GPU_MIN_MHZ, GPU_MAX_MHZ),
             1000);
    setenv(prop, "1001", 1);
    CHECK_EQ(prop_get_int_range(prop, GPU_STOCK_MHZ, GPU_MIN_MHZ, GPU_MAX_MHZ),
             670);
    setenv(prop, "0", 1);
    CHECK_EQ(prop_get_int_range(prop, GPU_STOCK_MHZ, GPU_MIN_MHZ, GPU_MAX_MHZ),
             670);
    setenv(prop, "fast", 1);
    CHECK_EQ(prop_get_int_range(prop, GPU_STOCK_MHZ, GPU_MIN_MHZ, GPU_MAX_MHZ),
             670);
    unsetenv(prop);

    /* A missing kgsl devfreq node is a logged no-op, not a crash. */
    remove_rel(FAKE_KGSL_DEV);
    gpu_state_init(&gpu);
    CHECK_EQ(gpu_apply(&gpu, 1000, false), -1);
    CHECK_EQ(gpu_status_mhz(&gpu), -1);
    CHECK_EQ(gpu_status_oc(&gpu), -1);

    /* Older kona DT layouts expose the nodes under /sys/devices/soc/...; the
     * daemon falls back to that layout when the /platform level is absent. */
    mkdir_p(FAKE_KGSL_DEVFREQ_LEGACY);
    mkdir_p(FAKE_KGSL_LEGACY_KGSL);
    write_file(FAKE_KGSL_DEVFREQ_LEGACY "/max_freq", "670000000\n");
    write_file(FAKE_KGSL_DEVFREQ_LEGACY "/min_freq", "305000000\n");
    write_file(FAKE_KGSL_LEGACY_KGSL "/gpu_available_frequencies",
               "1000000000 940000000 850000000 765000000 670000000 305000000\n");
    gpu_state_init(&gpu);
    CHECK_EQ(gpu_apply(&gpu, 1000, false), 1000);
    CHECK_EQ(read_int(FAKE_KGSL_DEVFREQ_LEGACY "/max_freq"), 1000000000);
    CHECK_EQ(gpu_status_oc(&gpu), 1);
    remove_rel(FAKE_KGSL_LEGACY);
}

/* --- phase 6: live GPU clock for the Power page graph (#124) ------------- */

static void test_gpu_clock(void)
{
    const char *gpuclk = FAKE_KGSL_DEV "/kgsl/kgsl-3d0/gpuclk";

    /* test_gpu() removed the platform kgsl tree for the missing-node case. */
    mkdir_p(FAKE_KGSL_DEV "/kgsl/kgsl-3d0");
    /* kgsl reports gpuclk in Hz; the status property is MHz. */
    write_file(gpuclk, "670000000\n");
    CHECK_EQ(gpu_clock_mhz(), 670);
    write_file(gpuclk, "1000000000\n");
    CHECK_EQ(gpu_clock_mhz(), 1000);
    write_file(gpuclk, "441600000\n");
    CHECK_EQ(gpu_clock_mhz(), 442);

    /* 0 is a valid "GPU clocked off" reading, not an unknown one. */
    write_file(gpuclk, "0\n");
    CHECK_EQ(gpu_clock_mhz(), 0);

    /* Missing node: -1 (the graph draws a gap), no crash. */
    remove_rel(FAKE_KGSL_DEV "/kgsl/kgsl-3d0/gpuclk");
    CHECK_EQ(gpu_clock_mhz(), -1);
    /* A garbage value is unknown too. */
    write_file(gpuclk, "busy\n");
    CHECK_EQ(gpu_clock_mhz(), -1);
    write_file(gpuclk, "670000000\n");
}

/* --- phase 6: CPU caps and governor (#124) ------------------------------- */

static void test_cpu(void)
{
    cpu_state cpu;
    const char *silver_max = FAKE_CPUFREQ "/policy0/scaling_max_freq";
    const char *gold_max = FAKE_CPUFREQ "/policy4/scaling_max_freq";
    const char *prime_max = FAKE_CPUFREQ "/policy7/scaling_max_freq";

    CHECK_EQ(cpu_cluster_from_cpu(0), CPU_CLUSTER_SILVER);
    CHECK_EQ(cpu_cluster_from_cpu(3), CPU_CLUSTER_SILVER);
    CHECK_EQ(cpu_cluster_from_cpu(4), CPU_CLUSTER_GOLD);
    CHECK_EQ(cpu_cluster_from_cpu(6), CPU_CLUSTER_GOLD);
    CHECK_EQ(cpu_cluster_from_cpu(7), CPU_CLUSTER_PRIME);

    cpu_state_init(&cpu);
    CHECK_EQ(cpu_apply(&cpu, 0, 0, 0, "schedutil"), 0);
    CHECK_EQ(read_int(silver_max), 1804800);
    CHECK_EQ(read_int(gold_max), 2419200);
    CHECK_EQ(read_int(prime_max), 2841600);
    CHECK(file_is(FAKE_CPUFREQ "/policy0/scaling_governor", "schedutil"));
    CHECK(file_is(FAKE_CPUFREQ "/policy7/scaling_governor", "schedutil"));
    CHECK_EQ(cpu_status_mhz(&cpu, CPU_CLUSTER_SILVER), 1478);
    CHECK_EQ(cpu_status_mhz(&cpu, CPU_CLUSTER_GOLD), 1958);
    CHECK_EQ(cpu_status_mhz(&cpu, CPU_CLUSTER_PRIME), 1958);

    /* #124 Low power preset: the exact rounded steps must be selectable. */
    CHECK_EQ(cpu_apply(&cpu, 1171, 1478, 1632, "conservative"), 0);
    CHECK_EQ(read_int(silver_max), 1171200);
    CHECK_EQ(read_int(gold_max), 1478400);
    CHECK_EQ(read_int(prime_max), 1516800);
    CHECK(file_is(FAKE_CPUFREQ "/policy0/scaling_governor", "conservative"));

    /* Between steps: the highest available step <= the request. */
    CHECK_EQ(cpu_apply(&cpu, 1500, 2000, 2500, "schedutil"), 0);
    CHECK_EQ(read_int(silver_max), 1478400);
    CHECK_EQ(read_int(gold_max), 1958400);
    CHECK_EQ(read_int(prime_max), 2419200);
    /* Below the lowest step: the lowest step. */
    CHECK_EQ(cpu_apply(&cpu, 100, 100, 100, "schedutil"), 0);
    CHECK_EQ(read_int(silver_max), 300000);

    /* Governor validation: only the documented three, unknown -> schedutil. */
    CHECK(!strcmp(cpu_validate_governor("conservative",
                                        "walt conservative powersave schedutil"),
                  "conservative"));
    CHECK(!strcmp(cpu_validate_governor("powersave", "schedutil powersave"),
                  "powersave"));
    CHECK(!strcmp(cpu_validate_governor("walt", "walt powersave schedutil"),
                  "schedutil"));
    CHECK(!strcmp(cpu_validate_governor("performance",
                                        "performance powersave"),
                  "powersave"));
    CHECK(!strcmp(cpu_validate_governor(NULL, "schedutil conservative"),
                  "schedutil"));
    CHECK_EQ(cpu_apply(&cpu, 0, 0, 0, "walt"), 0);
    CHECK(file_is(FAKE_CPUFREQ "/policy0/scaling_governor", "schedutil"));
    CHECK(!strcmp(cpu_governor_name(&cpu), "schedutil"));

    /* Missing cpufreq is a logged no-op with -1 status, not a crash. */
    remove_rel(FAKE_CPUFREQ);
    cpu_state_init(&cpu);
    CHECK_EQ(cpu_apply(&cpu, 0, 0, 0, "schedutil"), -1);
    CHECK_EQ(cpu_status_mhz(&cpu, CPU_CLUSTER_SILVER), -1);
}

/* --- phase 6: the shared v1 swap header (zram + swapfile, #141) ---------- */

static void test_swap_header(void)
{
    char path[PATH_MAX];
    unsigned int value = 0;
    char magic[10];
    int fd;

    plat_resolve("/swapheader.test", path, sizeof(path));
    fd = open(path, O_RDWR | O_CREAT | O_TRUNC, 0600);
    CHECK(fd >= 0);
    CHECK_EQ(mem_write_swap_header(fd, 16LL * 1024 * 1024), 0);
    CHECK(read_at("/swapheader.test", 1024, &value, sizeof(value)));
    CHECK_EQ(value, 1); /* version */
    CHECK(read_at("/swapheader.test", 1028, &value, sizeof(value)));
    CHECK_EQ(value, 16u * 1024u * 1024u / 4096u - 1u); /* last_page */
    CHECK(read_at("/swapheader.test", 4086, magic, sizeof(magic)));
    CHECK(!memcmp(magic, "SWAPSPACE2", sizeof(magic)));
    /* A size below two pages cannot be a swap device. */
    CHECK_EQ(mem_write_swap_header(fd, 4096), -1);
    close(fd);
    unlink(path);
}

/* --- phase 6: zram and swapfile (#141) ----------------------------------- */

static void test_mem(void)
{
    mem_state mem;
    int free_mb;
    int limit;
    int used;
    int saved;
    int total;
    int swap_used;

    mem_state_init(&mem);
    CHECK_EQ(mem.mem_total_mb, 8000);
    CHECK(mem.zram_supported);
    CHECK_EQ(mem.zram_mb, 0);

    /* zram is clamped to MemTotal/2, never to a fixed 4096. */
    CHECK_EQ(mem_clamp_zram(&mem, 8000), 4000);
    CHECK_EQ(mem_clamp_zram(&mem, MEM_ZRAM_MAX_MB), 4000);
    CHECK_EQ(mem_clamp_zram(&mem, 512), 512);
    CHECK_EQ(mem_clamp_zram(&mem, 0), 0);
    CHECK_EQ(mem_clamp_zram(&mem, -1), 0);

    /* Apply order: reset -> compressor -> disksize -> swapon (nothing to swap
     * off on a fresh device). */
    CHECK_EQ(mem_apply_zram(&mem, 4096), 4000);
    CHECK(!strcmp(mem.trace, "reset,comp,disksize,signature,swapon"));
    CHECK_EQ(read_ll("/sys/block/zram0/disksize"), 4000LL * 1024 * 1024);
    {
        unsigned int value = 0;
        char magic[10];

        /* The reset device got the swap signature before swapon. */
        CHECK(read_at("/dev/block/zram0", 1024, &value, sizeof(value)));
        CHECK_EQ(value, 1);
        CHECK(read_at("/dev/block/zram0", 1028, &value, sizeof(value)));
        CHECK_EQ(value, 4000u * 1024u * 1024u / 4096u - 1u);
        CHECK(read_at("/dev/block/zram0", 4086, magic, sizeof(magic)));
        CHECK(!memcmp(magic, "SWAPSPACE2", sizeof(magic)));
    }
    {
        char comp[32];

        CHECK_EQ(read_file("/sys/block/zram0/comp_algorithm", comp,
                           sizeof(comp)),
                 0);
        CHECK(!strcmp(comp, "lz4")); /* lz4 wins when comp_available lists it */
    }

    /* Idempotent: the same size only re-attaches the device. */
    CHECK_EQ(mem_apply_zram(&mem, 4000), 4000);
    CHECK(!strcmp(mem.trace, "swapon"));

    /* Resize/disable: swapoff -> reset, status back to 0. */
    CHECK_EQ(mem_apply_zram(&mem, 0), 0);
    CHECK(!strcmp(mem.trace, "swapoff,reset"));
    CHECK_EQ(mem.zram_mb, 0);

    /* First available compressor when lz4 is not listed. */
    write_file("/sys/block/zram0/comp_available", "lzo lzo-rle\n");
    CHECK_EQ(mem_apply_zram(&mem, 2048), 2048);
    {
        char comp[32];

        CHECK_EQ(read_file("/sys/block/zram0/comp_algorithm", comp,
                           sizeof(comp)),
                 0);
        CHECK(!strcmp(comp, "lzo"));
    }

    /* mm_stat: used = compressed size, saved = orig - compressed. */
    write_file("/sys/block/zram0/mm_stat", "2000000 500000 700000 0 0 0 0\n");
    CHECK_EQ(mem_status_zram(&mem, &used, &saved), 0);
    CHECK_EQ(used, 488);
    CHECK_EQ(saved, 1464);

    /* Missing /dev/block/zram0 (module not loaded): logged no-op, -1 status. */
    remove_rel("/dev/block/zram0");
    CHECK_EQ(mem_apply_zram(&mem, 2048), -1);
    CHECK_EQ(mem.zram_mb, -1);
    write_file("/dev/block/zram0", "");

    /* Swap: 16384 MB cap and the 2 GiB /data reserve. */
    free_mb = mem_data_free_mb();
    CHECK(free_mb > 0);
    limit = free_mb - MEM_SWAP_RESERVE_MB;
    if (limit < 0)
        limit = 0;
    if (limit > MEM_SWAP_MAX_MB)
        limit = MEM_SWAP_MAX_MB;
    CHECK_EQ(mem_clamp_swap(&mem, MEM_SWAP_MAX_MB), limit);
    CHECK_EQ(mem_clamp_swap(&mem, 0), 0);

    /* Create -> header -> swapon, idempotent re-attach, then disable unlinks
     * the file and gives the space back. */
    CHECK_EQ(mem_apply_swap(&mem, 32), 32);
    CHECK(!strcmp(mem.trace, "create,mkswap,swapon"));
    CHECK_EQ(file_size(MEM_SWAPFILE), 32LL * 1024 * 1024);
    CHECK_EQ(mem_apply_swap(&mem, 32), 32);
    CHECK(!strcmp(mem.trace, "swapon"));
    CHECK_EQ(mem_apply_swap(&mem, 0), 0);
    CHECK(!strcmp(mem.trace, "swapoff,unlink"));
    CHECK_EQ(file_size(MEM_SWAPFILE), -1);
    CHECK_EQ(mem.swap_mb, 0);

    /* /proc/meminfo SwapTotal/SwapFree. */
    write_file("/proc/meminfo",
               "MemTotal:        8192000 kB\n"
               "SwapTotal:       4096000 kB\nSwapFree:        1024000 kB\n");
    CHECK_EQ(mem_status_swap(&total, &swap_used), 0);
    CHECK_EQ(total, 4096000);
    CHECK_EQ(swap_used, 3072000);
}

/* --- #129: max_pct lowered while the spin-up kick is open ---------------- */

static void test_kick_max_lowered(void)
{
    fan_state fan;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 5;
    fan.settings.min_pct = 5;
    fan.settings.max_pct = 100;
    fan.settings.kick = true;
    fan.settings.kick_pct = FAN_KICK_DEFAULT_PCT;

    CHECK_EQ(fan_control_tick(&fan, 400, 1000, true), FAN_KICK_DEFAULT_PCT);
    CHECK(fan_kick_pending(&fan, 1000));
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"),
             fan_pct_to_duty(FAN_KICK_DEFAULT_PCT));

    /* The cap goes to 10 inside the 500 ms window: the fan must drop to the
     * (already limited) target immediately, not stay at the old kick level. */
    fan.settings.max_pct = 10;
    CHECK_EQ(fan_control_tick(&fan, 400, 1200, true), 5);
    CHECK_EQ(fan.out_pct, 5);
    CHECK_EQ(fan.kick_until_ms, 0);
    CHECK(!fan_kick_pending(&fan, 1200));
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(5));

    /* The kick level is clamped to the new cap from the start: a 5 % target
     * with max 10 kicks to 10 (never 30), and drops to 5 when it expires. */
    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_MANUAL;
    fan.settings.manual_pct = 5;
    fan.settings.min_pct = 5;
    fan.settings.max_pct = 10;
    fan.settings.kick = true;
    fan.settings.kick_pct = FAN_KICK_DEFAULT_PCT;
    CHECK_EQ(fan_control_tick(&fan, 400, 2000, true), 10);
    CHECK(fan_kick_pending(&fan, 2000));
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(10));
    CHECK_EQ(fan_control_tick(&fan, 400, 2000 + FAN_KICK_MS + 1, true), 5);
}

int main(void)
{
    if (make_sysfs() < 0) {
        fprintf(stderr, "cannot create the fake sysfs tree\n");
        return 2;
    }

    test_curve();
    test_duty();
    test_thermal();
    test_apply_and_kick();
    test_screen_off();
    test_presets();
    test_safety();
    test_limits();
    test_smart();
    test_smart_preview();
    test_led_colour();
    test_led_effects();
    test_led_apply();
    test_led_wake_state();
    test_led_wake_rewrite();
    test_off_writes_state();
    test_sensor_fault();
    test_rpm_while_off();
    test_led_retry();
    test_prop_range();
    test_thermal_watch();
    test_throttle();
    test_gpu();
    test_gpu_clock();
    test_swap_header();
    test_cpu();
    test_mem();
    test_kick_max_lowered();

    /* best effort cleanup */
    {
        char cmd[PATH_MAX + 32];

        snprintf(cmd, sizeof(cmd), "rm -rf '%s'", g_root);
        if (system(cmd) != 0)
            fprintf(stderr, "warning: could not remove %s\n", g_root);
    }

    if (g_failures) {
        fprintf(stderr, "%d check(s) failed\n", g_failures);
        return 1;
    }
    printf("ashfall-hwd host tests: all checks passed\n");
    return 0;
}
