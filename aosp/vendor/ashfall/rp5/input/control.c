/*
 * AshFall RP5 input control socket server.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#define _GNU_SOURCE /* accept4 */

#include "control.h"
#include "plat.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define CONTROL_PARSE_MAX 64
#define RAW_INTERVAL_MS 10 /* <= 100 Hz */

struct client {
    int fd;
    bool raw;
    int64_t last_raw_ms;
};

struct control {
    struct input_ctx *ctx;
    int listen_fd;
    struct client clients[CONTROL_MAX_CLIENTS];
    int client_count;
};

#define SOCKET_PATH "/dev/socket/ashfall_input"
#define SOCKET_ENV "ANDROID_SOCKET_ashfall_input"

/* The host test build binds a scratch path instead of /dev/socket. */
static const char *control_socket_path(void)
{
#ifdef ASHFALL_HOST
    const char *path = getenv("ASHFALL_CONTROL_SOCKET");

    if (path && path[0])
        return path;
#endif
    return SOCKET_PATH;
}

static int socket_from_env(void)
{
    const char *value = getenv(SOCKET_ENV);
    char *end = NULL;
    long fd;

    if (!value || !value[0])
        return -2;
    fd = strtol(value, &end, 10);
    if (end == value || (end && *end) || fd < 0 || fd > 4096)
        return -2;
    return (int)fd;
}

static int socket_create_fallback(void)
{
    struct sockaddr_un addr;
    int fd;

    fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        ah_logf(AH_LOG_ERROR, "control: socket: %s", strerror(errno));
        return -1;
    }
    unlink(control_socket_path());
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s",
             control_socket_path());
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        ah_logf(AH_LOG_ERROR, "control: bind %s: %s", control_socket_path(),
                strerror(errno));
        close(fd);
        return -1;
    }
    if (chmod(control_socket_path(), 0660) != 0)
        ah_logf(AH_LOG_WARN, "control: chmod %s: %s", control_socket_path(),
                strerror(errno));
    if (listen(fd, CONTROL_MAX_CLIENTS) != 0) {
        ah_logf(AH_LOG_ERROR, "control: listen: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

struct control *control_create(struct input_ctx *ctx)
{
    struct control *control;

    if (!ctx)
        return NULL;
    control = calloc(1, sizeof(*control));
    if (!control)
        return NULL;
    control->ctx = ctx;
    control->listen_fd = socket_from_env();
    if (control->listen_fd == -2) {
        control->listen_fd = socket_create_fallback();
    } else if (control->listen_fd < 0) {
        control->listen_fd = -1;
    } else if (listen(control->listen_fd, CONTROL_MAX_CLIENTS) != 0) {
        /* init only creates and binds the socket; listening is our job.
         * Without it accept() fails with EINVAL on every poll wakeup. */
        ah_logf(AH_LOG_ERROR, "control: listen: %s", strerror(errno));
        close(control->listen_fd);
        control->listen_fd = -1;
    }
    if (control->listen_fd >= 0) {
        /* The init-provided socket is blocking; accept_clients() relies on
         * EAGAIN to stop after the pending connections are drained. */
        int flags = fcntl(control->listen_fd, F_GETFL);

        if (flags >= 0)
            fcntl(control->listen_fd, F_SETFL, flags | O_NONBLOCK);
    }
    if (control->listen_fd < 0) {
        ah_logf(AH_LOG_WARN,
                "control: no %s (commands unavailable, gamepad unaffected)",
                control_socket_path());
    } else {
        ah_logf(AH_LOG_INFO, "control: listening on %s",
                control_socket_path());
    }
    return control;
}

void control_destroy(struct control *control)
{
    if (!control)
        return;
    for (int i = 0; i < control->client_count; i++)
        close(control->clients[i].fd);
    if (control->listen_fd >= 0)
        close(control->listen_fd);
    free(control);
}

int control_pollfds(const struct control *control, struct pollfd *fds, int max)
{
    int used = 0;

    if (!control || !fds || max <= 0)
        return 0;
    if (control->listen_fd >= 0 && used < max) {
        fds[used].fd = control->listen_fd;
        fds[used].events = POLLIN;
        fds[used].revents = 0;
        used++;
    }
    for (int i = 0; i < control->client_count && used < max; i++) {
        fds[used].fd = control->clients[i].fd;
        fds[used].events = POLLIN;
        fds[used].revents = 0;
        used++;
    }
    return used;
}

static void reply(int fd, const char *text)
{
    if (fd < 0 || !text)
        return;
    if (send(fd, text, strlen(text), MSG_NOSIGNAL) < 0)
        ah_logf(AH_LOG_WARN, "control: reply: %s", strerror(errno));
}

static void drop_client(struct control *control, int index)
{
    if (index < 0 || index >= control->client_count)
        return;
    close(control->clients[index].fd);
    for (int i = index; i + 1 < control->client_count; i++)
        control->clients[i] = control->clients[i + 1];
    control->client_count--;
}

static int client_index(const struct control *control, int fd)
{
    for (int i = 0; i < control->client_count; i++) {
        if (control->clients[i].fd == fd)
            return i;
    }
    return -1;
}

static void handle_get_cal(struct control *control, int fd)
{
    char body[1024];
    char text[1200];

    calib_format(&control->ctx->cal, body, sizeof(body));
    snprintf(text, sizeof(text), "OK %s %s\n",
             cal_source_name(control->ctx->cal_source), body);
    reply(fd, text);
}

static void handle_set_cal(struct control *control, int fd, char **tokens,
                           int count)
{
    struct calibration cal;
    char err[160] = "";
    char text[220];

    if (!calib_parse_pairs(count, tokens, &cal, err, sizeof(err))) {
        snprintf(text, sizeof(text), "ERR %s\n", err);
        reply(fd, text);
        return;
    }
    if (!calib_save_file(calib_user_path(), &cal, err, sizeof(err))) {
        snprintf(text, sizeof(text), "ERR %s\n", err);
        reply(fd, text);
        return;
    }
    control->ctx->cal = cal;
    control->ctx->cal_source = CAL_SOURCE_USER;
    if (control->ctx->on_calibration_changed)
        control->ctx->on_calibration_changed(control->ctx);
    reply(fd, "OK\n");
}

static void handle_reset_cal(struct control *control, int fd)
{
    if (unlink(calib_user_path()) != 0 && errno != ENOENT) {
        char text[160];

        snprintf(text, sizeof(text), "ERR cannot remove user calibration: %s\n",
                 strerror(errno));
        reply(fd, text);
        return;
    }
    input_reload_calibration(control->ctx);
    reply(fd, "OK\n");
}

static void handle_command(struct control *control, int fd, char *line)
{
    char *tokens[CONTROL_PARSE_MAX];
    int count = 0;
    char *save = NULL;
    char *tok;

    for (tok = strtok_r(line, " \t\r\n", &save); tok;
         tok = strtok_r(NULL, " \t\r\n", &save)) {
        if (count >= CONTROL_PARSE_MAX) {
            reply(fd, "ERR too many arguments\n");
            return;
        }
        tokens[count++] = tok;
    }
    if (count == 0)
        return;

    if (!strcmp(tokens[0], "GET") && count == 2 && !strcmp(tokens[1], "CAL")) {
        handle_get_cal(control, fd);
    } else if (!strcmp(tokens[0], "SET") && count >= 2 &&
               !strcmp(tokens[1], "CAL")) {
        handle_set_cal(control, fd, tokens + 2, count - 2);
    } else if (!strcmp(tokens[0], "RESET") && count == 2 &&
               !strcmp(tokens[1], "CAL")) {
        handle_reset_cal(control, fd);
    } else if (!strcmp(tokens[0], "RAW") && count == 2 &&
               (!strcmp(tokens[1], "ON") || !strcmp(tokens[1], "OFF"))) {
        int index = client_index(control, fd);

        if (index >= 0) {
            control->clients[index].raw = !strcmp(tokens[1], "ON");
            control->clients[index].last_raw_ms = 0;
        }
        reply(fd, "OK\n");
    } else {
        reply(fd, "ERR unknown command\n");
    }
}

static void accept_clients(struct control *control)
{
    for (;;) {
        int fd = accept4(control->listen_fd, NULL, NULL,
                         SOCK_CLOEXEC | SOCK_NONBLOCK);

        if (fd < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK)
                return;
            if (errno == EINTR || errno == ECONNABORTED || errno == EMFILE ||
                errno == ENFILE || errno == ENOBUFS || errno == ENOMEM)
                return;
            /* Persistent failure (e.g. EINVAL: not listening): log once and
             * stop polling the socket instead of spinning on it. The gamepad
             * itself is unaffected. */
            ah_logf(AH_LOG_ERROR, "control: accept: %s; control socket disabled",
                    strerror(errno));
            close(control->listen_fd);
            control->listen_fd = -1;
            return;
        }
        if (control->client_count >= CONTROL_MAX_CLIENTS) {
            static const char busy[] = "ERR busy\n";

            send(fd, busy, sizeof(busy) - 1, MSG_NOSIGNAL);
            close(fd);
            continue;
        }
        control->clients[control->client_count].fd = fd;
        control->clients[control->client_count].raw = false;
        control->clients[control->client_count].last_raw_ms = 0;
        control->client_count++;
    }
}

static void read_client(struct control *control, int index)
{
    char buf[512];
    ssize_t n = recv(control->clients[index].fd, buf, sizeof(buf) - 1, 0);

    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
        return;
    if (n <= 0) {
        /* EOF or a dead peer: RAW stops automatically. */
        drop_client(control, index);
        return;
    }
    buf[n] = '\0';
    handle_command(control, control->clients[index].fd, buf);
}

void control_dispatch(struct control *control, struct pollfd *fds, int nfds)
{
    if (!control || !fds)
        return;
    for (int i = 0; i < nfds; i++) {
        short revents = fds[i].revents;

        if (!revents)
            continue;
        if (fds[i].fd == control->listen_fd) {
            accept_clients(control);
            continue;
        }
        {
            int index = client_index(control, fds[i].fd);

            if (index < 0)
                continue;
            if (revents & (POLLHUP | POLLERR | POLLNVAL)) {
                drop_client(control, index);
            } else if (revents & POLLIN) {
                read_client(control, index);
            }
        }
    }
}

void control_stream_raw(struct control *control, int64_t now_ms,
                        const struct raw_sample *sample)
{
    char line[128];
    int len;

    if (!control || !sample || control->client_count == 0)
        return;
    for (int i = 0; i < control->client_count; i++) {
        struct client *client = &control->clients[i];

        if (!client->raw)
            continue;
        if (client->last_raw_ms && now_ms - client->last_raw_ms < RAW_INTERVAL_MS)
            continue;
        len = snprintf(line, sizeof(line), "RAW %d %d %d %d %d %d %04x\n",
                       sample->lx, sample->ly, sample->rx, sample->ry,
                       sample->l2, sample->r2, sample->buttons);
        if (len <= 0)
            continue;
        if (send(client->fd, line, (size_t)len, MSG_NOSIGNAL) < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK)
                continue; /* client is not reading fast enough; drop this frame */
            drop_client(control, i);
            i--;
            continue;
        }
        client->last_raw_ms = now_ms;
    }
}
