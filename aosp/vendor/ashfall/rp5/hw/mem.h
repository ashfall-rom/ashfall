/*
 * AshFall RP5 memory: the swapfile (docs/phase6.md §9.3, issue #141).
 *
 * The swapfile is opt-in (`persist.ashfall.mem.swap_mb`, 0 = off) and is
 * applied at boot and on every property change, like the fan and GPU settings.
 * The daemon is the only writer of the swap state; the Memory page writes the
 * property and reads ashfall.status.mem.*.
 *
 * Phase 6 wave 3 (issue #156 item 7) removed the zram half: Android init
 * already attaches /dev/block/zram0 (4 GB swap at boot) and a system_ext daemon
 * cannot own a device the vendor init script configures.  AshFall therefore only
 * manages its own swapfile on /data.
 *
 * swap: a file at /data/ashfall/swapfile (swapon priority 2), created with a
 * sparse fallocate + a v1 swap header and attached with swapon.  The request is
 * clamped to 16384 MB and to the free space of /data minus a 2 GiB reserve.
 * Disabling it swaps off and unlinks the file, returning the space.
 *
 * A missing /data mount is a logged no-op with -1 status, never a crash.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_MEM_H
#define ASHFALL_MEM_H

#include <limits.h>
#include <stdbool.h>

#define MEM_SWAP_DIR "/data/ashfall"
#define MEM_SWAPFILE MEM_SWAP_DIR "/swapfile"
#define MEM_MEMINFO "/proc/meminfo"

#define MEM_SWAP_MAX_MB 16384
#define MEM_SWAP_RESERVE_MB 2048
#define MEM_SWAP_PRIORITY 2
#define MEM_TRACE_LEN 192

typedef struct {
    bool scanned;
    /* Applied size: 0 = off, -1 = unavailable. */
    int swap_mb;
    int swap_request;
    /* Action order of the last apply ("swapoff,unlink,create,mkswap,swapon"),
     * so the host tests can assert the sequence without a kernel. */
    char trace[MEM_TRACE_LEN];
} mem_state;

/* Reset all state.  Safe before /data is mounted. */
void mem_state_init(mem_state *state);

/* Effective request after the free-space guard. */
int mem_clamp_swap(const mem_state *state, int request_mb);

/* Free space of /data in MB, -1 when it cannot be read. */
int mem_data_free_mb(void);

/*
 * Apply the requested size.  Idempotent: the same size re-attaches the existing
 * file instead of recreating it, 0 disables.  Returns the applied size in MB, or
 * -1 when the file could not be created/attached (logged).
 */
int mem_apply_swap(mem_state *state, int request_mb);

/*
 * Write a v1 swap header (version, last_page, SWAPSPACE2 magic at 4086) to an
 * open swapfile.  A size below two pages is refused with -1.
 */
int mem_write_swap_header(int fd, long long size);

/* /proc/meminfo SwapTotal/SwapFree -> total and used; -1 when unavailable. */
int mem_status_swap(int *total_kb, int *used_kb);

#endif /* ASHFALL_MEM_H */
