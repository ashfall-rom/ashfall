/*
 * AshFall RP5 input daemon: small platform helpers (logging, properties,
 * monotonic clock).
 *
 * Kept separate from the hw daemon's plat.c on purpose: the two binaries are
 * independent, and this one needs no sysfs access at all.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_INPUT_PLAT_H
#define ASHFALL_INPUT_PLAT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define AH_LOG_INFO 4
#define AH_LOG_WARN 5
#define AH_LOG_ERROR 6

void ah_log_set_tag(const char *tag);
void ah_logf(int prio, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

int64_t ah_now_ms(void);

int prop_get_str(const char *name, char *buf, size_t len);
/*
 * Like prop_get_str(), but also returns the property's write serial.  bionic
 * bumps it on every successful write, even when the text is unchanged, so a
 * two-state toggle that re-writes the value already stored is still visible.
 * `serial` is 0 when the property does not exist or on the host build.
 */
int prop_get_str_serial(const char *name, char *buf, size_t len,
                        uint32_t *serial);
int prop_get_int(const char *name, int fallback);
/* An int property constrained to [lo, hi].  Missing, malformed or
 * out-of-range values use the documented default (docs/phase5.md §2). */
int prop_get_int_range(const char *name, int fallback, int lo, int hi);

/* Publish a status property (ashfall.status.*); reports and keeps running
 * when the write fails (a host build just logs). */
void prop_set_str(const char *name, const char *value);

#endif /* ASHFALL_INPUT_PLAT_H */
