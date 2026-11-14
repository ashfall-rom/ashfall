/*
 * Copyright (C) 2026 The AshFall Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Test-only stand-in for ashfall-rp5-input.
 *
 * Serves /dev/socket/ashfall_input (SOCK_SEQPACKET) with the protocol from
 * docs/phase5.md §2.3 and §6.2 (GRAB ON/OFF), and animates the RAW stream so the
 * controller tester and the calibration wizard can be exercised on the emulator.
 * Development tooling: this binary is never installed into the image.
 *
 *   tools/mock/build.sh --run
 */

#define _GNU_SOURCE
#include <errno.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define SOCKET_PATH "/dev/socket/ashfall_input"
#define PACKET 8192

static const char *AXES[] = {"left_x", "left_y", "right_x", "right_y", "l2", "r2"};

static void reply(int fd, const char *text) {
    send(fd, text, strlen(text), 0);
}

static void calibration(char *out, size_t size) {
    size_t used = 0;
    for (size_t i = 0; i < sizeof(AXES) / sizeof(AXES[0]); i++) {
        used += (size_t)snprintf(out + used, size - used,
                                 "%s%s_min=-32768 %s_center=0 %s_max=32767",
                                 i == 0 ? "" : " ", AXES[i], AXES[i], AXES[i]);
    }
}

static double now_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void send_raw(int fd, double start) {
    const double t = now_seconds() - start;
    const int lx = (int)(sin(t * 0.9) * 28000.0);
    const int ly = (int)(cos(t * 0.9) * 28000.0);
    const int rx = (int)(sin(t * 0.4) * 20000.0);
    const int ry = (int)(cos(t * 0.7) * 20000.0);
    /* Raw trigger counts fall from max (released) to min (fully pulled), as on
     * the RP5; the values cover a partial pull as well as both ends. */
    const int l2 = (int)(32767.0 - (sin(t * 0.8) + 1.0) * 20000.0);
    const int r2 = (int)(32767.0 - (cos(t * 0.5) + 1.0) * 20000.0);
    /* Cycle through button groups so every bit of the documented mask is shown:
     * phase 0 = X/A/L1/Start/D-pad up, phase 1 = Y/B/R1/Select/Home/Back,
     * phase 2 = L3/R3/D-pad left. */
    const int phase = ((int)(t / 2.0)) % 3;
    int buttons = 0;
    if (phase == 0) {
        buttons = (1 << 12) | (1 << 14) | (1 << 0) | (1 << 3) | (1 << 8);
    } else if (phase == 1) {
        buttons = (1 << 13) | (1 << 15) | (1 << 1) | (1 << 2) | (1 << 6) | (1 << 7);
    } else {
        buttons = (1 << 4) | (1 << 5) | (1 << 10);
    }
    /* Test hook: while this file exists the mock reports Start+Select held, so
     * the tester's two-second exit chord can be exercised without a pad. */
    if (access("/data/local/tmp/ashfall-mock-hold", F_OK) == 0) {
        buttons = (1 << 3) | (1 << 2);
    }
    char line[160];
    const int len = snprintf(line, sizeof(line), "RAW %d %d %d %d %d %d 0x%04X",
                             lx, ly, rx, ry, l2, r2, buttons);
    send(fd, line, (size_t)len, 0);
}

static void handle_client(int fd) {
    char buffer[PACKET];
    int raw = 0;
    int grab = 0;
    const double start = now_seconds();

    for (;;) {
        struct pollfd pfd = {.fd = fd, .events = POLLIN};
        const int ready = poll(&pfd, 1, raw ? 16 : -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ready == 0) {
            send_raw(fd, start);
            continue;
        }
        if (!(pfd.revents & POLLIN)) break;

        ssize_t n = recv(fd, buffer, sizeof(buffer) - 1, 0);
        if (n <= 0) break;
        buffer[n] = '\0';
        while (n > 0 && (buffer[n - 1] == '\n' || buffer[n - 1] == '\r')) buffer[--n] = '\0';

        if (strncmp(buffer, "GET CAL", 7) == 0) {
            char cal[1024];
            calibration(cal, sizeof(cal));
            char replybuf[1100];
            snprintf(replybuf, sizeof(replybuf), "OK factory %s", cal);
            reply(fd, replybuf);
        } else if (strncmp(buffer, "SET CAL", 7) == 0) {
            int keys = 0;
            for (const char *p = buffer; *p; p++) {
                if (*p == '=') keys++;
            }
            reply(fd, keys >= 16 ? "OK user" : "ERR invalid calibration");
        } else if (strcmp(buffer, "RESET CAL") == 0) {
            reply(fd, "OK factory");
        } else if (strcmp(buffer, "RAW ON") == 0) {
            raw = 1;
            reply(fd, "OK raw on");
        } else if (strcmp(buffer, "RAW OFF") == 0) {
            raw = 0;
            reply(fd, "OK raw off");
        } else if (strcmp(buffer, "GRAB ON") == 0) {
            grab = 1;
            fprintf(stderr, "GRAB ON\n");
            fflush(stderr);
            reply(fd, "OK grab on");
        } else if (strcmp(buffer, "GRAB OFF") == 0) {
            grab = 0;
            fprintf(stderr, "GRAB OFF\n");
            fflush(stderr);
            reply(fd, "OK grab off");
        } else {
            reply(fd, "ERR unknown command");
        }
    }
    if (grab) {
        fprintf(stderr, "client left while grabbed -> GRAB OFF\n");
        fflush(stderr);
    }
    close(fd);
}

int main(int argc, char **argv) {
    (void)argv;
    (void)argc;
    signal(SIGPIPE, SIG_IGN);

    const int server = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    if (server < 0) {
        perror("socket");
        return 1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strlcpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path));
    unlink(SOCKET_PATH);
    if (bind(server, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        return 1;
    }
    if (listen(server, 8) < 0) {
        perror("listen");
        return 1;
    }
    fprintf(stderr, "ashfall mock daemon listening on %s\n", SOCKET_PATH);
    fflush(stderr);

    for (;;) {
        const int client = accept(server, NULL, NULL);
        if (client < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            break;
        }
        if (fork() == 0) {
            close(server);
            handle_client(client);
            _exit(0);
        }
        close(client);
    }
    close(server);
    return 0;
}
