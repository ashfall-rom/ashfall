/*
 * AshFall RP5 memory: zram and the swapfile (docs/phase6.md §9.3, #141).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "mem.h"
#include "plat.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <unistd.h>

#define MB 1024L
#define SWAP_HEADER_PAGE 4096
/* linux/swap.h: SWAP_FLAG_PREFER, priority in the low bits. */
#define SWAP_FLAG_PREFER 0x8000

#if !defined(ASHFALL_HOST)
#include <sys/syscall.h>
#endif

static void trace_clear(mem_state *state)
{
    state->trace[0] = '\0';
}

static void trace_add(mem_state *state, const char *action)
{
    size_t used = strlen(state->trace);

    if (used >= sizeof(state->trace) - 1)
        return;
    snprintf(state->trace + used, sizeof(state->trace) - used, "%s%s",
             used ? "," : "", action);
}

static int read_meminfo(long *mem_total_kb, long *swap_total_kb,
                        long *swap_free_kb)
{
    char buf[8192];
    char *line;

    if (sysfs_read_str(MEM_MEMINFO, buf, sizeof(buf)) < 0)
        return -1;
    for (line = buf; line && *line; line = strchr(line, '\n')) {
        long value;

        while (*line == '\n')
            line++;
        if (mem_total_kb && sscanf(line, "MemTotal: %ld kB", &value) == 1)
            *mem_total_kb = value;
        else if (swap_total_kb &&
                 sscanf(line, "SwapTotal: %ld kB", &value) == 1)
            *swap_total_kb = value;
        else if (swap_free_kb && sscanf(line, "SwapFree: %ld kB", &value) == 1)
            *swap_free_kb = value;
        if (!strchr(line, '\n'))
            break;
    }
    return 0;
}

static int zram_disksize_mb(void)
{
    char buf[64];
    char *end = NULL;
    long long bytes;

    if (sysfs_read_str(MEM_ZRAM_DISKSIZE, buf, sizeof(buf)) <= 0)
        return -1;
    errno = 0;
    bytes = strtoll(buf, &end, 10);
    if (errno != 0 || end == buf || bytes <= 0)
        return 0;
    return (int)(bytes / (1024 * 1024));
}

static bool zram_probe(void)
{
    return sysfs_present(MEM_ZRAM_DEV) && sysfs_present(MEM_ZRAM_DISKSIZE);
}

static int swapfile_mb(void)
{
    char path[PATH_MAX];
    struct stat st;

    if (stat(plat_resolve(MEM_SWAPFILE, path, sizeof(path)), &st) != 0)
        return -1;
    if (!S_ISREG(st.st_mode) || st.st_size <= 0)
        return -1;
    return (int)(st.st_size / (1024 * 1024));
}

int mem_data_free_mb(void)
{
    char path[PATH_MAX];
    struct statvfs vfs;

    if (statvfs(plat_resolve("/data", path, sizeof(path)), &vfs) != 0)
        return -1;
    if (vfs.f_frsize == 0)
        return -1;
    return (int)((long long)vfs.f_bavail * (long long)vfs.f_frsize /
                 (1024 * 1024));
}

void mem_state_init(mem_state *state)
{
    if (!state)
        return;
    memset(state, 0, sizeof(*state));
    if (read_meminfo(&state->mem_total_kb, NULL, NULL) == 0 &&
        state->mem_total_kb > 0)
        state->mem_total_mb = (int)(state->mem_total_kb / 1024);
    state->scanned = true;
    state->zram_supported = zram_probe();
    state->zram_mb = state->zram_supported ? zram_disksize_mb() : -1;
    if (state->zram_mb < 0)
        state->zram_mb = state->zram_supported ? 0 : -1;
    state->swap_mb = 0;
    state->zram_request = 0;
    state->swap_request = 0;
    if (state->mem_total_mb > 0)
        ah_logf(AH_LOG_INFO, "mem: MemTotal %d MB, zram %s", state->mem_total_mb,
                state->zram_supported ? "available" : "unavailable");
}

int mem_clamp_zram(const mem_state *state, int request_mb)
{
    int limit;

    if (request_mb <= 0)
        return 0;
    limit = MEM_ZRAM_MAX_MB;
    if (state->mem_total_mb > 0 && state->mem_total_mb / 2 < limit)
        limit = state->mem_total_mb / 2;
    if (request_mb > limit)
        request_mb = limit;
    return request_mb;
}

int mem_clamp_swap(const mem_state *state, int request_mb)
{
    int free_mb;
    int existing;
    int limit;

    (void)state;
    if (request_mb <= 0)
        return 0;
    if (request_mb > MEM_SWAP_MAX_MB)
        request_mb = MEM_SWAP_MAX_MB;
    free_mb = mem_data_free_mb();
    if (free_mb < 0) {
        static bool logged;

        if (!logged) {
            logged = true;
            ah_logf(AH_LOG_WARN,
                    "mem: cannot read the free space of /data; swap disabled");
        }
        return 0;
    }
    /* Resizing unlinks the old file before allocating the new one, so its
     * blocks count as available for this request.  Without this a /data that
     * is merely tight would drop a swapfile that was applied last boot. */
    existing = swapfile_mb();
    if (existing > 0)
        free_mb += existing;
    limit = free_mb - MEM_SWAP_RESERVE_MB;
    if (limit < 0)
        limit = 0;
    if (request_mb > limit) {
        ah_logf(AH_LOG_WARN,
                "mem: swap request %d MB clamped to %d MB (%d MB free on "
                "/data, %d MB reserved)",
                request_mb, limit, free_mb, MEM_SWAP_RESERVE_MB);
        request_mb = limit;
    }
    return request_mb;
}

/* Host tests: the swap syscalls would need a real kernel; the action order is
 * recorded in mem_state.trace instead.  zram sysfs and the swapfile itself are
 * real files under $ASHFALL_SYSFS_ROOT, so their writes are exercised. */
static int do_swapon(const char *path, int priority)
{
    char real[PATH_MAX];

    plat_resolve(path, real, sizeof(real));
#if defined(ASHFALL_HOST)
    (void)real;
    (void)priority;
    return 0;
#else
    return (int)syscall(__NR_swapon, real,
                        SWAP_FLAG_PREFER | (priority & 0x7fff));
#endif
}

static int do_swapoff(const char *path)
{
    char real[PATH_MAX];

    plat_resolve(path, real, sizeof(real));
#if defined(ASHFALL_HOST)
    (void)real;
    return 0;
#else
    return (int)syscall(__NR_swapoff, real);
#endif
}

/* lz4 when comp_available lists it, else the first available compressor. */
static void choose_compressor(char *out, size_t len)
{
    char list[128];
    char *save = NULL;
    char *token;
    char *first = NULL;

    out[0] = '\0';
    if (sysfs_read_str(MEM_ZRAM_COMP_AVAILABLE, list, sizeof(list)) <= 0)
        return;
    for (token = strtok_r(list, " \t\n", &save); token;
         token = strtok_r(NULL, " \t\n", &save)) {
        if (!first)
            first = token;
        if (!strcmp(token, "lz4")) {
            snprintf(out, len, "%s", token);
            return;
        }
    }
    if (first)
        snprintf(out, len, "%s", first);
}

int mem_apply_zram(mem_state *state, int request_mb)
{
    int clamped;
    long long bytes;
    char comp[24];

    if (!state)
        return -1;
    trace_clear(state);
    state->zram_supported = zram_probe();
    state->zram_request = request_mb;
    if (!state->zram_supported) {
        state->zram_mb = -1;
        ah_logf(AH_LOG_WARN,
                "mem: zram unavailable (%s or %s missing); request %d MB is a "
                "no-op",
                MEM_ZRAM_DEV, MEM_ZRAM_DISKSIZE, request_mb);
        return -1;
    }
    clamped = mem_clamp_zram(state, request_mb);
    if (clamped != request_mb && request_mb > 0)
        ah_logf(AH_LOG_INFO, "mem: zram request %d MB clamped to %d MB", request_mb,
                clamped);

    if (clamped == 0) {
        if (state->zram_mb != 0) {
            trace_add(state, "swapoff");
            do_swapoff(MEM_ZRAM_DEV);
            trace_add(state, "reset");
            if (sysfs_write_int(MEM_ZRAM_RESET, 1) != 0)
                ah_logf(AH_LOG_WARN, "mem: cannot reset zram");
        }
        state->zram_mb = 0;
        return 0;
    }

    /* Same size already set up (boot, or a restart in the same boot): attach
     * it instead of reallocating the device. */
    if (state->zram_mb == clamped) {
        /* Already allocated (boot, or a restart in the same boot): re-attach;
         * EBUSY means it is still the active swap device. */
        trace_add(state, "swapon");
        if (do_swapon(MEM_ZRAM_DEV, MEM_ZRAM_PRIORITY) != 0 && errno != EBUSY) {
            ah_logf(AH_LOG_WARN, "mem: swapon zram failed: %s",
                    strerror(errno));
            state->zram_mb = -1;
            return -1;
        }
        return clamped;
    }

    if (state->zram_mb > 0) {
        trace_add(state, "swapoff");
        do_swapoff(MEM_ZRAM_DEV);
    }
    trace_add(state, "reset");
    if (sysfs_write_int(MEM_ZRAM_RESET, 1) != 0) {
        ah_logf(AH_LOG_WARN, "mem: cannot reset zram");
        state->zram_mb = -1;
        return -1;
    }
    choose_compressor(comp, sizeof(comp));
    if (comp[0]) {
        static bool logged_comp;

        trace_add(state, "comp");
        if (sysfs_write_str(MEM_ZRAM_COMP, comp) != 0)
            ah_logf(AH_LOG_WARN, "mem: cannot set zram compressor %s", comp);
        else if (!logged_comp) {
            logged_comp = true;
            ah_logf(AH_LOG_INFO, "mem: zram compressor %s", comp);
        }
    }
    bytes = (long long)clamped * 1024 * 1024;
    trace_add(state, "disksize");
    {
        char value[32];

        snprintf(value, sizeof(value), "%lld", bytes);
        if (sysfs_write_str(MEM_ZRAM_DISKSIZE, value) != 0) {
            ah_logf(AH_LOG_WARN, "mem: cannot set zram disksize to %d MB",
                    clamped);
            state->zram_mb = -1;
            return -1;
        }
    }
    trace_add(state, "swapon");
    if (do_swapon(MEM_ZRAM_DEV, MEM_ZRAM_PRIORITY) != 0) {
        ah_logf(AH_LOG_WARN, "mem: swapon zram failed: %s", strerror(errno));
        state->zram_mb = -1;
        return -1;
    }
    state->zram_mb = clamped;
    ah_logf(AH_LOG_INFO, "mem: zram %d MB active", clamped);
    return clamped;
}

static int write_swap_header(int fd, long long size)
{
    unsigned char page[SWAP_HEADER_PAGE];
    unsigned int version = 1;
    unsigned int last_page;
    static const char magic[] = "SWAPSPACE2";

    if (size < 2 * SWAP_HEADER_PAGE)
        return -1;
    last_page = (unsigned int)(size / SWAP_HEADER_PAGE) - 1;
    memset(page, 0, sizeof(page));
    /* struct swap_header: bootbits[1024], then version, last_page, nr_badpages. */
    memcpy(page + 1024, &version, sizeof(version));
    memcpy(page + 1028, &last_page, sizeof(last_page));
    memcpy(page + SWAP_HEADER_PAGE - sizeof(magic) + 1, magic,
           sizeof(magic) - 1);
    if (pwrite(fd, page, sizeof(page), 0) != (ssize_t)sizeof(page))
        return -1;
    return 0;
}

static int swapfile_create(int mb)
{
    char path[PATH_MAX];
    char dir[PATH_MAX];
    long long size = (long long)mb * 1024 * 1024;
    int fd;
    int rc;

    if (mkdir(plat_resolve(MEM_SWAP_DIR, dir, sizeof(dir)), 0700) != 0 &&
        errno != EEXIST)
        return -1;
    fd = open(plat_resolve(MEM_SWAPFILE, path, sizeof(path)),
              O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        ah_logf(AH_LOG_WARN, "mem: cannot create %s: %s", MEM_SWAPFILE,
                strerror(errno));
        return -1;
    }
    rc = posix_fallocate(fd, 0, (off_t)size);
    if (rc != 0) {
        /* Filesystems without fallocate: a sparse file is still a valid swap
         * target once the header is written. */
        if (ftruncate(fd, (off_t)size) != 0) {
            close(fd);
            unlink(path);
            return -1;
        }
    }
    rc = write_swap_header(fd, size);
    close(fd);
    if (rc != 0) {
        unlink(path);
        return -1;
    }
    return 0;
}

int mem_apply_swap(mem_state *state, int request_mb)
{
    int clamped;
    int existing;

    if (!state)
        return -1;
    trace_clear(state);
    state->swap_request = request_mb;
    clamped = mem_clamp_swap(state, request_mb);

    if (clamped == 0) {
        if (state->swap_mb > 0 || swapfile_mb() >= 0) {
            char path[PATH_MAX];

            trace_add(state, "swapoff");
            do_swapoff(MEM_SWAPFILE);
            trace_add(state, "unlink");
            unlink(plat_resolve(MEM_SWAPFILE, path, sizeof(path)));
        }
        state->swap_mb = 0;
        return 0;
    }

    existing = swapfile_mb();
    if (existing == clamped) {
        /* Same file already allocated (boot, or a daemon restart): attach. */
        trace_add(state, "swapon");
        if (do_swapon(MEM_SWAPFILE, MEM_SWAP_PRIORITY) != 0 && errno != EBUSY) {
            ah_logf(AH_LOG_WARN, "mem: swapon %s failed: %s", MEM_SWAPFILE,
                    strerror(errno));
            state->swap_mb = -1;
            return -1;
        }
        state->swap_mb = clamped;
        return clamped;
    }

    if (state->swap_mb != 0 || existing > 0) {
        trace_add(state, "swapoff");
        do_swapoff(MEM_SWAPFILE);
        trace_add(state, "unlink");
        {
            char path[PATH_MAX];

            unlink(plat_resolve(MEM_SWAPFILE, path, sizeof(path)));
        }
    }
    trace_add(state, "create");
    trace_add(state, "mkswap");
    if (swapfile_create(clamped) != 0) {
        ah_logf(AH_LOG_WARN, "mem: cannot create a %d MB swapfile", clamped);
        state->swap_mb = -1;
        return -1;
    }
    trace_add(state, "swapon");
    if (do_swapon(MEM_SWAPFILE, MEM_SWAP_PRIORITY) != 0) {
        char path[PATH_MAX];

        ah_logf(AH_LOG_WARN, "mem: swapon %s failed: %s", MEM_SWAPFILE,
                strerror(errno));
        unlink(plat_resolve(MEM_SWAPFILE, path, sizeof(path)));
        state->swap_mb = -1;
        return -1;
    }
    state->swap_mb = clamped;
    ah_logf(AH_LOG_INFO, "mem: swapfile %d MB active", clamped);
    return clamped;
}

int mem_status_zram(const mem_state *state, int *used_kb, int *saved_kb)
{
    char buf[160];
    long long orig = 0;
    long long compr = 0;

    if (used_kb)
        *used_kb = -1;
    if (saved_kb)
        *saved_kb = -1;
    if (!state || !state->zram_supported || state->zram_mb <= 0)
        return -1;
    if (sysfs_read_str(MEM_ZRAM_MM_STAT, buf, sizeof(buf)) <= 0)
        return -1;
    /* orig_data_size compr_data_size mem_used_total ... */
    if (sscanf(buf, "%lld %lld", &orig, &compr) != 2)
        return -1;
    if (orig < 0 || compr < 0)
        return -1;
    if (used_kb)
        *used_kb = (int)(compr / 1024);
    if (saved_kb)
        *saved_kb = (int)((orig > compr ? orig - compr : 0) / 1024);
    return 0;
}

int mem_status_swap(int *total_kb, int *used_kb)
{
    long total = -1;
    long free_kb = -1;

    if (total_kb)
        *total_kb = -1;
    if (used_kb)
        *used_kb = -1;
    if (read_meminfo(NULL, &total, &free_kb) != 0 || total < 0 || free_kb < 0)
        return -1;
    if (total_kb)
        *total_kb = (int)total;
    if (used_kb)
        *used_kb = (int)(total - free_kb);
    return 0;
}
