/*
 * Host-side tests for the AshFall RP5 input extensions: calibration load
 * order, validation, deadzone math and the control socket protocol.
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
#include "uinput_dev.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
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

    snprintf(path, sizeof(path), "%.300s/joys.rp5", g_dir);
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
    struct fusion_state fusion;
    struct control *control;
    char reply[1200];
    int client;

    memset(&ctx, 0, sizeof(ctx));
    deadzones_default(&ctx.dz);
    input_reload_calibration(&ctx); /* defaults: no files */
    unsetenv(PROP_FUSION);
    fusion_defaults(&fusion);
    ctx.fusion = &fusion;

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

    /* #138 / docs/phase6.md §9.8: the fusion grammar on the same socket. */
    client_command(control, client, "FUSION STATUS\n", reply, sizeof(reply));
    CHECK(strncmp(reply, "OK FUSION 0 PADS 0\n", 19) == 0);

    client_command(control, client, "FUSION ON\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK(fusion.enabled);
    /* The app owns the property: the same text must not undo the command. */
    fusion_settings_read(&fusion);
    CHECK(fusion.enabled);

    client_command(control, client, "FUSION LAYOUT 2020:3001:mcu NINTENDO\n",
                   reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK_EQ(fusion.config_count, 1);
    CHECK_EQ(fusion.configs[0].layout, FUSION_LAYOUT_NINTENDO);

    client_command(control, client, "FUSION LAYOUT 2020:3001:mcu WAT\n", reply,
                   sizeof(reply));
    CHECK(strncmp(reply, "ERR ", 4) == 0);

    client_command(control, client, "FUSION REMAP 2020:3001:mcu 304->305\n",
                   reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK_EQ(fusion.configs[0].map_count, 1);

    client_command(control, client, "FUSION RESCAN\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK(fusion.rescan);

    client_command(control, client, "FUSION NOPE\n", reply, sizeof(reply));
    CHECK(strncmp(reply, "ERR ", 4) == 0);

    /* Back to the state the rest of this test expects. */
    client_command(control, client, "FUSION OFF\n", reply, sizeof(reply));
    CHECK(strcmp(reply, "OK\n") == 0);
    CHECK(!fusion.enabled);
    fusion.rescan = false;

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

/* --- #138: GamepadFusion ------------------------------------------------- */

static bool has_event(const struct captured *cap, uint16_t type, uint16_t code,
                      int32_t value)
{
    for (int i = 0; i < cap->count && i < (int)(sizeof(cap->ev) / sizeof(cap->ev[0]));
         i++) {
        if (cap->ev[i].type == type && cap->ev[i].code == code &&
            cap->ev[i].value == value)
            return true;
    }
    return false;
}

static int count_type(const struct captured *cap, uint16_t type)
{
    int count = 0;

    for (int i = 0; i < cap->count && i < (int)(sizeof(cap->ev) / sizeof(cap->ev[0]));
         i++) {
        if (cap->ev[i].type == type)
            count++;
    }
    return count;
}

static void flush_into(struct fusion_state *fs, struct captured *cap)
{
    memset(cap, 0, sizeof(*cap));
    fusion_flush(fs, capture, cap);
}

/* First flush after a topology change reports every axis; call it so the
 * assertions below only see real deltas. */
static void flush_prime(struct fusion_state *fs)
{
    struct captured cap;

    flush_into(fs, &cap);
}

static void test_fusion_classify(void)
{
    uint8_t keybits[FUSION_BITS_BYTES(KEY_MAX + 1)];
    uint8_t absbits[FUSION_BITS_BYTES(ABS_MAX + 1)];
    uint8_t props[FUSION_BITS_BYTES(INPUT_PROP_MAX + 1)];
    size_t kb = sizeof(keybits);
    size_t ab = sizeof(absbits);
    size_t pb = sizeof(props);

    memset(keybits, 0, sizeof(keybits));
    memset(absbits, 0, sizeof(absbits));
    memset(props, 0, sizeof(props));

    /* A motion-only node: gyro/accelerometer with ABS_X/Y/Z and no gamepad
     * button.  It must never be classified as a pad (so it is never opened or
     * grabbed and Android's sensor path keeps it). */
    fusion_bits_set(absbits, ABS_X);
    fusion_bits_set(absbits, ABS_Y);
    fusion_bits_set(absbits, ABS_Z);
    fusion_bits_set(props, INPUT_PROP_ACCELEROMETER);
    CHECK(!fusion_classify(keybits, kb, absbits, ab, props, pb));
    CHECK(fusion_is_motion_only(keybits, kb, absbits, ab, props, pb));

    /* Same axes, but a gamepad button shows up: now it is a gamepad. */
    fusion_bits_set(keybits, BTN_SOUTH);
    CHECK(fusion_classify(keybits, kb, absbits, ab, props, pb));
    CHECK(!fusion_is_motion_only(keybits, kb, absbits, ab, props, pb));

    /* BTN_GAMEPAD and BTN_A are the same code as BTN_SOUTH on Linux. */
    memset(keybits, 0, sizeof(keybits));
    fusion_bits_set(keybits, BTN_GAMEPAD);
    CHECK(fusion_classify(keybits, kb, absbits, ab, props, pb));
    memset(keybits, 0, sizeof(keybits));
    fusion_bits_set(keybits, BTN_TRIGGER);
    CHECK(fusion_classify(keybits, kb, absbits, ab, props, pb));

    /* Buttons without a single axis are not a gamepad either. */
    memset(absbits, 0, sizeof(absbits));
    memset(props, 0, sizeof(props));
    CHECK(!fusion_classify(keybits, kb, absbits, ab, props, pb));

    /* A key-only node (the power button, a keyboard) is not motion either. */
    CHECK(!fusion_is_motion_only(keybits, kb, absbits, ab, props, pb));

    CHECK(fusion_bits_test(keybits, BTN_TRIGGER));
    CHECK(!fusion_bits_test(keybits, BTN_SOUTH));
}

static void test_fusion_identity(void)
{
    char key[FUSION_KEY_MAX];

    /* Our own uinput device, in both identities, must be ignored. */
    CHECK(fusion_is_self(0x2020, 0x3001, "AshFall RP5 Gamepad"));
    CHECK(fusion_is_self(0x045e, 0x028e, "AshFall Fusion Controller"));
    CHECK(fusion_is_self(0x045e, 0x028e, ""));
    /* A real Xbox pad shares 045e:028e but not our name. */
    CHECK(!fusion_is_self(0x045e, 0x028e, "Microsoft X-Box One pad"));
    CHECK(!fusion_is_self(0x0b05, 0x1b21, "8BitDo Ultimate 2C"));

    fusion_key_format(0x045e, 0x028e, FUSION_CONN_BT, key, sizeof(key));
    CHECK(strcmp(key, "045e:028e:bt") == 0);
    fusion_key_format(0x2020, 0x3001, FUSION_CONN_MCU, key, sizeof(key));
    CHECK(strcmp(key, "2020:3001:mcu") == 0);
    fusion_key_format(0x0b05, 0x1b21, FUSION_CONN_USB, key, sizeof(key));
    CHECK(strcmp(key, "0b05:1b21:usb") == 0);
    CHECK(strcmp(fusion_connection_name(FUSION_CONN_BT), "bt") == 0);
    CHECK(strcmp(fusion_layout_name(FUSION_LAYOUT_NINTENDO), "NINTENDO") == 0);
}

static void test_fusion_axis_sources(void)
{
    struct fusion_absinfo info[FUSION_ABS_MAX];
    uint16_t src[FUSION_AXIS_COUNT];

    /* The MCU / Android VirtualGamepad shape: triggers on ABS_BRAKE/ABS_GAS,
     * right stick on ABS_Z/ABS_RZ. */
    memset(info, 0, sizeof(info));
    info[ABS_X].present = true;
    info[ABS_Y].present = true;
    info[ABS_Z].present = true;
    info[ABS_Z].minimum = -32768;
    info[ABS_Z].maximum = 32767;
    info[ABS_RZ].present = true;
    info[ABS_RZ].minimum = -32768;
    info[ABS_RZ].maximum = 32767;
    info[ABS_BRAKE].present = true;
    info[ABS_BRAKE].minimum = 0;
    info[ABS_BRAKE].maximum = 255;
    info[ABS_GAS].present = true;
    info[ABS_HAT0X].present = true;
    info[ABS_HAT0Y].present = true;
    fusion_axis_sources(info, src);
    CHECK_EQ(src[FUSION_AXIS_LX], ABS_X);
    CHECK_EQ(src[FUSION_AXIS_LY], ABS_Y);
    CHECK_EQ(src[FUSION_AXIS_L2], ABS_BRAKE);
    CHECK_EQ(src[FUSION_AXIS_R2], ABS_GAS);
    CHECK_EQ(src[FUSION_AXIS_RX], ABS_Z);
    CHECK_EQ(src[FUSION_AXIS_RY], ABS_RZ);
    CHECK_EQ(src[FUSION_AXIS_HATX], ABS_HAT0X);
    CHECK_EQ(src[FUSION_AXIS_HATY], ABS_HAT0Y);

    /* The HID Xbox shape (an 8BitDo in XInput mode): triggers on ABS_Z/ABS_RZ
     * as 0..255, right stick on ABS_RX/ABS_RY.  A resting trigger must not be
     * read as a deflected right stick - the stock app's most-reported bug. */
    memset(info, 0, sizeof(info));
    info[ABS_X].present = true;
    info[ABS_Y].present = true;
    info[ABS_Z].present = true;
    info[ABS_Z].minimum = 0;
    info[ABS_Z].maximum = 255;
    info[ABS_RZ].present = true;
    info[ABS_RZ].minimum = 0;
    info[ABS_RZ].maximum = 255;
    info[ABS_RX].present = true;
    info[ABS_RY].present = true;
    info[ABS_HAT0X].present = true;
    info[ABS_HAT0Y].present = true;
    fusion_axis_sources(info, src);
    CHECK_EQ(src[FUSION_AXIS_L2], ABS_Z);
    CHECK_EQ(src[FUSION_AXIS_R2], ABS_RZ);
    CHECK_EQ(src[FUSION_AXIS_RX], ABS_RX);
    CHECK_EQ(src[FUSION_AXIS_RY], ABS_RY);

    /* A pad with neither triggers nor a right stick classified as one. */
    memset(info, 0, sizeof(info));
    info[ABS_X].present = true;
    info[ABS_Y].present = true;
    fusion_axis_sources(info, src);
    CHECK_EQ(src[FUSION_AXIS_L2], 0);
    CHECK_EQ(src[FUSION_AXIS_R2], 0);
    CHECK_EQ(src[FUSION_AXIS_RX], 0);
    CHECK_EQ(src[FUSION_AXIS_RY], 0);
}

static void test_fusion_button_union(void)
{
    struct fusion_state fs;
    struct captured cap;
    int usb;
    int bt;

    fusion_defaults(&fs);
    usb = fusion_pad_index(&fs, "0b05:1b21:usb");
    CHECK_EQ(usb, -1);
    CHECK(fusion_pad_add(&fs, "0b05:1b21:usb", "8BitDo USB", FUSION_CONN_USB,
                         1000) != NULL);
    CHECK(fusion_pad_add(&fs, "0b05:1b21:bt", "8BitDo BT", FUSION_CONN_BT,
                         1000) != NULL);
    CHECK_EQ(fs.pad_count, 2);
    flush_prime(&fs);

    usb = fusion_pad_index(&fs, "0b05:1b21:usb");
    bt = fusion_pad_index(&fs, "0b05:1b21:bt");
    CHECK(usb >= 0);
    CHECK(bt >= 0);

    /* Union: A on one pad, B on the other -> both pressed, one SYN. */
    fusion_key_event(&fs, usb, BTN_A, 1);
    fusion_key_event(&fs, bt, BTN_B, 1);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 1));
    CHECK(has_event(&cap, EV_KEY, BTN_B, 1));
    CHECK_EQ(count_type(&cap, EV_SYN), 1);
    CHECK_EQ(count_type(&cap, EV_KEY), 2);

    /* Releasing one pad's button must not release the other pad's. */
    fusion_key_event(&fs, bt, BTN_B, 0);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_B, 0));
    CHECK_EQ(count_type(&cap, EV_KEY), 1);

    /* The same button on both pads stays pressed until both release. */
    fusion_key_event(&fs, usb, BTN_A, 0);
    fusion_key_event(&fs, bt, BTN_A, 1);
    flush_into(&fs, &cap);
    CHECK_EQ(count_type(&cap, EV_KEY), 0); /* A was already down */

    /* A second flush with no input emits nothing at all. */
    flush_into(&fs, &cap);
    CHECK_EQ(cap.count, 0);
}

static void test_fusion_axis_resolution(void)
{
    struct fusion_state fs;
    struct captured cap;
    int p0;
    int p1;

    fusion_defaults(&fs);
    fusion_pad_add(&fs, "0b05:1b21:usb", "pad 0", FUSION_CONN_USB, 1000);
    fusion_pad_add(&fs, "0b05:1b21:bt", "pad 1", FUSION_CONN_BT, 1000);
    flush_prime(&fs);
    p0 = fusion_pad_index(&fs, "0b05:1b21:usb");
    p1 = fusion_pad_index(&fs, "0b05:1b21:bt");

    /* Most recent non-resting value wins. */
    fusion_abs_event(&fs, p0, FUSION_AXIS_LX, 1000, 2000);
    fusion_abs_event(&fs, p1, FUSION_AXIS_LX, 2000, 2001);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_X, 2000));

    fusion_abs_event(&fs, p0, FUSION_AXIS_LX, 3000, 2002);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_X, 3000));

    /* p0 returns to rest: p1's older deflection is still the newest
     * *non-resting* value, so the merge must fall back to it. */
    fusion_abs_event(&fs, p0, FUSION_AXIS_LX, 0, 2003);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_X, 2000));

    /* Nothing deflected any more -> rest. */
    fusion_abs_event(&fs, p1, FUSION_AXIS_LX, 0, 2004);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_X, 0));

    /* Triggers are the maximum of the pads, not the newest. */
    fusion_abs_event(&fs, p0, FUSION_AXIS_L2, 100, 2005);
    fusion_abs_event(&fs, p1, FUSION_AXIS_L2, 200, 2006);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_BRAKE, 200));

    fusion_abs_event(&fs, p1, FUSION_AXIS_L2, 50, 2007);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_BRAKE, 100));

    /* Range clamping: a wild pad value cannot break the advertised range. */
    fusion_abs_event(&fs, p0, FUSION_AXIS_L2, 9999, 2008);
    fusion_abs_event(&fs, p0, FUSION_AXIS_LX, 99999, 2009);
    fusion_abs_event(&fs, p0, FUSION_AXIS_HATX, 7, 2010);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_BRAKE, 255));
    CHECK(has_event(&cap, EV_ABS, ABS_X, 32767));
    CHECK(has_event(&cap, EV_ABS, ABS_HAT0X, 1));
}

static void test_fusion_axis_scaling(void)
{
    struct fusion_state fs;
    struct captured cap;
    int pad;

    fusion_defaults(&fs);
    fusion_pad_add(&fs, "0b05:1b21:bt", "bt pad", FUSION_CONN_BT, 1000);
    pad = fusion_pad_index(&fs, "0b05:1b21:bt");
    CHECK(pad >= 0);
    /* A pad that does not speak the uinput range: -127..127 sticks and
     * 0..1023 triggers.  Clamping would give ~0.4 % of stick travel and a
     * full-scale trigger at the first touch. */
    fusion_pad_set_axis_range(&fs, pad, FUSION_AXIS_LX, -127, 127);
    fusion_pad_set_axis_range(&fs, pad, FUSION_AXIS_L2, 0, 1023);
    flush_prime(&fs);

    fusion_abs_event(&fs, pad, FUSION_AXIS_LX, 127, 2000);
    fusion_abs_event(&fs, pad, FUSION_AXIS_L2, 1023, 2001);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_X, 32767));
    CHECK(has_event(&cap, EV_ABS, ABS_BRAKE, 255));

    fusion_abs_event(&fs, pad, FUSION_AXIS_LX, -127, 2002);
    fusion_abs_event(&fs, pad, FUSION_AXIS_L2, 0, 2003);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_X, -32768));
    CHECK(has_event(&cap, EV_ABS, ABS_BRAKE, 0));

    /* Rest maps to rest, so the merge's "non-resting" rule still works. */
    fusion_abs_event(&fs, pad, FUSION_AXIS_LX, 0, 2004);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_X, 0));

    /* An identity pad is passed through unscaled (no rounding drift). */
    fusion_pad_set_axis_range(&fs, pad, FUSION_AXIS_LY, -32768, 32767);
    fusion_abs_event(&fs, pad, FUSION_AXIS_LY, 12345, 2005);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_ABS, ABS_Y, 12345));

    /* last_seen drives the FUSION STATUS age for key-only activity too. */
    fs.pads[pad].last_seen_ms = 0;
    fusion_key_event(&fs, pad, BTN_A, 1);
    CHECK(fs.pads[pad].last_seen_ms > 0);
}

static void test_fusion_remap(void)
{
    struct fusion_state fs;
    struct captured cap;
    struct fusion_remap map[FUSION_MAX_REMAP];
    int pad;

    fusion_defaults(&fs);
    fusion_pad_add(&fs, "0b05:1b21:usb", "pad", FUSION_CONN_USB, 1000);
    pad = fusion_pad_index(&fs, "0b05:1b21:usb");
    flush_prime(&fs);

    /* DEFAULT/XBOX are identity. */
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_DEFAULT, BTN_A), BTN_A);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_XBOX, BTN_A), BTN_A);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_NINTENDO, BTN_A), BTN_B);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_NINTENDO, BTN_B), BTN_A);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_NINTENDO, BTN_X), BTN_Y);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_NINTENDO, BTN_Y), BTN_X);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_SWAP_AB, BTN_X), BTN_X);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_SWAP_XY, BTN_A), BTN_A);
    CHECK_EQ(fusion_layout_code(FUSION_LAYOUT_SWAP_XY, BTN_Y), BTN_X);

    /* NINTENDO swaps both face-button pairs before the merge. */
    fusion_key_event(&fs, pad, BTN_A, 1);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 1));

    CHECK(fusion_set_layout(&fs, "0b05:1b21:usb", FUSION_LAYOUT_NINTENDO));
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 0));
    CHECK(has_event(&cap, EV_KEY, BTN_B, 1));

    /* SWAP_XY only touches X/Y. */
    CHECK(fusion_set_layout(&fs, "0b05:1b21:usb", FUSION_LAYOUT_SWAP_XY));
    fusion_key_event(&fs, pad, BTN_X, 1);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_Y, 1));

    /* Custom from->to map (304 = BTN_A, 305 = BTN_B). */
    CHECK(fusion_set_layout(&fs, "0b05:1b21:usb", FUSION_LAYOUT_CUSTOM));
    CHECK(fusion_set_remap(&fs, "0b05:1b21:usb", "304->305,305->304"));
    CHECK_EQ(fs.config_count, 1);
    CHECK_EQ(fs.configs[0].map_count, 2);
    CHECK_EQ(fusion_map_code(&fs.pads[pad], BTN_A), BTN_B);
    CHECK_EQ(fusion_map_code(&fs.pads[pad], BTN_B), BTN_A);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 0));
    CHECK(has_event(&cap, EV_KEY, BTN_B, 1));

    /* A pad that has no custom map for a code keeps it. */
    CHECK_EQ(fusion_map_code(&fs.pads[pad], BTN_START), BTN_START);

    /* Garbage maps are ignored, never fatal. */
    CHECK_EQ(fusion_parse_remap_list("foo->bar", map, FUSION_MAX_REMAP), 0);
    CHECK_EQ(fusion_parse_remap_list("304->", map, FUSION_MAX_REMAP), 0);
    CHECK_EQ(fusion_parse_remap_list("304->305,", map, FUSION_MAX_REMAP), 1);
    CHECK_EQ(fusion_parse_remap_list("0x130->0x131", map, FUSION_MAX_REMAP), 1);
    CHECK_EQ(map[0].from, BTN_A);
    CHECK_EQ(map[0].to, BTN_B);
    CHECK_EQ(fusion_parse_remap_list("304->304", map, FUSION_MAX_REMAP), 0);
    CHECK_EQ(fusion_parse_remap_list("99999->305", map, FUSION_MAX_REMAP), 0);
}

static void test_fusion_topology_and_grab(void)
{
    struct fusion_state fs;
    struct captured cap;
    int pad;

    fusion_defaults(&fs);
    fusion_pad_add(&fs, "0b05:1b21:usb", "pad", FUSION_CONN_USB, 1000);
    pad = fusion_pad_index(&fs, "0b05:1b21:usb");
    flush_prime(&fs);

    fusion_key_event(&fs, pad, BTN_A, 1);
    fusion_abs_event(&fs, pad, FUSION_AXIS_LX, 12000, 2000);
    fusion_abs_event(&fs, pad, FUSION_AXIS_L2, 100, 2001);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 1));
    CHECK(has_event(&cap, EV_ABS, ABS_X, 12000));

    /* GRAB ON / fusion off: release everything still reported, one SYN. */
    memset(&cap, 0, sizeof(cap));
    CHECK(fusion_release(&fs, capture, &cap) > 0);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 0));
    CHECK(has_event(&cap, EV_ABS, ABS_X, 0));
    CHECK(has_event(&cap, EV_ABS, ABS_BRAKE, 0));
    CHECK_EQ(count_type(&cap, EV_SYN), 1);
    /* Nothing is left reported, so a second release emits nothing. */
    flush_into(&fs, &cap); /* also DIRTY-marks every axis again */
    CHECK(has_event(&cap, EV_ABS, ABS_X, 12000));

    /* A topology change re-reports the full state: the uinput device was
     * (re)created, so keys and axes are sent from scratch. */
    fusion_reset_reported(&fs);
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 1));
    CHECK(has_event(&cap, EV_ABS, ABS_X, 12000));
    CHECK(has_event(&cap, EV_ABS, ABS_BRAKE, 100));
    CHECK_EQ(count_type(&cap, EV_ABS), FUSION_AXIS_COUNT);
    CHECK_EQ(count_type(&cap, EV_SYN), 1);

    /* Removing the pad releases its keys while another pad's stay. */
    fusion_pad_add(&fs, "0b05:1b21:bt", "pad 2", FUSION_CONN_BT, 1000);
    {
        int other = fusion_pad_index(&fs, "0b05:1b21:bt");

        fusion_key_event(&fs, other, BTN_B, 1);
    }
    flush_prime(&fs);
    CHECK(fusion_pad_remove(&fs, "0b05:1b21:usb"));
    flush_into(&fs, &cap);
    CHECK(has_event(&cap, EV_KEY, BTN_A, 0));
    CHECK(!has_event(&cap, EV_KEY, BTN_B, 0));
    CHECK_EQ(fs.pad_count, 1);

    /* A refresh alone re-sends the axes but no key deltas. */
    fusion_refresh(&fs);
    flush_into(&fs, &cap);
    CHECK_EQ(count_type(&cap, EV_ABS), FUSION_AXIS_COUNT);
    CHECK_EQ(count_type(&cap, EV_KEY), 0);

    fusion_clear_pads(&fs);
    CHECK_EQ(fs.pad_count, 0);
}

static void test_fusion_settings(void)
{
    struct fusion_state fs;
    struct fusion_pad_config parsed[FUSION_MAX_PADS];

    unsetenv(PROP_FUSION);
    unsetenv(PROP_FUSION_LAYOUTS);
    unsetenv(PROP_FUSION_REMAP);
    fusion_defaults(&fs);
    fusion_settings_read(&fs);
    CHECK(!fs.enabled); /* off by default (#138 / docs/phase6.md §9.7) */
    CHECK_EQ(fs.config_count, 0);

    setenv(PROP_FUSION, "1", 1);
    fusion_settings_read(&fs);
    CHECK(fs.enabled);

    /* Garbage falls back to the default instead of crashing. */
    setenv(PROP_FUSION, "-1", 1);
    fusion_settings_read(&fs);
    CHECK(!fs.enabled);
    setenv(PROP_FUSION, "yes", 1);
    fusion_settings_read(&fs);
    CHECK(!fs.enabled);
    setenv(PROP_FUSION, "1", 1);

    /* name=LAYOUT;name=LAYOUT, space-free pad keys. */
    CHECK_EQ(fusion_parse_layouts("0b05:1b21:usb=NINTENDO", parsed,
                                  FUSION_MAX_PADS),
             1);
    CHECK(strcmp(parsed[0].key, "0b05:1b21:usb") == 0);
    CHECK_EQ(parsed[0].layout, FUSION_LAYOUT_NINTENDO);
    CHECK_EQ(fusion_parse_layouts("a:1:usb=NINTENDO;bad;b:2:bt=XBOX", parsed,
                                  FUSION_MAX_PADS),
             2);
    CHECK_EQ(parsed[1].layout, FUSION_LAYOUT_XBOX);
    /* Unknown modes and malformed keys are skipped. */
    CHECK_EQ(fusion_parse_layouts("a:1:usb=WAT", parsed, FUSION_MAX_PADS), 0);
    CHECK_EQ(fusion_parse_layouts("a b=NINTENDO", parsed, FUSION_MAX_PADS), 0);
    CHECK_EQ(fusion_parse_layouts("=NINTENDO", parsed, FUSION_MAX_PADS), 0);

    /* name=304->305,305->304;... */
    CHECK_EQ(fusion_parse_remap("a:1:usb=304->305,305->304", parsed,
                                FUSION_MAX_PADS),
             1);
    CHECK_EQ(parsed[0].map_count, 2);
    CHECK_EQ(parsed[0].map[1].from, BTN_B);
    CHECK_EQ(fusion_parse_remap("a:1:usb=garbage", parsed, FUSION_MAX_PADS), 0);
    /* A malformed entry is skipped without shifting the valid ones. */
    memset(parsed, 0, sizeof(parsed));
    CHECK_EQ(fusion_parse_remap("a:1:usb=;b:2:usb=304->305", parsed,
                                FUSION_MAX_PADS),
             1);
    CHECK(strcmp(parsed[0].key, "b:2:usb") == 0);
    CHECK_EQ(parsed[0].map_count, 1);

    /* Live: a configured pad picks its layout and custom map up on arrival.
     * Both properties name the same pad and must merge into one entry. */
    setenv(PROP_FUSION_LAYOUTS, "0b05:1b21:usb=NINTENDO", 1);
    setenv(PROP_FUSION_REMAP, "0b05:1b21:usb=304->305", 1);
    fusion_settings_read(&fs);
    CHECK_EQ(fs.config_count, 1);
    fusion_pad_add(&fs, "0b05:1b21:usb", "pad", FUSION_CONN_USB, 1000);
    {
        int pad = fusion_pad_index(&fs, "0b05:1b21:usb");

        CHECK(pad >= 0);
        CHECK_EQ(fs.pads[pad].layout, FUSION_LAYOUT_NINTENDO);
        CHECK_EQ(fs.pads[pad].map_count, 1);
    }

    /* Only non-default entries are serialised, one per pad. */
    {
        char layouts[256];
        char remap[256];

        fusion_set_layout(&fs, "2020:3001:mcu", FUSION_LAYOUT_SWAP_AB);
        fusion_format_settings(&fs, layouts, sizeof(layouts), remap,
                              sizeof(remap));
        CHECK(strstr(layouts, "0b05:1b21:usb=NINTENDO") != NULL);
        CHECK(strstr(layouts, "2020:3001:mcu=SWAP_AB") != NULL);
        CHECK(strstr(remap, "0b05:1b21:usb=304->305") != NULL);

        /* A default layout is not serialised at all. */
        fusion_set_layout(&fs, "0b05:1b21:usb", FUSION_LAYOUT_DEFAULT);
        fusion_format_settings(&fs, layouts, sizeof(layouts), remap,
                              sizeof(remap));
        CHECK(strstr(layouts, "0b05:1b21:usb") == NULL);
        CHECK(strstr(layouts, "2020:3001:mcu=SWAP_AB") != NULL);
    }

    /* The app owns the properties: a poll with unchanged text must not undo a
     * live FUSION command. */
    CHECK(fusion_set_layout(&fs, "2020:3001:mcu", FUSION_LAYOUT_SWAP_XY));
    fusion_settings_read(&fs);
    CHECK_EQ(fusion_pad_index(&fs, "2020:3001:mcu") >= 0, 0);
    CHECK_EQ(fs.config_count, 2);
    CHECK_EQ(fs.configs[0].layout, FUSION_LAYOUT_DEFAULT);
    CHECK_EQ(fs.configs[1].layout, FUSION_LAYOUT_SWAP_XY);

    /* Bad keys/layouts are rejected, not applied. */
    CHECK(!fusion_set_layout(&fs, "", FUSION_LAYOUT_XBOX));
    CHECK(!fusion_set_layout(&fs, "x", FUSION_LAYOUT_COUNT));
    CHECK(!fusion_set_remap(&fs, "", "304->305"));

    unsetenv(PROP_FUSION);
    unsetenv(PROP_FUSION_LAYOUTS);
    unsetenv(PROP_FUSION_REMAP);
}

static void test_fusion_status(void)
{
    struct fusion_state fs;
    char text[1024];

    fusion_defaults(&fs);
    CHECK(fusion_status_format(&fs, 1000, text, sizeof(text)) > 0);
    CHECK(strncmp(text, "OK FUSION 0 PADS 0\n", 19) == 0);

    fs.enabled = true;
    fusion_pad_add(&fs, "2020:3001:mcu", "RP5 internal (MCU)",
                   FUSION_CONN_MCU, 1000);
    fusion_pad_add(&fs, "0b05:1b21:usb", "8BitDo Ultimate 2C",
                   FUSION_CONN_USB, 500);
    fusion_set_layout(&fs, "0b05:1b21:usb", FUSION_LAYOUT_NINTENDO);
    CHECK(fusion_status_format(&fs, 1500, text, sizeof(text)) > 0);
    CHECK(strncmp(text, "OK FUSION 1 PADS 2\n", 19) == 0);
    CHECK(strstr(text, "PAD 2020:3001:mcu DEFAULT 500 RP5 internal (MCU)\n") !=
          NULL);
    /* The name is the rest of the line, spaces included. */
    CHECK(strstr(text, "PAD 0b05:1b21:usb NINTENDO 1000 8BitDo Ultimate 2C\n") !=
          NULL);

    /* A kernel-provided name cannot break the framing. */
    memset(&fs.pads[0].name, 0, sizeof(fs.pads[0].name));
    snprintf(fs.pads[0].name, sizeof(fs.pads[0].name), "bad\nname");
    fusion_status_format(&fs, 1000, text, sizeof(text));
    CHECK(strstr(text, "bad name") != NULL);
}

/* --- #156: uinput capability/device-setup construction ------------------- */

#define CALL_MAX 160

struct uinput_call {
    unsigned long request;
    unsigned long arg;
};

/*
 * A fake kernel: it records the sequence and, optionally, enforces the two
 * rules that made the fusion device fail on the device -
 *
 *   drivers/input/misc/uinput.c: UI_DEV_CREATE rejects EV_FF without
 *   ff_effects_max, and an unsupported FF bit is refused at UI_SET_FFBIT.
 *
 * so the test fails if the daemon ever sends the sequence #156 shipped.
 */
struct uinput_fake {
    int opens;
    int closes;
    int fd;
    bool ffbit_supported;
    bool enforce_ff_rule;
    bool ff_set; /* EV_FF seen on the current fd */
    struct uinput_call calls[CALL_MAX];
    int call_count;
    struct uinput_setup setup;
    int creates;
};

static int fake_open(void *ctx)
{
    struct uinput_fake *fake = ctx;

    fake->opens++;
    fake->fd = 64 + fake->opens;
    fake->ff_set = false;
    return fake->fd;
}

static void fake_record(struct uinput_fake *fake, unsigned long request,
                        unsigned long arg)
{
    if (fake->call_count >= CALL_MAX)
        return;
    fake->calls[fake->call_count].request = request;
    fake->calls[fake->call_count].arg = arg;
    fake->call_count++;
}

static int fake_ioctl(void *ctx, int fd, unsigned long request,
                      unsigned long arg)
{
    struct uinput_fake *fake = ctx;

    (void)fd;
    fake_record(fake, request, arg);

    switch (request) {
    case UI_SET_EVBIT:
        if ((int)arg == EV_FF) {
            if (!fake->ffbit_supported) {
                errno = EINVAL;
                return -1;
            }
            fake->ff_set = true;
        }
        return 0;
    case UI_SET_FFBIT:
        if (!fake->ffbit_supported) {
            errno = EINVAL;
            return -1;
        }
        return 0;
    case UI_DEV_SETUP:
        memcpy(&fake->setup, (const void *)(uintptr_t)arg,
               sizeof(fake->setup));
        return 0;
    case UI_DEV_CREATE:
        fake->creates++;
        if (fake->enforce_ff_rule && fake->ff_set &&
            fake->setup.ff_effects_max == 0) {
            errno = EINVAL;
            return -1;
        }
        return 0;
    default:
        return 0;
    }
}

static int fake_write(void *ctx, int fd, const void *buf, size_t len)
{
    struct uinput_fake *fake = ctx;

    (void)fd;
    (void)buf;
    (void)len;
    fake_record(fake, 0xdeadbeef, 0);
    return 0;
}

static void fake_close(void *ctx, int fd)
{
    struct uinput_fake *fake = ctx;

    (void)fd;
    fake->closes++;
}

static const struct uinput_dev_ops fake_ops = {
    .ctx = NULL, /* filled in per test */
    .open = fake_open,
    .ioctl = fake_ioctl,
    .write = fake_write,
    .close = fake_close,
};

/* First call index for a request, or -1. */
static int call_index(const struct uinput_fake *fake, unsigned long request)
{
    for (int i = 0; i < fake->call_count; i++) {
        if (fake->calls[i].request == request)
            return i;
    }
    return -1;
}

static int call_count_of(const struct uinput_fake *fake, unsigned long request,
                         unsigned long arg)
{
    int count = 0;

    for (int i = 0; i < fake->call_count; i++) {
        if (fake->calls[i].request == request && fake->calls[i].arg == arg)
            count++;
    }
    return count;
}

static void fake_init(struct uinput_fake *fake, struct uinput_dev_ops *ops)
{
    memset(fake, 0, sizeof(*fake));
    fake->ffbit_supported = true;
    fake->enforce_ff_rule = true;
    *ops = fake_ops;
    ops->ctx = fake;
}

/* The full fusion capability set, exactly as the daemon advertises it. */
static void fusion_spec(struct uinput_dev_spec *spec)
{
    memset(spec, 0, sizeof(*spec));
    spec->name = FUSION_NAME_ON;
    spec->bustype = BUS_USB;
    spec->vendor = FUSION_VID;
    spec->product = FUSION_PID;
    spec->version = 1;
    spec->keys = fusion_key_caps;
    spec->key_count = fusion_key_cap_count;
    spec->axes = fusion_axis_codes;
    spec->axis_count = FUSION_AXIS_COUNT;
    spec->rumble = true;
}

static void test_uinput_fusion_caps(void)
{
    struct uinput_dev_spec spec;
    struct uinput_dev_ops ops;
    struct uinput_fake fake;
    bool rumble = false;
    int fd;

    fake_init(&fake, &ops);
    fusion_spec(&spec);
    fd = uinput_dev_create(&ops, &spec, &rumble);

    CHECK(fd >= 0);
    CHECK(rumble);
    CHECK_EQ(fake.opens, 1);
    CHECK_EQ(fake.creates, 1);

    /* Event types first, then key bits, then FF, then axes and only then the
     * device setup the kernel reads ff_effects_max from. */
    CHECK_EQ(fake.calls[0].request, UI_SET_EVBIT);
    CHECK_EQ(fake.calls[0].arg, EV_KEY);
    CHECK(call_index(&fake, UI_SET_KEYBIT) < call_index(&fake, UI_SET_FFBIT));
    CHECK(call_index(&fake, UI_SET_FFBIT) < call_index(&fake, UI_DEV_SETUP));
    CHECK(call_index(&fake, UI_DEV_SETUP) < call_index(&fake, UI_DEV_CREATE));
    CHECK(call_index(&fake, UI_DEV_CREATE) == fake.call_count - 1);
    CHECK(call_index(&fake, UI_ABS_SETUP) < call_index(&fake, UI_DEV_SETUP));

    /* Force feedback: EV_FF + FF_RUMBLE, and the non-zero effect count that
     * UI_DEV_CREATE requires (the #156 EINVAL). */
    CHECK_EQ(call_count_of(&fake, UI_SET_EVBIT, EV_FF), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_FFBIT, FF_RUMBLE), 1);
    CHECK(fake.setup.ff_effects_max == UINPUT_DEV_FF_EFFECTS);
    CHECK(fake.setup.ff_effects_max > 0);

    /* Identity: the well-known Xbox pad, so apps bind their gamepad profile. */
    CHECK(strcmp(fake.setup.name, FUSION_NAME_ON) == 0);
    CHECK_EQ(fake.setup.id.vendor, FUSION_VID);
    CHECK_EQ(fake.setup.id.product, FUSION_PID);
    CHECK_EQ(fake.setup.id.bustype, BUS_USB);

    /* Every advertised key and axis is set, hats/triggers with their ranges. */
    CHECK_EQ(call_count_of(&fake, UI_SET_KEYBIT, BTN_A), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_KEYBIT, BTN_MODE), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_ABSBIT, ABS_HAT0X), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_ABSBIT, ABS_BRAKE), 1);
    for (int i = 0; i < spec.key_count; i++) {
        if (spec.keys[i] == 0)
            continue;
        CHECK_EQ(call_count_of(&fake, UI_SET_KEYBIT, spec.keys[i]), 1);
    }
}

static void test_uinput_phase5_device(void)
{
    static const uint16_t keys[] = {
        /* The MCU keymap has holes for the D-pad bits: a 0 entry must never
         * reach the kernel. */
        BTN_TL, BTN_TR, BTN_A, 0, KEY_HOMEPAGE, KEY_BACK,
    };
    struct uinput_dev_spec spec;
    struct uinput_dev_ops ops;
    struct uinput_fake fake;
    bool rumble = true;
    int fd;

    fake_init(&fake, &ops);
    memset(&spec, 0, sizeof(spec));
    spec.name = FUSION_NAME_OFF;
    spec.bustype = BUS_USB;
    spec.vendor = FUSION_MCU_VID;
    spec.product = FUSION_MCU_PID;
    spec.version = 1;
    spec.keys = keys;
    spec.key_count = (int)(sizeof(keys) / sizeof(keys[0]));
    spec.axes = fusion_axis_codes;
    spec.axis_count = FUSION_AXIS_COUNT;
    spec.rumble = false;

    fd = uinput_dev_create(&ops, &spec, &rumble);
    CHECK(fd >= 0);
    /* Fusion off is the phase-5 device: no EV_FF, no FF bits, no effect
     * slots.  It must stay byte-for-byte the device apps already know. */
    CHECK(!rumble);
    CHECK_EQ(fake.creates, 1);
    CHECK_EQ(call_index(&fake, UI_DEV_SETUP) >= 0, 1);
    CHECK(call_count_of(&fake, UI_SET_EVBIT, EV_FF) == 0);
    CHECK(call_count_of(&fake, UI_SET_FFBIT, FF_RUMBLE) == 0);
    CHECK(call_count_of(&fake, UI_SET_EVBIT, EV_REL) == 0);
    CHECK(call_count_of(&fake, UI_SET_PROPBIT, INPUT_PROP_POINTER) == 0);
    /* Holes in the keymap are skipped, never set as code 0. */
    CHECK(call_count_of(&fake, UI_SET_KEYBIT, 0) == 0);
    CHECK(call_count_of(&fake, UI_SET_KEYBIT, BTN_A) == 1);
    CHECK_EQ(fake.setup.ff_effects_max, 0);
    CHECK(strcmp(fake.setup.name, FUSION_NAME_OFF) == 0);
    CHECK_EQ(fake.setup.id.vendor, FUSION_MCU_VID);
    CHECK_EQ(fake.setup.id.product, FUSION_MCU_PID);
}

static void test_uinput_ff_refused(void)
{
    struct uinput_dev_spec spec;
    struct uinput_dev_ops ops;
    struct uinput_fake fake;
    bool rumble = true;
    int fd;

    /* A kernel that does not take EV_FF: the pad must still be created, with
     * rumble honestly reported as unavailable. */
    fake_init(&fake, &ops);
    fake.ffbit_supported = false;
    fusion_spec(&spec);

    fd = uinput_dev_create(&ops, &spec, &rumble);
    CHECK(fd >= 0);
    CHECK(!rumble);
    CHECK_EQ(fake.opens, 2); /* first attempt without FF is closed again */
    CHECK_EQ(fake.closes, 1);
    CHECK_EQ(fake.creates, 1);
    CHECK_EQ(fake.setup.ff_effects_max, 0);
    CHECK(strcmp(fake.setup.name, FUSION_NAME_ON) == 0);
}

static void test_uinput_axis_range(void)
{
    int min = 0;
    int max = 0;

    uinput_dev_axis_range(ABS_BRAKE, &min, &max);
    CHECK_EQ(min, 0);
    CHECK_EQ(max, 255);
    uinput_dev_axis_range(ABS_GAS, &min, &max);
    CHECK_EQ(max, 255);
    uinput_dev_axis_range(ABS_HAT0X, &min, &max);
    CHECK_EQ(min, -1);
    CHECK_EQ(max, 1);
    uinput_dev_axis_range(ABS_HAT0Y, &min, &max);
    CHECK_EQ(min, -1);
    uinput_dev_axis_range(ABS_X, &min, &max);
    CHECK_EQ(min, -32768);
    CHECK_EQ(max, 32767);
    uinput_dev_axis_range(ABS_RZ, &min, &max);
    CHECK_EQ(max, 32767);
}

/* --- #156: external pad detection and hotplug ---------------------------- */

static void test_fusion_node_mergeable(void)
{
    struct fusion_node node;
    char key[FUSION_KEY_MAX];

    memset(&node, 0, sizeof(node));
    node.vendor = 0x0b05;
    node.product = 0x1b21;
    node.connection = FUSION_CONN_USB;
    node.name = "8BitDo Ultimate 2C";
    node.gamepad = true;
    CHECK(fusion_node_mergeable(&node, key, sizeof(key)));
    CHECK(strcmp(key, "0b05:1b21:usb") == 0);

    /* The same pad over Bluetooth keeps its own stable key. */
    node.connection = FUSION_CONN_BT;
    CHECK(fusion_node_mergeable(&node, key, sizeof(key)));
    CHECK(strcmp(key, "0b05:1b21:bt") == 0);

    /* Our own uinput device is never a pad, in either identity. */
    node.vendor = FUSION_VID;
    node.product = FUSION_PID;
    node.name = FUSION_NAME_ON;
    CHECK(!fusion_node_mergeable(&node, key, sizeof(key)));
    CHECK_EQ(key[0], '\0');
    node.vendor = FUSION_MCU_VID;
    node.product = FUSION_MCU_PID;
    node.name = FUSION_NAME_OFF;
    CHECK(!fusion_node_mergeable(&node, key, sizeof(key)));

    /* Motion-only and key-only nodes stay with their own subsystems. */
    node.vendor = 0x0001;
    node.product = 0x0001;
    node.name = "accelerometer";
    node.motion_only = true;
    CHECK(!fusion_node_mergeable(&node, key, sizeof(key)));
    node.motion_only = false;
    node.gamepad = false;
    CHECK(!fusion_node_mergeable(&node, key, sizeof(key)));

    /* A short buffer truncates the key but never overflows it. */
    node.gamepad = true;
    node.name = "pad";
    memset(key, 'x', sizeof(key));
    CHECK(fusion_node_mergeable(&node, key, 6));
    CHECK_EQ(strlen(key), 5);
    CHECK(fusion_node_mergeable(&node, NULL, 0));
}

static void test_fusion_hotplug(void)
{
    /* A pad that appears or disappears is a rescan now, not on the next
     * 2 s tick. */
    CHECK(fusion_hotplug_scan(IN_CREATE, "event7"));
    CHECK(fusion_hotplug_scan(IN_DELETE, "event7"));
    CHECK(fusion_hotplug_scan(IN_ATTRIB, "event12"));
    CHECK(fusion_hotplug_scan(IN_MOVED_FROM, "event3"));
    CHECK(fusion_hotplug_scan(IN_MOVED_TO, "event3"));
    /* Queue overflow and directory-level changes: look again. */
    CHECK(fusion_hotplug_scan(IN_Q_OVERFLOW, ""));
    CHECK(fusion_hotplug_scan(IN_CREATE, ""));
    CHECK(fusion_hotplug_scan(IN_CREATE, NULL));
    /* Nodes that are not event devices, and events we do not watch. */
    CHECK(!fusion_hotplug_scan(IN_CREATE, "mouse0"));
    CHECK(!fusion_hotplug_scan(IN_CREATE, "js0"));
    CHECK(!fusion_hotplug_scan(IN_IGNORED, "event7"));
    CHECK(!fusion_hotplug_scan(IN_OPEN, "event7"));
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

/* --- #131: desktop-mode pointer ------------------------------------------ */

#define CAPTURE_MAX 512

struct pointer_capture {
    int count;
    uint16_t type[CAPTURE_MAX];
    uint16_t code[CAPTURE_MAX];
    int32_t value[CAPTURE_MAX];
};

static void capture_emit(void *ctx, uint16_t type, uint16_t code, int32_t value)
{
    struct pointer_capture *cap = ctx;

    if (cap->count >= CAPTURE_MAX)
        return;
    cap->type[cap->count] = type;
    cap->code[cap->count] = code;
    cap->value[cap->count] = value;
    cap->count++;
}

static int capture_count(const struct pointer_capture *cap, uint16_t type,
                         uint16_t code, int32_t value)
{
    int count = 0;

    for (int i = 0; i < cap->count; i++) {
        if (cap->type[i] == type && cap->code[i] == code &&
            cap->value[i] == value)
            count++;
    }
    return count;
}

static int capture_sum(const struct pointer_capture *cap, uint16_t type,
                       uint16_t code)
{
    int sum = 0;

    for (int i = 0; i < cap->count; i++) {
        if (cap->type[i] == type && cap->code[i] == code)
            sum += cap->value[i];
    }
    return sum;
}

static int capture_max_abs(const struct pointer_capture *cap, uint16_t type,
                           uint16_t code)
{
    int best = 0;

    for (int i = 0; i < cap->count; i++) {
        int value;

        if (cap->type[i] != type || cap->code[i] != code)
            continue;
        value = cap->value[i] < 0 ? -cap->value[i] : cap->value[i];
        if (value > best)
            best = value;
    }
    return best;
}

static void test_pointer_mode(void)
{
    struct pointer_state ps;
    enum pointer_mode mode = POINTER_MODE_DESKTOP;

    pointer_defaults(&ps);
    CHECK_EQ(ps.mode, POINTER_MODE_GAME);
    CHECK(pointer_mode_parse("desktop", &mode));
    CHECK_EQ(mode, POINTER_MODE_DESKTOP);
    CHECK(pointer_mode_parse("game", &mode));
    CHECK_EQ(mode, POINTER_MODE_GAME);
    CHECK(!pointer_mode_parse("DESKTOP", &mode));
    CHECK(!pointer_mode_parse("", &mode));
    CHECK(!pointer_mode_parse(NULL, &mode));
    CHECK(strcmp(pointer_mode_name(POINTER_MODE_DESKTOP), "desktop") == 0);
    CHECK(strcmp(pointer_mode_name(POINTER_MODE_GAME), "game") == 0);

    /* Missing property: game, and the first read only adopts the default. */
    unsetenv(PROP_PAD_MODE);
    pointer_defaults(&ps);
    CHECK(!pointer_mode_read(&ps));
    CHECK_EQ(ps.mode, POINTER_MODE_GAME);
    CHECK(!pointer_mode_read(&ps));

    setenv(PROP_PAD_MODE, "desktop", 1);
    CHECK(pointer_mode_read(&ps));
    CHECK_EQ(ps.mode, POINTER_MODE_DESKTOP);
    CHECK(!pointer_mode_read(&ps));

    /* The chord switches the live mode; the property text did not change, so
     * the next 250 ms poll must not undo it. */
    CHECK(pointer_set_mode(&ps, POINTER_MODE_GAME));
    CHECK(!pointer_mode_read(&ps));
    CHECK_EQ(ps.mode, POINTER_MODE_GAME);

    /* A malformed value falls back to game; it was already game, so this is
     * not a mode change, and the adopted text stops a per-poll log. */
    setenv(PROP_PAD_MODE, "monitor", 1);
    CHECK(!pointer_mode_read(&ps));
    CHECK_EQ(ps.mode, POINTER_MODE_GAME);
    CHECK(!pointer_mode_read(&ps));

    unsetenv(PROP_PAD_MODE);
}

static void test_pointer_movement(void)
{
    struct pointer_state ps;
    struct pointer_capture cap;
    bool chord = false;
    int64_t t = 1000;
    int total;

    /* Game mode emits nothing, even with the stick at full deflection. */
    pointer_defaults(&ps);
    memset(&cap, 0, sizeof(cap));
    pointer_frame(&ps, POINTER_KEY_A | POINTER_KEY_B, 32767, 32767, 255, 255,
                  t);
    for (int i = 0; i < 20; i++)
        pointer_tick(&ps, t + (int64_t)i * POINTER_TICK_MS, capture_emit, &cap,
                     &chord);
    CHECK_EQ(cap.count, 0);
    CHECK(!ps.btn_left);
    CHECK(!ps.btn_right);
    CHECK(!ps.btn_middle);

    /* Desktop, full right deflection: the ramp reaches ~1200 px/s and never
     * exceeds it.  125 ticks is one second of motion. */
    CHECK(pointer_set_mode(&ps, POINTER_MODE_DESKTOP));
    pointer_frame(&ps, 0, 32767, 0, 0, 0, t);
    pointer_release(&ps, NULL, NULL);
    memset(&cap, 0, sizeof(cap));
    pointer_tick(&ps, t, capture_emit, &cap, &chord); /* prime the dt */
    for (int i = 1; i <= 125; i++)
        pointer_tick(&ps, t + (int64_t)i * POINTER_TICK_MS, capture_emit, &cap,
                     &chord);
    total = capture_sum(&cap, EV_REL, REL_X);
    CHECK(total > 1000);
    CHECK(total <= 1200);
    CHECK(capture_max_abs(&cap, EV_REL, REL_X) <= 10);
    CHECK_EQ(capture_sum(&cap, EV_REL, REL_Y), 0);

    /* Left deflection moves left. */
    pointer_release(&ps, NULL, NULL);
    pointer_frame(&ps, 0, -32767, 0, 0, 0, t);
    memset(&cap, 0, sizeof(cap));
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    for (int i = 1; i <= 40; i++)
        pointer_tick(&ps, t + (int64_t)i * POINTER_TICK_MS, capture_emit, &cap,
                     &chord);
    CHECK(capture_sum(&cap, EV_REL, REL_X) < 0);
}

static void test_pointer_deadzone_and_fraction(void)
{
    struct pointer_state ps;
    struct pointer_capture cap;
    bool chord = false;
    int64_t t = 1000;
    int total = 0;

    memset(&cap, 0, sizeof(cap));
    pointer_defaults(&ps);
    pointer_set_mode(&ps, POINTER_MODE_DESKTOP);

    /* 4.9 % of travel: inside the fixed 5 % deadzone, no motion at all. */
    pointer_frame(&ps, 0, 1600, 0, 0, 0, t);
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    for (int i = 1; i <= 50; i++)
        pointer_tick(&ps, t + (int64_t)i * POINTER_TICK_MS, capture_emit, &cap,
                     &chord);
    CHECK_EQ(capture_sum(&cap, EV_REL, REL_X), 0);

    /* Just above the deadzone the speed is far below 1 px/tick.  Without
     * fractional accumulation every tick would round to zero and the pointer
     * would never move. */
    pointer_release(&ps, NULL, NULL);
    pointer_frame(&ps, 0, 4000, 0, 0, 0, t);
    memset(&cap, 0, sizeof(cap));
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    for (int i = 1; i <= 100; i++)
        pointer_tick(&ps, t + (int64_t)i * POINTER_TICK_MS, capture_emit, &cap,
                     &chord);
    total = capture_sum(&cap, EV_REL, REL_X);
    CHECK(total >= 4);
    CHECK(total <= 30);
    CHECK_EQ(capture_max_abs(&cap, EV_REL, REL_X), 1);
}

static void test_pointer_buttons(void)
{
    struct pointer_state ps;
    struct pointer_capture cap;
    bool chord = false;
    int64_t t = 1000;

    memset(&cap, 0, sizeof(cap));
    pointer_defaults(&ps);
    pointer_set_mode(&ps, POINTER_MODE_DESKTOP);

    /* A = left click. */
    pointer_frame(&ps, POINTER_KEY_A, 0, 0, 0, 0, t);
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    CHECK(ps.btn_left);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 1), 1);

    /* R3 keeps the same BTN_LEFT down: A -> R3 must not click twice. */
    pointer_frame(&ps, POINTER_KEY_R3, 0, 0, 0, 0, t + 8);
    pointer_tick(&ps, t + 8, capture_emit, &cap, &chord);
    CHECK(ps.btn_left);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 1), 1);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 0), 0);

    pointer_frame(&ps, 0, 0, 0, 0, 0, t + 16);
    pointer_tick(&ps, t + 16, capture_emit, &cap, &chord);
    CHECK(!ps.btn_left);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 0), 1);

    /* B = right click. */
    pointer_frame(&ps, POINTER_KEY_B, 0, 0, 0, 0, t + 24);
    pointer_tick(&ps, t + 24, capture_emit, &cap, &chord);
    CHECK(ps.btn_right);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_RIGHT, 1), 1);
    pointer_frame(&ps, 0, 0, 0, 0, 0, t + 32);
    pointer_tick(&ps, t + 32, capture_emit, &cap, &chord);
    CHECK(!ps.btn_right);

    /* R2 = middle click, with 50 %/37.5 % hysteresis. */
    pointer_frame(&ps, 0, 0, 0, 0, 255, t + 40);
    pointer_tick(&ps, t + 40, capture_emit, &cap, &chord);
    CHECK(ps.btn_middle);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_MIDDLE, 1), 1);
    pointer_frame(&ps, 0, 0, 0, 0, 100, t + 48);
    pointer_tick(&ps, t + 48, capture_emit, &cap, &chord);
    CHECK(ps.btn_middle);
    pointer_frame(&ps, 0, 0, 0, 0, 90, t + 56);
    pointer_tick(&ps, t + 56, capture_emit, &cap, &chord);
    CHECK(!ps.btn_middle);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_MIDDLE, 0), 1);

    /* L2 = drag: hold BTN_LEFT for as long as the trigger is pulled. */
    pointer_frame(&ps, 0, 0, 0, 255, 0, t + 64);
    pointer_tick(&ps, t + 64, capture_emit, &cap, &chord);
    CHECK(ps.btn_left);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 1), 2);
    pointer_frame(&ps, 0, 0, 0, 100, 0, t + 72);
    pointer_tick(&ps, t + 72, capture_emit, &cap, &chord);
    CHECK(ps.btn_left);
    pointer_frame(&ps, 0, 0, 0, 90, 0, t + 80);
    pointer_tick(&ps, t + 80, capture_emit, &cap, &chord);
    CHECK(!ps.btn_left);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 0), 2);

    /* A change is followed by exactly one SYN in that tick. */
    CHECK(capture_count(&cap, EV_SYN, SYN_REPORT, 0) >= 1);
}

static void test_pointer_wheel(void)
{
    struct pointer_state ps;
    struct pointer_capture cap;
    bool chord = false;
    int64_t t = 1000;

    memset(&cap, 0, sizeof(cap));
    pointer_defaults(&ps);
    pointer_set_mode(&ps, POINTER_MODE_DESKTOP);

    /* L1: one notch on press, then 400 ms initial / 80 ms repeat. */
    pointer_frame(&ps, POINTER_KEY_L1, 0, 0, 0, 0, t);
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, 1), 1);
    pointer_tick(&ps, t + 80, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, 1), 1);
    pointer_tick(&ps, t + 392, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, 1), 1);
    pointer_tick(&ps, t + 400, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, 1), 2);
    pointer_tick(&ps, t + 480, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, 1), 3);

    pointer_frame(&ps, 0, 0, 0, 0, 0, t + 488);
    pointer_tick(&ps, t + 488, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, 1), 3);

    /* A fresh press scrolls immediately again. */
    pointer_frame(&ps, POINTER_KEY_L1, 0, 0, 0, 0, t + 496);
    pointer_tick(&ps, t + 496, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, 1), 4);

    /* R1 scrolls the other way. */
    pointer_frame(&ps, POINTER_KEY_R1, 0, 0, 0, 0, t + 504);
    pointer_tick(&ps, t + 504, capture_emit, &cap, &chord);
    CHECK_EQ(capture_count(&cap, EV_REL, REL_WHEEL, -1), 1);
}

static void test_pointer_chord(void)
{
    struct pointer_state ps;
    struct pointer_capture cap;
    bool chord = false;
    int fires = 0;
    int64_t t = 1000;

    memset(&cap, 0, sizeof(cap));
    pointer_defaults(&ps);

    /* Two of the three buttons never fire it, however long they are held. */
    pointer_frame(&ps, POINTER_KEY_L1 | POINTER_KEY_R1, 0, 0, 0, 0, t);
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    pointer_frame(&ps, POINTER_KEY_L1 | POINTER_KEY_R1, 0, 0, 0, 0, t + 1500);
    pointer_tick(&ps, t + 1500, capture_emit, &cap, &chord);
    CHECK(!chord);

    /* All three: exactly one fire after ~1 s, not one per tick. */
    pointer_frame(&ps, POINTER_CHORD_MASK, 0, 0, 0, 0, t + 2000);
    pointer_tick(&ps, t + 2000, capture_emit, &cap, &chord);
    CHECK(!chord);
    pointer_tick(&ps, t + 2999, capture_emit, &cap, &chord);
    CHECK(!chord);
    pointer_tick(&ps, t + 3000, capture_emit, &cap, &chord);
    CHECK(chord);
    fires++;
    chord = false;
    pointer_tick(&ps, t + 3008, capture_emit, &cap, &chord);
    CHECK(!chord);

    /* Released and pressed again: the chord re-arms. */
    pointer_frame(&ps, POINTER_KEY_L1 | POINTER_KEY_R1, 0, 0, 0, 0, t + 3016);
    pointer_tick(&ps, t + 3016, capture_emit, &cap, &chord);
    CHECK(!chord);
    pointer_frame(&ps, POINTER_CHORD_MASK, 0, 0, 0, 0, t + 3024);
    pointer_tick(&ps, t + 3024, capture_emit, &cap, &chord);
    pointer_tick(&ps, t + 4024, capture_emit, &cap, &chord);
    CHECK(chord);
    fires++;
    CHECK_EQ(fires, 2);
    CHECK_EQ(cap.count, 0); /* the chord itself injects nothing */
}

static void test_pointer_release(void)
{
    struct pointer_state ps;
    struct pointer_capture cap;
    bool chord = false;
    int64_t t = 1000;
    int emitted;

    memset(&cap, 0, sizeof(cap));
    pointer_defaults(&ps);
    pointer_set_mode(&ps, POINTER_MODE_DESKTOP);

    /* A + B + both triggers: three buttons down at once. */
    pointer_frame(&ps, POINTER_KEY_A | POINTER_KEY_B, 0, 0, 255, 255, t);
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    CHECK(ps.btn_left);
    CHECK(ps.btn_right);
    CHECK(ps.btn_middle);

    emitted = pointer_release(&ps, capture_emit, &cap);
    CHECK_EQ(emitted, 3);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 0), 1);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_RIGHT, 0), 1);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_MIDDLE, 0), 1);
    CHECK_EQ(capture_count(&cap, EV_SYN, SYN_REPORT, 0), 2);
    CHECK(!ps.btn_left);
    CHECK(!ps.btn_right);
    CHECK(!ps.btn_middle);
    CHECK(ps.vx == 0.0);
    CHECK(ps.frac_x == 0.0);

    /* Nothing held: no second release. */
    CHECK_EQ(pointer_release(&ps, capture_emit, &cap), 0);

    /* Silent release still clears the state (GRAB ON / shutdown path). */
    pointer_frame(&ps, POINTER_KEY_A, 0, 0, 0, 0, t + 8);
    pointer_tick(&ps, t + 8, capture_emit, &cap, &chord);
    CHECK(ps.btn_left);
    CHECK_EQ(pointer_release(&ps, NULL, NULL), 0);
    CHECK(!ps.btn_left);
}

static void test_pointer_stale_frame(void)
{
    struct pointer_state ps;
    struct pointer_capture cap;
    bool chord = false;
    int64_t t = 1000;

    memset(&cap, 0, sizeof(cap));
    pointer_defaults(&ps);
    pointer_set_mode(&ps, POINTER_MODE_DESKTOP);
    pointer_frame(&ps, POINTER_KEY_A, 32767, 0, 0, 0, t);
    pointer_tick(&ps, t, capture_emit, &cap, &chord);
    CHECK(ps.btn_left);

    /* The MCU went quiet for >2 s: the held click must not stay down and the
     * stick must stop (no motion was integrated before the timeout). */
    pointer_tick(&ps, t + POINTER_FRAME_TIMEOUT_MS + 1, capture_emit, &cap,
                 &chord);
    CHECK(!ps.btn_left);
    CHECK_EQ(capture_count(&cap, EV_KEY, BTN_LEFT, 0), 1);
    CHECK_EQ(capture_sum(&cap, EV_REL, REL_X), 0);
}

static void test_pointer_timeout(void)
{
    struct pointer_state ps;

    pointer_defaults(&ps);
    CHECK_EQ(pointer_timeout_ms(&ps, 1000), -1);

    pointer_set_mode(&ps, POINTER_MODE_DESKTOP);
    CHECK_EQ(pointer_timeout_ms(&ps, 1000), 1);
    pointer_release(&ps, NULL, NULL);
    /* pointer_release() resets the tick reference: the first tick primes it. */
    CHECK_EQ(pointer_timeout_ms(&ps, 1000), 1);
}

static void test_uinput_pointer_device(void)
{
    struct uinput_dev_spec spec;
    struct uinput_dev_ops ops;
    struct uinput_fake fake;
    bool rumble = true;
    int fd;

    fake_init(&fake, &ops);
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

    fd = uinput_dev_create(&ops, &spec, &rumble);
    CHECK(fd >= 0);
    CHECK(!rumble);
    CHECK_EQ(fake.creates, 1);
    CHECK(strcmp(fake.setup.name, POINTER_NAME) == 0);
    CHECK_EQ(fake.setup.id.vendor, POINTER_VID);
    CHECK_EQ(fake.setup.id.product, POINTER_PID);
    CHECK_EQ(fake.setup.id.bustype, BUS_USB);
    CHECK_EQ(fake.setup.ff_effects_max, 0);

    /* A pointer, not a gamepad: EV_KEY/EV_REL/EV_SYN only. */
    CHECK_EQ(call_count_of(&fake, UI_SET_EVBIT, EV_KEY), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_EVBIT, EV_REL), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_EVBIT, EV_SYN), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_EVBIT, EV_ABS), 0);
    CHECK_EQ(call_count_of(&fake, UI_SET_EVBIT, EV_FF), 0);

    for (int i = 0; i < pointer_key_count; i++)
        CHECK_EQ(call_count_of(&fake, UI_SET_KEYBIT, pointer_keys[i]), 1);
    for (int i = 0; i < pointer_rel_count; i++)
        CHECK_EQ(call_count_of(&fake, UI_SET_RELBIT, pointer_rels[i]), 1);
    CHECK_EQ(call_count_of(&fake, UI_SET_PROPBIT, INPUT_PROP_POINTER), 1);
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
    test_fusion_classify();
    test_fusion_identity();
    test_fusion_axis_sources();
    test_fusion_button_union();
    test_fusion_axis_resolution();
    test_fusion_axis_scaling();
    test_fusion_remap();
    test_fusion_topology_and_grab();
    test_fusion_settings();
    test_fusion_status();
    test_uinput_fusion_caps();
    test_uinput_phase5_device();
    test_uinput_ff_refused();
    test_uinput_axis_range();
    test_pointer_mode();
    test_pointer_movement();
    test_pointer_deadzone_and_fraction();
    test_pointer_buttons();
    test_pointer_wheel();
    test_pointer_chord();
    test_pointer_release();
    test_pointer_stale_frame();
    test_pointer_timeout();
    test_uinput_pointer_device();
    test_fusion_node_mergeable();
    test_fusion_hotplug();

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
