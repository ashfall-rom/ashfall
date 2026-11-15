/*
 * AshFall RP5 memory: zram and the swapfile (docs/phase6.md §9.3, issue #141).
 *
 * Both are opt-in (`persist.ashfall.mem.{zram_mb,swap_mb}`, 0 = off) and are
 * applied at boot and on every property change, like the fan and GPU settings.
 * The daemon is the only writer of the zram/swap state; the Memory page writes
 * properties and reads ashfall.status.mem.*.
 *
 * zram: /dev/block/zram0 with
 *   /sys/block/zram0/{disksize,comp_algorithm,comp_available,mm_stat,reset}.
 * The apply order is swapoff -> reset -> comp_algorithm -> disksize -> swapon
 * (priority 1); the compressor is lz4 when listed in comp_available, else the
 * first available one (logged, no UI option).  The size is clamped to
 * MemTotal/2.
 *
 * swap: a file at /data/ashfall/swapfile (swapon priority 2), created with a
 * sparse fallocate + a v1 swap header and attached with swapon.  The request is
 * clamped to 16384 MB and to the free space of /data minus a 2 GiB reserve.
 * Disabling it swaps off and unlinks the file, returning the space.
 *
 * A missing /dev/block/zram0, kernel module or /data mount is a logged no-op
 * with -1 status, never a crash.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_MEM_H
#define ASHFALL_MEM_H

#include <limits.h>
#include <stdbool.h>

#define MEM_ZRAM_DEV "/dev/block/zram0"
#define MEM_ZRAM_SYSFS "/sys/block/zram0"
#define MEM_ZRAM_DISKSIZE MEM_ZRAM_SYSFS "/disksize"
#define MEM_ZRAM_RESET MEM_ZRAM_SYSFS "/reset"
#define MEM_ZRAM_COMP MEM_ZRAM_SYSFS "/comp_algorithm"
#define MEM_ZRAM_COMP_AVAILABLE MEM_ZRAM_SYSFS "/comp_available"
#define MEM_ZRAM_MM_STAT MEM_ZRAM_SYSFS "/mm_stat"

#define MEM_SWAP_DIR "/data/ashfall"
#define MEM_SWAPFILE MEM_SWAP_DIR "/swapfile"
#define MEM_MEMINFO "/proc/meminfo"

#define MEM_ZRAM_MAX_MB 6144 /* hard cap; MemTotal/2 is applied on top */
#define MEM_SWAP_MAX_MB 16384
#define MEM_SWAP_RESERVE_MB 2048
#define MEM_ZRAM_PRIORITY 1
#define MEM_SWAP_PRIORITY 2
#define MEM_TRACE_LEN 192

typedef struct {
    bool scanned;
    long mem_total_kb;
    int mem_total_mb;
    bool zram_supported;
    /* Applied sizes: 0 = off, -1 = unavailable. */
    int zram_mb;
    int swap_mb;
    int zram_request;
    int swap_request;
    /* Action order of the last apply ("swapoff,reset,comp,disksize,swapon"),
     * so the host tests can assert the sequence without a kernel. */
    char trace[MEM_TRACE_LEN];
} mem_state;

/* Reset all state and read MemTotal/the current zram size.  Safe before the
 * zram module or /data exists. */
void mem_state_init(mem_state *state);

/* Effective request after MemTotal/2 (zram) or the free-space guard (swap). */
int mem_clamp_zram(const mem_state *state, int request_mb);
int mem_clamp_swap(const mem_state *state, int request_mb);

/* Free space of /data in MB, -1 when it cannot be read. */
int mem_data_free_mb(void);

/*
 * Apply the requested sizes.  Both are idempotent: the same size re-attaches
 * an existing device/file instead of recreating it, 0 disables.  Return the
 * applied size in MB, or -1 when the subsystem is unavailable (logged).
 */
int mem_apply_zram(mem_state *state, int request_mb);
int mem_apply_swap(mem_state *state, int request_mb);

/*
 * Write a v1 swap header (version, last_page, SWAPSPACE2 magic at 4086) to an
 * open file/block device.  Shared by the swapfile and by zram, whose freshly
 * reset block device is all zeros and is rejected by swapon(2) with EINVAL
 * until it carries this signature (init normally runs mkswap on zram0).  A size
 * below two pages is refused with -1.
 */
int mem_write_swap_header(int fd, long long size);

/* ashfall.status.mem.zram_used_kb / zram_saved_kb from mm_stat (compressed
 * size and orig-compr); -1 when unavailable. */
int mem_status_zram(const mem_state *state, int *used_kb, int *saved_kb);

/* /proc/meminfo SwapTotal/SwapFree -> total and used; -1 when unavailable. */
int mem_status_swap(int *total_kb, int *used_kb);

#endif /* ASHFALL_MEM_H */
