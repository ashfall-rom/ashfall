/*
 * AshFall RP5 CPU frequency caps and governor (docs/phase6.md §9.3).
 *
 * Three CPU policies, discovered at runtime: kona exposes silver (CPU 0..3,
 * policy0), gold (CPU 4..6, policy4) and prime (CPU 7, policy7), but the
 * directories, the CPU-id split and the available steps are read from
 * /sys/devices/system/cpu/cpufreq rather than assumed.  Each policy is matched
 * to a cluster by its `affected_cpus` list (falling back to the policy number
 * and, when neither is usable, to the ascending order of its top frequency).
 *
 * Only *lowering* is supported: 0 means "stock", i.e. the highest available
 * step, and a request is clamped to the highest available step <= the request.
 * The governor is validated against scaling_available_governors and only
 * schedutil / conservative / powersave / performance are accepted; anything
 * else becomes schedutil.  `performance` (phase 6 wave 3, issue #156 item 6)
 * still respects scaling_max_freq: it pins the asked clock, it does not raise
 * the cap.  Both are re-applied at boot and on every property change, because
 * the kernel resets scaling_max_freq/scaling_governor to its defaults on boot.
 *
 * The same file owns the CPU *utilisation* sample (ashfall.status.perf.cpu_load_pct)
 * because both read /proc/stat and cpufreq under the existing status tick.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_CPU_H
#define ASHFALL_CPU_H

#include <limits.h>
#include <stdbool.h>

#define CPU_CPUFREQ_BASE "/sys/devices/system/cpu/cpufreq"
#define CPU_STAT_PATH "/proc/stat"
#define CPU_CLUSTER_COUNT 3
#define CPU_MAX_STEPS 32
#define CPU_MAX_MHZ 4000
#define CPU_GOV_LEN 24

typedef enum {
    CPU_CLUSTER_SILVER = 0,
    CPU_CLUSTER_GOLD = 1,
    CPU_CLUSTER_PRIME = 2,
    CPU_CLUSTER_NONE = -1,
} cpu_cluster;

typedef struct {
    char dir[PATH_MAX];
    char max_path[PATH_MAX];
    char gov_path[PATH_MAX];
    char cur_path[PATH_MAX];
    char govs_path[PATH_MAX];
    char govs[160]; /* scaling_available_governors, verbatim */
    /* scaling_available_frequencies in kHz, descending. */
    int freqs_khz[CPU_MAX_STEPS];
    int count;
    bool available;
    bool applied;
    int applied_khz; /* last successfully written cap, 0 = unknown */
    int request_mhz; /* last evaluated request (0 = stock) */
} cpu_policy;

typedef struct {
    cpu_policy policy[CPU_CLUSTER_COUNT];
    int count;
    bool available;
    bool governor_applied;
    char governor[CPU_GOV_LEN]; /* last successfully written governor */
} cpu_state;

/* Two /proc/stat samples: the load is only known once a delta exists. */
typedef struct {
    unsigned long long idle;  /* idle + iowait, in USER_HZ jiffies */
    unsigned long long total; /* the first eight aggregate fields */
    bool valid;               /* a sample is stored and usable as a baseline */
} cpu_load;

/* Reset all state.  Safe before cpufreq exists (early boot). */
void cpu_state_init(cpu_state *state);

/*
 * Apply the three caps (MHz, 0 = stock/no cap) and one governor to all
 * policies.  Reads the tables on first use and retries while cpufreq is not
 * there yet.  Returns 0 when at least one policy was applied, -1 when cpufreq
 * is unavailable (logged, never fatal).
 */
int cpu_apply(cpu_state *state, int silver_mhz, int gold_mhz, int prime_mhz,
              const char *governor);

/* Effective scaling_cur_freq per cluster in MHz, -1 unavailable. */
int cpu_status_mhz(const cpu_state *state, cpu_cluster cluster);

/* True while cpufreq is not up yet or a cap write failed: the daemon retries
 * on its CPU_RETRY_MS timer instead of waiting for a property change. */
bool cpu_needs_retry(const cpu_state *state);

/* Last successfully written governor; "schedutil" when unknown. */
const char *cpu_governor_name(const cpu_state *state);

void cpu_load_init(cpu_load *load);

/*
 * ashfall.status.perf.cpu_load_pct: utilisation since the previous call, from
 * the aggregate `cpu` line of /proc/stat (idle + iowait over total).  Returns
 * 0..100, or -1 when /proc/stat is unreadable/truncated or when this is the
 * first sample (no delta yet) - a gap, never a fabricated 0 or 100.
 */
int cpu_load_pct(cpu_load *load);

/* Helpers exported for the host tests. */
cpu_cluster cpu_cluster_from_cpu(int cpu);
int cpu_pick_khz(const cpu_policy *policy, int request_mhz);
const char *cpu_validate_governor(const char *request, const char *available);

#endif /* ASHFALL_CPU_H */
