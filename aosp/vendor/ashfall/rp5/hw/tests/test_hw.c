/*
 * Host-side tests for the ashfall-hwd logic: fan curve, safety override,
 * spin-up kick, LED effects and thermal zone selection.  No device, no root:
 * sysfs is faked under $ASHFALL_SYSFS_ROOT.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "fan.h"
#include "led.h"
#include "plat.h"
#include "thermal.h"

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

    mkdir_p("/sys/class/thermal/thermal_zone0");
    mkdir_p("/sys/class/thermal/thermal_zone1");
    mkdir_p("/sys/class/thermal/thermal_zone2");
    write_file("/sys/class/thermal/thermal_zone0/type", "pm8150_tz\n");
    write_file("/sys/class/thermal/thermal_zone0/temp", "90000\n");
    write_file("/sys/class/thermal/thermal_zone1/type", "cpu0-usr\n");
    write_file("/sys/class/thermal/thermal_zone1/temp", "55000\n");
    write_file("/sys/class/thermal/thermal_zone2/type", "gpuss-0-usr\n");
    write_file("/sys/class/thermal/thermal_zone2/temp", "62000\n");
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
    fan.settings.manual_pct = 50;

    /* 0 -> nonzero: spin-up kick at 100 % first. */
    CHECK_EQ(fan_control_tick(&fan, 400, now), 100);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(100));
    CHECK(fan_kick_pending(&fan, now + 100));

    /* After the kick the target speed is applied. */
    CHECK_EQ(fan_control_tick(&fan, 400, now + FAN_MIN_KICK_MS + 1), 50);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(50));

    /* off disables the fan. */
    fan.settings.mode = FAN_MODE_OFF;
    CHECK_EQ(fan_control_tick(&fan, 400, now + 10000), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), 0);
}

static void test_presets(void)
{
    fan_state fan;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_QUIET;
    CHECK_EQ(fan_control_tick(&fan, 400, 1000), 100); /* kick */
    CHECK_EQ(fan_control_tick(&fan, 400, 1000 + FAN_MIN_KICK_MS + 1),
             FAN_PRESET_QUIET_PCT);

    fan.settings.mode = FAN_MODE_MAX;
    CHECK_EQ(fan_control_tick(&fan, 400, 5000), FAN_PRESET_MAX_PCT);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/duty"), fan_pct_to_duty(100));
}

static void test_safety(void)
{
    fan_state fan;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_OFF;
    fan.settings.safety_c = 85;

    /* 85.0 C in off mode -> full speed, safety latched. */
    CHECK_EQ(fan_control_tick(&fan, 850, 1000), 100);
    CHECK(fan.safety);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 1);

    /* Just below the threshold: still latched (release is 10 C lower). */
    CHECK_EQ(fan_control_tick(&fan, 760, 5000), 100);
    CHECK(fan.safety);

    /* 75.0 C -> release, back to off. */
    CHECK_EQ(fan_control_tick(&fan, 750, 9000), 0);
    CHECK(!fan.safety);
    CHECK_EQ(read_int("/sys/class/gpio5_pwm2/state"), 0);
}

static void test_smart(void)
{
    fan_state fan;
    int target;

    fan_state_init(&fan);
    fan.settings.mode = FAN_MODE_SMART;
    fan_parse_curve("45:0,55:30,65:55,75:80,82:100", &fan.settings.curve);

    fan_control_tick(&fan, 650, 1000);
    target = fan.target_pct;
    CHECK_EQ(target, 55);

    /* A single 2 C dip must not lower the target (hysteresis is 3 C and the
     * EMA needs a few samples to move anyway). */
    fan_control_tick(&fan, 630, 3000);
    CHECK_EQ(fan.target_pct, target);

    /* A sustained drop does re-evaluate once the smoothed temperature is a
     * full 3 C below the anchor: the curve gives ~47 % at the EMA (~61.8 C)
     * and the new anchor then holds for another 3 C. */
    for (int i = 0; i < 10; i++)
        fan_control_tick(&fan, 600, 5000 + i * 2000);
    CHECK(fan.target_pct < target);
    CHECK(fan.target_pct >= 45);
    CHECK(fan.target_pct <= 48);

    /* Rising temperature is followed without waiting for hysteresis (the EMA
     * needs a few samples, then the curve reaches 100 %). */
    for (int i = 0; i < 10; i++)
        fan_control_tick(&fan, 820, 40000 + i * 2000);
    CHECK(fan.target_pct >= 99);
}

/* --- led ----------------------------------------------------------------- */

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

    /* breathing stays between 5 % and 100 % of the base colour */
    led.settings.mode = LED_MODE_BREATHING;
    led.settings.left = (rgb_t){200, 200, 200};
    led.settings.right = (rgb_t){200, 200, 200};
    for (int t = 0; t <= 800; t += 20) {
        led_frame(&led, t, frame);
        CHECK(frame[0].r >= 9);
        CHECK(frame[0].r <= 200);
    }

    /* rainbow at t=0 is pure red, a third of the period later pure green */
    led.settings.mode = LED_MODE_RAINBOW;
    led.settings.speed = 5; /* period 1200 ms */
    led.settings.sync = true;
    led_frame(&led, 0, frame);
    CHECK_EQ(frame[0].r, 255);
    CHECK_EQ(frame[0].g, 0);
    led_frame(&led, 400, frame);
    CHECK_EQ(frame[0].r, 0);
    CHECK_EQ(frame[0].g, 255);
    CHECK(!memcmp(&frame[0], &frame[1], sizeof(frame[0])));
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

    /* A stopped fan first gets the spin-up kick (100 % for FAN_MIN_KICK_MS),
     * then settles on the balanced preset: cooling, never off. */
    CHECK_EQ(fan_control_tick(&fan, -1, 0), 100);
    CHECK_EQ(fan_control_tick(&fan, -1, FAN_MIN_KICK_MS + 1),
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
    CHECK_EQ(fan_control_tick(&fan, thermal_hottest_tenths(&set), 1000), 0);
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
    test_presets();
    test_safety();
    test_smart();
    test_led_colour();
    test_led_effects();
    test_led_apply();
    test_off_writes_state();
    test_sensor_fault();
    test_rpm_while_off();
    test_led_retry();
    test_prop_range();
    test_thermal_watch();

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
