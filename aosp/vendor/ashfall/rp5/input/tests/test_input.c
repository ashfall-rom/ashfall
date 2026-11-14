/*
 * Host-side tests for the AshFall RP5 input extensions: calibration load
 * order, validation, deadzone math and the control socket protocol.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "calib.h"
#include "control.h"
#include "input.h"
#include "mapping.h"
#include "plat.h"
#include "session.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/input.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

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

static char g_dir[PATH_MAX];
static char g_socket[100];

#define FACTORY_TEXT                                                           \
    "left_x_center=130.0\nleft_x_min=-1100.0\nleft_x_max=1200.0\n"            \
    "left_y_center=40.0\nleft_y_min=-1150.0\nleft_y_max=1180.0\n"             \
    "right_x_center=90.0\nright_x_min=-1120.0\nright_x_max=1220.0\n"          \
    "right_y_center=-10.0\nright_y_min=-1170.0\nright_y_max=1190.0\n"         \
    "l2_min=100.0\nl2_max=1200.0\nr2_min=10.0\nr2_max=1300.0\n"

static void write_file(const char *path, const char *text)
{
    FILE *file = fopen(path, "w");

    if (!file) {
        fprintf(stderr, "cannot create %s: %s\n", path, strerror(errno));
        exit(2);
    }
    fputs(text, file);
    fclose(file);
}

static void make_dir(void)
{
    char template[] = "/tmp/ashfall-input-test.XXXXXX";

    if (!mkdtemp(template)) {
        fprintf(stderr, "mkdtemp failed\n");
        exit(2);
    }
    snprintf(g_dir, sizeof(g_dir), "%s", template);
    setenv("ASHFALL_CAL_DIR", g_dir, 1);
    snprintf(g_socket, sizeof(g_socket), "%.80s/ashfall_input", g_dir);
    setenv("ASHFALL_CONTROL_SOCKET", g_socket, 1);
    unsetenv("ANDROID_SOCKET_ashfall_input");
}

static char *user_path(void)
{
    static char path[PATH_MAX];

    snprintf(path, sizeof(path), "%.300s/joys", g_dir);
    return path;
}

static char *factory_path(void)
{
    static char path[PATH_MAX];

    snprintf(path, sizeof(path), "%.300s/joys.factory", g_dir);
    return path;
}

static void remove_file(const char *path)
{
    unlink(path);
}

/* --- calibration --------------------------------------------------------- */

static void test_defaults(void)
{
    struct calibration cal;

    calib_defaults(&cal);
    CHECK_EQ((long)cal.lx.center, 125);
    CHECK_EQ((long)cal.l2_max, 1266);
    CHECK(calib_validate(&cal, NULL, 0));
}

static void test_validation(void)
{
    struct calibration cal;
    char err[128];

    calib_defaults(&cal);
    cal.lx.center = cal.lx.max; /* min < center < max violated */
    CHECK(!calib_validate(&cal, err, sizeof(err)));
    CHECK(strstr(err, "left_x") != NULL);

    calib_defaults(&cal);
    cal.l2_max = cal.l2_min;
    CHECK(!calib_validate(&cal, err, sizeof(err)));

    calib_defaults(&cal);
    cal.ry.min = -100000.0;
    CHECK(!calib_validate(&cal, err, sizeof(err)));
}

static void test_pairs(void)
{
    struct calibration cal;
    char err[160];
    char *ok[] = {
        "left_x_center=125.0", "left_x_min=-1134.0", "left_x_max=1196.0",
        "left_y_center=31.0",  "left_y_min=-1146.0", "left_y_max=1174.0",
        "right_x_center=82.0", "right_x_min=-1121.0", "right_x_max=1218.0",
        "right_y_center=-6.0", "right_y_min=-1167.0", "right_y_max=1192.0",
        "l2_min=92.0",         "l2_max=1266.0",      "r2_min=0.0",
        "r2_max=1362.0",
    };

    CHECK(calib_parse_pairs(16, ok, &cal, err, sizeof(err)));
    CHECK_EQ((long)cal.lx.center, 125);

    /* missing key */
    CHECK(!calib_parse_pairs(15, ok, &cal, err, sizeof(err)));
    CHECK(strstr(err, "missing key") != NULL);

    /* unknown key */
    {
        char *bad[17];
        char extra[] = "foo=1.0";

        memcpy(bad, ok, sizeof(ok));
        bad[16] = extra;
        CHECK(!calib_parse_pairs(17, bad, &cal, err, sizeof(err)));
        CHECK(strstr(err, "unknown key") != NULL);
    }

    /* min >= center */
    {
        char *bad[16];

        memcpy(bad, ok, sizeof(ok));
        bad[0] = "left_x_center=2000.0";
        CHECK(!calib_parse_pairs(16, bad, &cal, err, sizeof(err)));
    }

    /* trailing junk after the number is not a value */
    {
        char *bad[16];

        memcpy(bad, ok, sizeof(ok));
        bad[0] = "left_x_center=125.0abc";
        CHECK(!calib_parse_pairs(16, bad, &cal, err, sizeof(err)));
    }

    /* malformed pair */
    {
        char *bad[16];

        memcpy(bad, ok, sizeof(ok));
        bad[0] = "left_x_center";
        CHECK(!calib_parse_pairs(16, bad, &cal, err, sizeof(err)));
    }
}

static void test_load_order(void)
{
    struct calibration cal;

    /* no files at all -> built-in defaults */
    remove_file(user_path());
    remove_file(factory_path());
    CHECK_EQ(calib_load(&cal), CAL_SOURCE_DEFAULT);
    CHECK_EQ((long)cal.lx.center, 125);

    /* factory copy only */
    write_file(factory_path(), FACTORY_TEXT);
    CHECK_EQ(calib_load(&cal), CAL_SOURCE_FACTORY);
    CHECK_EQ((long)cal.lx.center, 130);

    /* user file wins */
    write_file(user_path(), FACTORY_TEXT);
    CHECK_EQ(calib_load(&cal), CAL_SOURCE_USER);

    /* a corrupt user file must fall back to the factory copy */
    write_file(user_path(), "left_x_center=9999.0\n");
    CHECK_EQ(calib_load(&cal), CAL_SOURCE_FACTORY);

    remove_file(user_path());
    remove_file(factory_path());
}

static void test_save_roundtrip(void)
{
    struct calibration cal;
    struct calibration loaded;
    char err[160];
    char text[1024];

    calib_defaults(&cal);
    cal.lx.center = 200.0;
    CHECK(calib_save_file(user_path(), &cal, err, sizeof(err)));
    CHECK(calib_load_file(user_path(), &loaded, err, sizeof(err)));
    CHECK_EQ((long)loaded.lx.center, 200);

    calib_format(&cal, text, sizeof(text));
    CHECK(strstr(text, "left_x_center=200.0") != NULL);
    CHECK(strstr(text, "r2_max=1362.0") != NULL);
    remove_file(user_path());
}

/* --- #80: the exact stock factory file -------------------------------- */

/* Copied byte-for-byte from the archived /mnt/vendor/persist/retrostation/joys
 * of the test unit: the key order is not the canonical one, minimums are
 * negative and r2_min is 0.0, all of which the parser must accept. */
static const char FACTORY_RP5_TEXT[] =
    "right_x_center=82.0\n"
    "l2_min=92.0\n"
    "right_y_max=1192.0\n"
    "r2_min=0.0\n"
    "left_y_center=31.0\n"
    "right_x_max=1218.0\n"
    "left_x_center=125.0\n"
    "left_y_min=-1146.0\n"
    "left_x_max=1196.0\n"
    "r2_max=1362.0\n"
    "l2_max=1266.0\n"
    "right_y_min=-1167.0\n"
    "right_x_min=-1121.0\n"
    "left_x_min=-1134.0\n"
    "left_y_max=1174.0\n"
    "right_y_center=-6.0\n";

static void test_factory_rp5(void)
{
    char path[PATH_MAX];
    char err[160];
    struct calibration cal;
    struct calibration defaults;

    snprintf(path, sizeof(path), "%s/joys.rp5", g_dir);
    write_file(path, FACTORY_RP5_TEXT);
    CHECK(calib_load_file(path, &cal, err, sizeof(err)));
    calib_defaults(&defaults);
    CHECK(!memcmp(&cal, &defaults, sizeof(defaults)));
    remove_file(path);
}

/* --- mapping ------------------------------------------------------------- */

static void test_mapping(void)
{
    /* ADC direction is inverted: raw above the centre -> negative axis. */
    CHECK(stick_map(125, 125.0, -1134.0, 1196.0) == 0);
    CHECK(stick_map(1196, 125.0, -1134.0, 1196.0) == -32768);
    CHECK(stick_map(-1134, 125.0, -1134.0, 1196.0) == 32767);
    CHECK(stick_map(600, 125.0, -1134.0, 1196.0) < 0);

    /* Triggers rest above max and reach 255 at min. */
    CHECK_EQ(trigger_map(1266, 92.0, 1266.0), 0);
    CHECK_EQ(trigger_map(92, 92.0, 1266.0), 255);
    CHECK_EQ(trigger_map(2000, 92.0, 1266.0), 0); /* clamped */
}

static void test_deadzones(void)
{
    int x, y;

    /* inner 0 / outer 0: values pass through */
    x = 16000;
    y = -8000;
    stick_apply_deadzone(&x, &y, 0, 0);
    CHECK(x >= 15900 && x <= 16100);
    CHECK(y <= -7900 && y >= -8100);

    /* inner 10 %: a small deflection is zeroed */
    x = 1000;
    y = 1000;
    stick_apply_deadzone(&x, &y, 10, 0);
    CHECK_EQ(x, 0);
    CHECK_EQ(y, 0);

    /* full deflection still reaches full scale after the inner deadzone */
    x = -32768;
    y = 0;
    stick_apply_deadzone(&x, &y, 10, 0);
    CHECK(x <= -32700);

    /* outer 20 %: the last 20 % of travel is compressed to full scale */
    x = (int)(32767 * 0.85);
    y = 0;
    stick_apply_deadzone(&x, &y, 0, 20);
    CHECK(x >= 32000);

    /* diagonal deflection keeps its direction */
    x = 20000;
    y = 20000;
    stick_apply_deadzone(&x, &y, 5, 3);
    CHECK(x > 0 && y > 0);

    /* trigger deadzone */
    CHECK_EQ(trigger_apply_deadzone(5, 3), 0);
    CHECK_EQ(trigger_apply_deadzone(255, 3), 255);
    /* 50 % of travel after a 3 % deadzone is rescaled to ~48.6 % */
    CHECK(trigger_apply_deadzone(128, 3) >= 120);
    CHECK(trigger_apply_deadzone(128, 3) <= 128);
}

/* --- control socket ------------------------------------------------------ */

static int connect_client(void)
{
    struct sockaddr_un addr;
    struct timeval tv = {.tv_sec = 1, .tv_usec = 0};
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);

    if (fd < 0)
        return -1;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", g_socket);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return fd;
}

/* Poll the server until it has something for us, then dispatch. */
static void server_pump(struct control *control, int timeout_ms)
{
    struct pollfd fds[1 + CONTROL_MAX_CLIENTS];
    int nfds = control_pollfds(control, fds, (int)(sizeof(fds) / sizeof(fds[0])));
    int rc = poll(fds, (nfds_t)nfds, timeout_ms);

    if (rc > 0)
        control_dispatch(control, fds, nfds);
}

static char *client_command(struct control *control, int client,
                            const char *command, char *reply, size_t reply_len)
{
    reply[0] = '\0';
    if (send(client, command, strlen(command), 0) < 0)
        return reply;
    /* A freshly accepted connection is not in the poll set yet, so give the
     * server a couple of rounds to accept, read and answer. */
    for (int i = 0; i < 10; i++) {
        ssize_t n;

        server_pump(control, 100);
        n = recv(client, reply, reply_len - 1, MSG_DONTWAIT);
        if (n > 0) {
            reply[n] = '\0';
            return reply;
        }
    }
    return reply;
}

static void test_control_socket(void)
{
    struct input_ctx ctx;
    struct control *control;
    char reply[1200];
    int client;

    memset(&ctx, 0, sizeof(ctx));
    deadzones_default(&ctx.dz);
    input_reload_calibration(&ctx); /* defaults: no files */

    remove_file(user_path());
    remove_file(factory_path());
    input_reload_calibration(&ctx);
    CHECK_EQ(ctx.cal_source, CAL_SOURCE_DEFAULT);

    control = control_create(&ctx);
    CHECK(control != NULL);
    if (!control)
        return;

    client = connect_client();
    CHECK(client >= 0);
    if (client < 0) {
        control_destroy(control);
        return;
    }

    client_command(control, client, "GET CAL\n", reply, sizeof(reply));
    CHECK(strncmp(reply, "OK default ", 11) == 0);
    CHECK(strstr(reply, "left_x_center=125.0") != NULL);
    CHECK(strstr(reply, "r2_max=1362.0") != NULL);

    client_command(control, client, "SET CAL left_x_center=1.0\n", reply,
                   sizeof(reply));
    CHECK(strncmp(reply, "ERR ", 4) == 0);

    client_command(control, client,
                   "SET CAL left_x_center=125.0 left_x_min=-1134.0 "
                   "left_x_max=1196.0 left_y_center=31.0 left_y_min=-1146.0 "
                   "left_y_max=1174.0 right_x_center=82.0 "
                   "right_x_min=-1121.0 right_x_max=1218.0 "
                   "right_y_center=-6.0 right_y_min=-1167.0 "
                   "right_y_max=1192.0 l2_min=92.0 l2_max=1266.0 "
                   "r2_min=0.0 r2_max=1362.0\n",
                   reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK_EQ(ctx.cal_source, CAL_SOURCE_USER);
    {
        struct stat st;

        CHECK(stat(user_path(), &st) == 0);
    }

    /* the calibration survives a reload */
    input_reload_calibration(&ctx);
    CHECK_EQ(ctx.cal_source, CAL_SOURCE_USER);

    client_command(control, client, "RESET CAL\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK_EQ(ctx.cal_source, CAL_SOURCE_DEFAULT);
    CHECK(access(user_path(), F_OK) != 0);

    /* RAW streaming: one sample per 10 ms at most, exact format */
    client_command(control, client, "RAW ON\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    {
        struct raw_sample sample = {.lx = -100, .ly = 200, .rx = 300,
                                    .ry = -400, .l2 = 12, .r2 = 34,
                                    .buttons = 0x8001};

        control_stream_raw(control, 1000, &sample);
        {
            ssize_t n = recv(client, reply, sizeof(reply) - 1, 0);

            reply[n > 0 ? (size_t)n : 0] = '\0';
        }
        CHECK(strcmp(reply, "RAW -100 200 300 -400 12 34 8001\n") == 0);

        /* too soon: dropped */
        control_stream_raw(control, 1005, &sample);
        if (recv(client, reply, sizeof(reply) - 1, MSG_DONTWAIT) >= 0)
            CHECK(!"unexpected RAW sample inside the rate limit");

        /* 10 ms later: delivered again */
        control_stream_raw(control, 1010, &sample);
        {
            ssize_t n = recv(client, reply, sizeof(reply) - 1, 0);

            reply[n > 0 ? (size_t)n : 0] = '\0';
        }
        CHECK(strncmp(reply, "RAW ", 4) == 0);
    }
    client_command(control, client, "RAW OFF\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    {
        struct raw_sample sample = {0};

        control_stream_raw(control, 2000, &sample);
        if (recv(client, reply, sizeof(reply) - 1, MSG_DONTWAIT) >= 0)
            CHECK(!"RAW sample after RAW OFF");
    }

    /* GRAB ON/OFF (docs/phase5.md §6.2): one owner, auto-release on
     * disconnect, second client gets ERR busy. */
    client_command(control, client, "NOPE\n", reply, sizeof(reply));
    CHECK(strncmp(reply, "ERR ", 4) == 0);
    CHECK(!control_grabbed(control));
    client_command(control, client, "GRAB ON\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK(control_grabbed(control));
    {
        int other = connect_client();

        CHECK(other >= 0);
        if (other >= 0) {
            client_command(control, other, "GRAB ON\n", reply, sizeof(reply));
            CHECK(strncmp(reply, "ERR busy", 8) == 0);
            close(other);
            server_pump(control, 200);
        }
    }
    CHECK(control_grabbed(control)); /* still owned by the first client */
    client_command(control, client, "GRAB OFF\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK(!control_grabbed(control));

    /* A dangling GRAB ON is released automatically when its client goes. */
    client_command(control, client, "GRAB ON\n", reply, sizeof(reply));
    CHECK(control_grabbed(control));
    close(client);
    server_pump(control, 200);
    server_pump(control, 100);
    CHECK(!control_grabbed(control));

    /* #80: a factory copy that appears after startup is picked up by a
     * reload (the daemon retries while the source is `default`), and the
     * source is published as a status property. */
    remove_file(user_path());
    remove_file(factory_path());
    input_reload_calibration(&ctx);
    CHECK_EQ(ctx.cal_source, CAL_SOURCE_DEFAULT);
    write_file(factory_path(), FACTORY_TEXT);
    input_reload_calibration(&ctx);
    CHECK_EQ(ctx.cal_source, CAL_SOURCE_FACTORY);
    CHECK(ctx.cal_source_published);
    remove_file(factory_path());
    input_reload_calibration(&ctx);
    CHECK_EQ(ctx.cal_source, CAL_SOURCE_DEFAULT);

    control_destroy(control);
    remove_file(g_socket);
}

/* --- #71: releasing a lost MCU session ----------------------------------- */

struct captured {
    int count;
    struct {
        uint16_t type;
        uint16_t code;
        int32_t value;
    } ev[64];
};

static void capture(void *ctx, uint16_t type, uint16_t code, int32_t value)
{
    struct captured *cap = ctx;

    if (cap->count < (int)(sizeof(cap->ev) / sizeof(cap->ev[0]))) {
        cap->ev[cap->count].type = type;
        cap->ev[cap->count].code = code;
        cap->ev[cap->count].value = value;
    }
    cap->count++;
}

static const uint16_t test_keymap[SESSION_KEY_BITS] = {
    BTN_TL,      BTN_TR, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR,
    KEY_HOMEPAGE, KEY_BACK, 0, 0, 0, 0, BTN_X, BTN_Y, BTN_A, BTN_B,
};

/* Same order as the daemon's AXIS_* enum (L2, R2, LX, LY, RX, RY, HATX,
 * HATY); session_release() takes the codes, so the test pins the layout. */
enum { T_L2, T_R2, T_LX, T_LY, T_RX, T_RY, T_HATX, T_HATY };

static const uint16_t test_axes[SESSION_MAX_AXES] = {
    ABS_BRAKE, ABS_GAS, ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y,
};

static void test_session_release(void)
{
    session_state st;
    struct captured cap;

    /* A held face button, a deflected stick and a D-pad direction. */
    memset(&st, 0, sizeof(st));
    memset(&cap, 0, sizeof(cap));
    st.keys = (uint16_t)((1u << 14) | (1u << 10)); /* BTN_A + D-pad right */
    st.axis_count = SESSION_MAX_AXES;
    st.axes[T_LX] = 12000;
    st.axes[T_HATX] = 1; /* the rest are 0, i.e. already at rest */

    CHECK_EQ(session_release(&st, test_keymap, test_axes, capture, &cap), 3);
    CHECK_EQ(cap.count, 4); /* + SYN */
    CHECK_EQ(cap.ev[0].type, EV_KEY);
    CHECK_EQ(cap.ev[0].code, BTN_A);
    CHECK_EQ(cap.ev[0].value, 0);
    CHECK_EQ(cap.ev[1].type, EV_ABS);
    CHECK_EQ(cap.ev[1].code, ABS_X);
    CHECK_EQ(cap.ev[1].value, 0);
    CHECK_EQ(cap.ev[2].type, EV_ABS);
    CHECK_EQ(cap.ev[2].code, ABS_HAT0X);
    CHECK_EQ(cap.ev[3].type, EV_SYN);

    /* The state is clean and dirty-marked: nothing is released twice, and the
     * next frame re-reports every axis. */
    CHECK_EQ(st.keys, 0);
    for (int i = 0; i < SESSION_MAX_AXES; i++)
        CHECK_EQ(st.axes[i], INT_MIN);
    memset(&cap, 0, sizeof(cap));
    CHECK_EQ(session_release(&st, test_keymap, test_axes, capture, &cap), 0);
    CHECK_EQ(cap.count, 0);

    /* A clean session must not emit anything (no spurious SYN). */
    memset(&st, 0, sizeof(st));
    memset(&cap, 0, sizeof(cap));
    st.axis_count = SESSION_MAX_AXES;
    for (int i = 0; i < SESSION_MAX_AXES; i++)
        st.axes[i] = INT_MIN;
    CHECK_EQ(session_release(&st, test_keymap, test_axes, capture, &cap), 0);
    CHECK_EQ(cap.count, 0);
}

/* --- #70: the control socket answers without any MCU session ------------- */
static void test_control_without_mcu(void)
{
    struct input_ctx ctx;
    struct control *control;
    char reply[1200] = "";
    int client;
    bool answered = false;

    memset(&ctx, 0, sizeof(ctx));
    deadzones_default(&ctx.dz);
    ctx.on_calibration_changed = NULL;
    input_reload_calibration(&ctx);

    control = control_create(&ctx);
    CHECK(control != NULL);
    if (!control)
        return;
    client = connect_client();
    CHECK(client >= 0);
    if (client < 0) {
        control_destroy(control);
        return;
    }
    if (send(client, "GET CAL\n", 8, 0) < 0) {
        close(client);
        control_destroy(control);
        return;
    }

    /* Exactly what the daemon does while there is no MCU/uinput: only
     * control_poll(), never the session loop. */
    for (int i = 0; i < 10 && !answered; i++) {
        ssize_t n;

        control_poll(control, 100);
        n = recv(client, reply, sizeof(reply) - 1, MSG_DONTWAIT);
        if (n > 0) {
            reply[n] = '\0';
            answered = true;
        }
    }
    CHECK(answered);
    CHECK(strncmp(reply, "OK ", 3) == 0);
    CHECK(strstr(reply, "left_x_center=") != NULL);

    close(client);
    control_destroy(control);
}

/* --- #74: out-of-range deadzone properties use the contract default ------ */
static void test_deadzone_props(void)
{
    struct deadzones dz;

    deadzones_default(&dz);
    unsetenv("persist.ashfall.pad.deadzone_inner");
    unsetenv("persist.ashfall.pad.deadzone_outer");
    unsetenv("persist.ashfall.pad.trigger_deadzone");
    deadzones_read(&dz);
    CHECK_EQ(dz.inner_pct, 5);
    CHECK_EQ(dz.outer_pct, 3);
    CHECK_EQ(dz.trigger_pct, 3);

    setenv("persist.ashfall.pad.deadzone_inner", "12", 1);
    setenv("persist.ashfall.pad.deadzone_outer", "0", 1);
    setenv("persist.ashfall.pad.trigger_deadzone", "30", 1);
    deadzones_read(&dz);
    CHECK_EQ(dz.inner_pct, 12);
    CHECK_EQ(dz.outer_pct, 0);
    CHECK_EQ(dz.trigger_pct, 30);

    /* Out of range is invalid, not "clamp to the boundary". */
    setenv("persist.ashfall.pad.deadzone_inner", "99", 1);
    setenv("persist.ashfall.pad.deadzone_outer", "99", 1);
    setenv("persist.ashfall.pad.trigger_deadzone", "-1", 1);
    deadzones_read(&dz);
    CHECK_EQ(dz.inner_pct, 5);
    CHECK_EQ(dz.outer_pct, 3);
    CHECK_EQ(dz.trigger_pct, 3);

    unsetenv("persist.ashfall.pad.deadzone_inner");
    unsetenv("persist.ashfall.pad.deadzone_outer");
    unsetenv("persist.ashfall.pad.trigger_deadzone");
}

int main(void)
{
    make_dir();
    setenv("ASHFALL_LOG", "0", 1);

    test_defaults();
    test_factory_rp5();
    test_validation();
    test_pairs();
    test_load_order();
    test_save_roundtrip();
    test_mapping();
    test_deadzones();
    test_control_socket();
    test_session_release();
    test_control_without_mcu();
    test_deadzone_props();

    {
        char cmd[PATH_MAX + 32];

        snprintf(cmd, sizeof(cmd), "rm -rf '%s'", g_dir);
        if (system(cmd) != 0)
            fprintf(stderr, "warning: could not remove %s\n", g_dir);
    }

    if (g_failures) {
        fprintf(stderr, "%d check(s) failed\n", g_failures);
        return 1;
    }
    printf("ashfall-rp5-input host tests: all checks passed\n");
    return 0;
}
