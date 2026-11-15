/*
 * AshFall RP5 CPU frequency caps and governor (docs/phase6.md §9.3).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "cpu.h"
#include "plat.h"

#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define KHZ_PER_MHZ 1000

static int khz_to_mhz(int khz)
{
    if (khz <= 0)
        return -1;
    return (khz + KHZ_PER_MHZ / 2) / KHZ_PER_MHZ;
}

cpu_cluster cpu_cluster_from_cpu(int cpu)
{
    /* kona: silver 0..3, gold 4..6, prime 7. */
    if (cpu < 0)
        return CPU_CLUSTER_NONE;
    if (cpu <= 3)
        return CPU_CLUSTER_SILVER;
    if (cpu <= 6)
        return CPU_CLUSTER_GOLD;
    return CPU_CLUSTER_PRIME;
}

/* Token search in a space-separated sysfs list ("300000 691200 ..."). */
static bool list_contains(const char *list, const char *name)
{
    size_t len;

    if (!list || !name)
        return false;
    len = strlen(name);
    for (const char *p = list; (p = strstr(p, name)) != NULL; p += len) {
        bool start_ok = p == list || p[-1] == ' ' || p[-1] == '\t' ||
                        p[-1] == '\n';
        bool end_ok = p[len] == '\0' || p[len] == ' ' || p[len] == '\t' ||
                      p[len] == '\n';

        if (start_ok && end_ok)
            return true;
    }
    return false;
}

static const char *allowed_governors[] = {"schedutil", "conservative",
                                          "powersave"};

static const char *canonical_governor(const char *request)
{
    if (!request)
        return NULL;
    for (size_t i = 0; i < sizeof(allowed_governors) / sizeof(allowed_governors[0]);
         i++) {
        if (!strcmp(request, allowed_governors[i]))
            return allowed_governors[i];
    }
    return NULL;
}

const char *cpu_validate_governor(const char *request, const char *available)
{
    const char *want = canonical_governor(request);
    bool have_list = available && available[0];

    /* An unknown name (walt/performance are deliberately unsupported) falls
     * back to schedutil, the kernel default. */
    if (want && (!have_list || list_contains(available, want)))
        return want;
    for (size_t i = 0; i < sizeof(allowed_governors) / sizeof(allowed_governors[0]);
         i++) {
        if (!have_list || list_contains(available, allowed_governors[i]))
            return allowed_governors[i];
    }
    return "schedutil";
}

int cpu_pick_khz(const cpu_policy *policy, int request_mhz)
{
    if (!policy || !policy->available || policy->count <= 0)
        return 0;
    if (request_mhz <= 0)
        return policy->freqs_khz[0]; /* 0 = stock, the highest step */
    /* Highest step whose MHz (floored) is still <= the request: the presets
     * name the rounded-down step (1171 for 1171.2 MHz), so 1171 must select
     * 1171200 kHz and not the next step below. */
    for (int i = 0; i < policy->count; i++) {
        if (policy->freqs_khz[i] / KHZ_PER_MHZ <= request_mhz)
            return policy->freqs_khz[i];
    }
    return policy->freqs_khz[policy->count - 1];
}

static void sort_khz_descending(int *values, int count)
{
    for (int i = 1; i < count; i++) {
        int value = values[i];
        int j = i - 1;

        while (j >= 0 && values[j] < value) {
            values[j + 1] = values[j];
            j--;
        }
        values[j + 1] = value;
    }
}

static bool load_policy(cpu_policy *policy, const char *name)
{
    char buf[512];
    char freqs_path[PATH_MAX];
    char *cursor;
    int count = 0;

    snprintf(policy->dir, sizeof(policy->dir), "%s/%s", CPU_CPUFREQ_BASE, name);
    snprintf(policy->max_path, sizeof(policy->max_path), "%s/scaling_max_freq",
             policy->dir);
    snprintf(policy->gov_path, sizeof(policy->gov_path), "%s/scaling_governor",
             policy->dir);
    snprintf(policy->cur_path, sizeof(policy->cur_path), "%s/scaling_cur_freq",
             policy->dir);
    snprintf(policy->govs_path, sizeof(policy->govs_path),
             "%s/scaling_available_governors", policy->dir);
    if (sysfs_read_str(policy->govs_path, policy->govs, sizeof(policy->govs)) < 0)
        policy->govs[0] = '\0';
    snprintf(freqs_path, sizeof(freqs_path), "%s/scaling_available_frequencies",
             policy->dir);
    if (sysfs_read_str(freqs_path, buf, sizeof(buf)) <= 0)
        return false;

    cursor = buf;
    while (*cursor && count < CPU_MAX_STEPS) {
        char *end = NULL;
        long khz;

        while (*cursor == ' ' || *cursor == '\t' || *cursor == '\n' ||
               *cursor == '\r')
            cursor++;
        if (!*cursor)
            break;
        errno = 0;
        khz = strtol(cursor, &end, 10);
        if (errno != 0 || end == cursor || khz <= 0 || khz > INT_MAX)
            break;
        policy->freqs_khz[count++] = (int)khz;
        cursor = end;
    }
    if (count == 0)
        return false;
    sort_khz_descending(policy->freqs_khz, count);
    policy->count = count;
    policy->available = true;
    policy->applied = false;
    policy->applied_khz = 0;
    return true;
}

/* The policy's first CPU: affected_cpus, then related_cpus, then the number in
 * the directory name. */
static int policy_first_cpu(const char *dir, const char *name)
{
    static const char *const attrs[] = {"affected_cpus", "related_cpus"};
    char buf[64];

    for (size_t i = 0; i < sizeof(attrs) / sizeof(attrs[0]); i++) {
        char path[PATH_MAX];

        snprintf(path, sizeof(path), "%s/%s", dir, attrs[i]);
        if (sysfs_read_str(path, buf, sizeof(buf)) > 0) {
            char *end = NULL;
            long cpu = strtol(buf, &end, 10);

            if (end != buf && cpu >= 0 && cpu < 64)
                return (int)cpu;
        }
    }
    if (!strncmp(name, "policy", 6) && name[6])
        return atoi(name + 6);
    return -1;
}

static void cpu_scan(cpu_state *state)
{
    static bool logged_missing;
    cpu_policy found[CPU_CLUSTER_COUNT];
    cpu_cluster cluster[CPU_CLUSTER_COUNT];
    bool used[CPU_CLUSTER_COUNT] = {false, false, false};
    int count = 0;
    char base[PATH_MAX];
    DIR *dir;
    struct dirent *de;

    memset(state->policy, 0, sizeof(state->policy));
    state->count = 0;
    state->available = false;

    dir = opendir(plat_resolve(CPU_CPUFREQ_BASE, base, sizeof(base)));
    if (!dir) {
        if (!logged_missing) {
            logged_missing = true;
            ah_logf(AH_LOG_WARN,
                    "cpu: %s unavailable; caps and governor not applied",
                    CPU_CPUFREQ_BASE);
        }
        return;
    }
    while ((de = readdir(dir)) != NULL && count < CPU_CLUSTER_COUNT) {
        cpu_policy policy;

        if (strncmp(de->d_name, "policy", 6) != 0)
            continue;
        memset(&policy, 0, sizeof(policy));
        if (!load_policy(&policy, de->d_name))
            continue;
        found[count] = policy;
        cluster[count] = cpu_cluster_from_cpu(policy_first_cpu(policy.dir,
                                                               de->d_name));
        count++;
    }
    closedir(dir);

    /* Place the policies whose CPU ids mapped cleanly first... */
    {
        bool assigned[CPU_CLUSTER_COUNT] = {false, false, false};

        for (int i = 0; i < count; i++) {
            if (cluster[i] == CPU_CLUSTER_NONE || used[cluster[i]])
                continue;
            state->policy[cluster[i]] = found[i];
            used[cluster[i]] = true;
            assigned[i] = true;
        }
        /* ... then fill the remaining clusters in ascending top-frequency
         * order: the slowest unassigned policy is silver, the fastest prime. */
        for (int c = 0; c < CPU_CLUSTER_COUNT; c++) {
            int pick = -1;

            if (used[c])
                continue;
            for (int i = 0; i < count; i++) {
                if (assigned[i])
                    continue;
                if (pick < 0 || found[i].freqs_khz[0] < found[pick].freqs_khz[0])
                    pick = i;
            }
            if (pick < 0)
                break;
            state->policy[c] = found[pick];
            used[c] = true;
            assigned[pick] = true;
        }
    }

    for (int c = 0; c < CPU_CLUSTER_COUNT; c++) {
        const cpu_policy *p = &state->policy[c];

        if (!p->available)
            continue;
        state->count++;
        ah_logf(AH_LOG_INFO, "cpu: %s %s %d..%d MHz, governor %s",
                c == CPU_CLUSTER_SILVER ? "silver"
                : c == CPU_CLUSTER_GOLD ? "gold"
                                        : "prime",
                strrchr(p->dir, '/') ? strrchr(p->dir, '/') + 1 : p->dir,
                khz_to_mhz(p->freqs_khz[p->count - 1]),
                khz_to_mhz(p->freqs_khz[0]),
                p->govs[0] ? p->govs : "unknown");
    }
    state->available = state->count > 0;
    if (!state->available && !logged_missing) {
        logged_missing = true;
        ah_logf(AH_LOG_WARN,
                "cpu: no cpufreq policy with scaling_available_frequencies; "
                "caps and governor not applied");
    }
}

void cpu_state_init(cpu_state *state)
{
    if (!state)
        return;
    memset(state, 0, sizeof(*state));
    snprintf(state->governor, sizeof(state->governor), "schedutil");
}

static void apply_governor(cpu_state *state, const char *request)
{
    for (int c = 0; c < CPU_CLUSTER_COUNT; c++) {
        cpu_policy *p = &state->policy[c];
        char current[CPU_GOV_LEN];
        const char *chosen;

        if (!p->available)
            continue;
        chosen = cpu_validate_governor(request, p->govs[0] ? p->govs : NULL);
        /* Record the effective name even when the kernel already runs it (a
         * daemon restart must report what is applied, not the default). */
        if (strcmp(state->governor, chosen) != 0) {
            ah_logf(AH_LOG_INFO, "cpu: governor %s (requested %s)", chosen,
                    request && request[0] ? request : "schedutil");
            snprintf(state->governor, sizeof(state->governor), "%s", chosen);
        }
        if (sysfs_read_str(p->gov_path, current, sizeof(current)) > 0 &&
            !strcmp(current, chosen))
            continue;
        if (sysfs_write_str(p->gov_path, chosen) != 0) {
            static bool logged_gov[CPU_CLUSTER_COUNT];

            /* One line per cluster, not one per retry. */
            if (!logged_gov[c]) {
                logged_gov[c] = true;
                ah_logf(AH_LOG_WARN, "cpu: cannot write %s", p->gov_path);
            }
        }
    }
}

int cpu_apply(cpu_state *state, int silver_mhz, int gold_mhz, int prime_mhz,
              const char *governor)
{
    int request[CPU_CLUSTER_COUNT];

    if (!state)
        return -1;
    if (!state->available)
        cpu_scan(state);
    if (!state->available)
        return -1;

    request[CPU_CLUSTER_SILVER] = silver_mhz;
    request[CPU_CLUSTER_GOLD] = gold_mhz;
    request[CPU_CLUSTER_PRIME] = prime_mhz;

    for (int c = 0; c < CPU_CLUSTER_COUNT; c++) {
        cpu_policy *p = &state->policy[c];
        int target_khz;
        int readback = 0;

        if (!p->available)
            continue;
        if (request[c] < 0)
            request[c] = 0;
        if (request[c] > CPU_MAX_MHZ)
            request[c] = CPU_MAX_MHZ;
        target_khz = cpu_pick_khz(p, request[c]);
        p->request_mhz = request[c];
        if (!p->applied || p->applied_khz != target_khz) {
            if (sysfs_write_int(p->max_path, target_khz) != 0) {
                static bool logged_write[CPU_CLUSTER_COUNT];

                p->applied = false;
                p->applied_khz = 0;
                /* One line per cluster, not one per retry. */
                if (!logged_write[c]) {
                    logged_write[c] = true;
                    ah_logf(AH_LOG_WARN, "cpu: cannot write %s", p->max_path);
                }
                continue;
            }
            p->applied = true;
            p->applied_khz = target_khz;
            if (sysfs_read_int(p->max_path, &readback) == 0 && readback > 0)
                p->applied_khz = readback;
            ah_logf(AH_LOG_INFO, "cpu: %s cap %d MHz%s",
                    c == CPU_CLUSTER_SILVER ? "silver"
                    : c == CPU_CLUSTER_GOLD ? "gold"
                                            : "prime",
                    khz_to_mhz(p->applied_khz),
                    request[c] > 0 ? "" : " (stock)");
        }
    }

    apply_governor(state, governor);
    state->governor_applied = true;
    return 0;
}

bool cpu_needs_retry(const cpu_state *state)
{
    if (!state || !state->available)
        return true;
    for (int c = 0; c < CPU_CLUSTER_COUNT; c++) {
        if (state->policy[c].available && !state->policy[c].applied)
            return true;
    }
    return false;
}

int cpu_status_mhz(const cpu_state *state, cpu_cluster cluster)
{
    int khz = 0;

    if (!state || cluster < 0 || cluster >= CPU_CLUSTER_COUNT)
        return -1;
    if (!state->policy[cluster].available)
        return -1;
    if (sysfs_read_int(state->policy[cluster].cur_path, &khz) < 0 || khz <= 0)
        return -1;
    return khz_to_mhz(khz);
}

const char *cpu_governor_name(const cpu_state *state)
{
    if (!state || !state->governor[0])
        return "schedutil";
    return state->governor;
}
