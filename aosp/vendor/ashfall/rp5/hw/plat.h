/*
 * AshFall RP5 hardware daemon: small platform helpers.
 *
 * The daemon is deliberately dependency-light: it talks to system properties
 * (bionic), to sysfs and nothing else.  The helpers below are also compiled
 * for the host test binary (ASHFALL_HOST), where properties come from the
 * environment and sysfs is rooted under $ASHFALL_SYSFS_ROOT so the fan/LED
 * logic can be exercised without a device.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_PLAT_H
#define ASHFALL_PLAT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Android log priorities (android/log.h values). */
#define AH_LOG_INFO 4
#define AH_LOG_WARN 5
#define AH_LOG_ERROR 6

void ah_log_set_tag(const char *tag);
void ah_logf(int prio, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

/* Milliseconds from an arbitrary but monotonic-in-process clock (CLOCK_MONOTONIC). */
int64_t ah_now_ms(void);
void ah_sleep_ms(int ms);

/* System properties.  get_* return the fallback when the property is missing
 * or malformed.  set_str logs (and keeps running) when the write fails. */
int prop_get_str(const char *name, char *buf, size_t len);
int prop_get_int(const char *name, int fallback);
/* An int property constrained to [lo, hi].  Missing, malformed or
 * out-of-range values use the documented default (docs/phase5.md §2); each
 * distinct bad value is logged once. */
int prop_get_int_range(const char *name, int fallback, int lo, int hi);
bool prop_get_bool(const char *name, bool fallback);
void prop_set_str(const char *name, const char *value);
void prop_set_int(const char *name, int value);

/* Block until any system property changes or the timeout expires.  Used
 * instead of polling so a settings change is applied immediately. */
void prop_wait_any(int timeout_ms);

/* sysfs access.  Paths passed here are absolute device paths; in a host build
 * they are prefixed with $ASHFALL_SYSFS_ROOT.  plat_resolve() applies that
 * prefix for callers that open directories themselves. */
const char *plat_resolve(const char *path, char *buf, size_t len);
int sysfs_read_str(const char *path, char *buf, size_t len);
int sysfs_read_int(const char *path, int *out);
int sysfs_write_str(const char *path, const char *value);
int sysfs_write_int(const char *path, int value);
bool sysfs_exists(const char *path);

/* True when the attribute exists and is writable (DAC).  Used to decide
 * whether a sysfs node is present without a read round-trip. */
bool sysfs_writable(const char *path);

/* True when the attribute exists at all (existence only, no read/DAC check).
 * Used to find write-only sysfs nodes whose permissions may still change
 * (vendor init chmod runs around the time class core starts). */
bool sysfs_present(const char *path);

#endif /* ASHFALL_PLAT_H */
