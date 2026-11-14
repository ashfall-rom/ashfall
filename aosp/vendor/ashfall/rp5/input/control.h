/*
 * AshFall RP5 input control socket: /dev/socket/ashfall_input.
 *
 * One text command per seqpacket, one reply per command:
 *   GET CAL                  -> OK <source> k=v k=v ...
 *   SET CAL k=v ...           -> OK | ERR <reason>
 *   RESET CAL                 -> OK
 *   RAW ON | RAW OFF          -> OK   (<= 100 Hz raw samples to that client)
 *
 * The socket itself is created by init (`socket ashfall_input seqpacket 0660
 * system system`) and inherited as $ANDROID_SOCKET_ashfall_input.  For
 * standalone experiments the server falls back to binding the path itself.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_CONTROL_H
#define ASHFALL_CONTROL_H

#include "input.h"

#include <poll.h>
#include <stdbool.h>
#include <stdint.h>

#define CONTROL_MAX_CLIENTS 8

struct control;

struct control *control_create(struct input_ctx *ctx);
void control_destroy(struct control *control);

/* Append this server's fds to a poll() set.  Returns the count appended. */
int control_pollfds(const struct control *control, struct pollfd *fds, int max);

/* Handle the revents of the entries appended by control_pollfds(). */
void control_dispatch(struct control *control, struct pollfd *fds, int nfds);

/* Wait up to timeout_ms for control traffic and dispatch it.  This is the
 * "outside a session" entry point: the socket must be serviced even when the
 * MCU or /dev/uinput is unavailable (GET/SET CAL must always answer).
 * Returns poll()'s result, or 0 when there is no socket/traffic. */
int control_poll(struct control *control, int timeout_ms);

/* Stream a raw sample to every subscribed client (100 Hz cap per client). */
void control_stream_raw(struct control *control, int64_t now_ms,
                        const struct raw_sample *sample);

#endif /* ASHFALL_CONTROL_H */
