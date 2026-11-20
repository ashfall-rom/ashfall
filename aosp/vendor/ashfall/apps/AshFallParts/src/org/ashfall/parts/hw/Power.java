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

package org.ashfall.parts.hw;

import android.content.Context;
import android.os.SystemClock;

import org.ashfall.parts.ipc.Props;

/**
 * Power property interface (#124, docs/phase6.md §9.3).
 *
 * <p>The UI never touches sysfs: it writes the {@code persist.ashfall.perf.*}
 * properties (the caps, the governor and the GPU cap), {@code ashfall-hwd}
 * applies them and publishes the effective values as {@code ashfall.status.perf.*}.
 * The app only reads a status property, it never writes one.  A missing or
 * invalid value is {@code -1}, never a fabricated one.
 *
 * <p>The battery power of the live graph is the one exception: it is sampled
 * with {@link PowerBattery} (framework {@code BatteryManager}), because the
 * daemon's battery sampler was removed (a coredomain cannot read
 * {@code sysfs_batteryinfo}).  CPU and GPU clocks still come from the status
 * properties.
 *
 * <p>The active preset id lives in {@code persist.ashfall.power.preset} and is
 * this app's own state; the daemon only ever sees the applied values.
 *
 * <p>While a power UI is visible the app sets {@code ashfall.ui.power_live=1},
 * so {@code ashfall-hwd} publishes the perf status every 500 ms instead of its
 * own 2 s tick; the page plots the real CPU/GPU load it publishes (#156 §2/§3).
 */
public final class Power {

    /** Per-cluster CPU cap in MHz; {@code 0} = stock maximum. */
    public static final String CPU_MAX_SILVER = "persist.ashfall.perf.cpu_max_silver";
    public static final String CPU_MAX_GOLD = "persist.ashfall.perf.cpu_max_gold";
    public static final String CPU_MAX_PRIME = "persist.ashfall.perf.cpu_max_prime";
    /** One governor for all three policies; default {@code schedutil}. */
    public static final String CPU_GOV = "persist.ashfall.perf.cpu_gov";
    /** App-only: the id of the active power preset. */
    public static final String PRESET = "persist.ashfall.power.preset";

    /** {@code 1} while a power UI is visible: the daemon publishes every 500 ms. */
    public static final String UI_LIVE = "ashfall.ui.power_live";

    public static final String STATUS_CPU_MHZ_SILVER = "ashfall.status.perf.cpu_mhz_silver";
    public static final String STATUS_CPU_MHZ_GOLD = "ashfall.status.perf.cpu_mhz_gold";
    public static final String STATUS_CPU_MHZ_PRIME = "ashfall.status.perf.cpu_mhz_prime";
    public static final String STATUS_GPU_MHZ = "ashfall.status.perf.gpu_mhz";
    /** Real CPU utilisation 0..100 from {@code /proc/stat}; {@code -1} unknown. */
    public static final String STATUS_CPU_LOAD_PCT = "ashfall.status.perf.cpu_load_pct";
    /** Real GPU utilisation 0..100 from kgsl {@code gpu_busy_percentage}; {@code -1}. */
    public static final String STATUS_GPU_LOAD_PCT = "ashfall.status.perf.gpu_load_pct";

    /** Stock top step of each cluster; no preset goes above it. */
    public static final int SILVER_STOCK_MHZ = 1804;
    public static final int GOLD_STOCK_MHZ = 2419;
    public static final int PRIME_STOCK_MHZ = 2841;
    /** Lowest step of each cluster: the floor of the cap sliders. */
    public static final int SILVER_MIN_MHZ = 300;
    public static final int GOLD_MIN_MHZ = 710;
    public static final int PRIME_MIN_MHZ = 844;

    public static final String GOV_SCHEDUTIL = "schedutil";
    public static final String GOV_CONSERVATIVE = "conservative";
    public static final String GOV_POWERSAVE = "powersave";
    public static final String GOV_PERFORMANCE = "performance";
    /**
     * The governors the page offers.  {@code walt} stays hidden (the kernel
     * exposes it, but there is no use case that does not break the preset
     * model); {@code performance} was added in #156 §6 and the built-in
     * Performance preset writes it.
     */
    public static final String[] GOVERNORS = {
            GOV_SCHEDUTIL, GOV_CONSERVATIVE, GOV_POWERSAVE, GOV_PERFORMANCE,
    };

    /** GPU cap range; a preset may carry every step, 940 and 1000 included (#156 §5). */
    public static final int GPU_MIN_MHZ = 305;
    public static final int GPU_STOCK_MHZ = 670;
    public static final int GPU_MAX_MHZ = 1000;
    /** The GPU step of the built-in Performance preset (#124). */
    public static final int PERFORMANCE_GPU_MHZ = 850;
    /** The step that needs the thermal/stability confirmation. */
    public static final int GPU_EXPERIMENTAL_MHZ = 1000;
    /**
     * The GPU steps the selectors offer.  {@code Perf.GPU_STEPS} lacks 305,
     * which the Low power preset needs, so the Power page keeps its own list.
     */
    public static final int[] GPU_STEPS = {305, 670, 765, 850, 940, 1000};

    /** Slider floor of the CPU caps: what the daemon falls back to. */
    public static final int STOCK_CAP_MHZ = 0;

    /** Number of open power UIs; the live flag stays set until the last one closes. */
    private static int sLiveCount;

    /**
     * Test-only switch for the emulator preview: {@code adb shell setprop
     * debug.ashfall.parts.power_mock 1}.  It makes the whole page (caps,
     * governor, GPU, throttle override and the published status) talk to an
     * in-process fake daemon, because the stock emulator image has no
     * {@code ashfall.*} property contexts.  Production never sets it and a
     * user build ignores it, exactly like {@code Perf.MOCK_PROP}.
     */
    public static final String MOCK_PROP = "debug.ashfall.parts.power_mock";

    private Power() {
    }

    // ---------------------------------------------------------------------
    // Settings the app writes
    // ---------------------------------------------------------------------

    /** The active cluster cap in MHz; {@code 0} (stock) reads as the stock top. */
    public static int cpuCapSilverMhz() {
        return capMhz(rawCapSilver(), SILVER_MIN_MHZ, SILVER_STOCK_MHZ);
    }

    public static int cpuCapGoldMhz() {
        return capMhz(rawCapGold(), GOLD_MIN_MHZ, GOLD_STOCK_MHZ);
    }

    public static int cpuCapPrimeMhz() {
        return capMhz(rawCapPrime(), PRIME_MIN_MHZ, PRIME_STOCK_MHZ);
    }

    private static int rawCapSilver() {
        return mockEnabled() ? Mock.silver : Props.getInt(CPU_MAX_SILVER, STOCK_CAP_MHZ);
    }

    private static int rawCapGold() {
        return mockEnabled() ? Mock.gold : Props.getInt(CPU_MAX_GOLD, STOCK_CAP_MHZ);
    }

    private static int rawCapPrime() {
        return mockEnabled() ? Mock.prime : Props.getInt(CPU_MAX_PRIME, STOCK_CAP_MHZ);
    }

    /**
     * {@code 0} is the contract's "stock maximum"; the UI works in MHz, so the
     * getters resolve it to the stock top and the setters store the clamp.
     */
    private static int capMhz(int raw, int min, int stock) {
        return raw <= STOCK_CAP_MHZ ? stock : clampMhz(raw, min, stock);
    }

    public static void setCpuCapSilverMhz(int mhz) {
        final int value = clampMhz(mhz, SILVER_MIN_MHZ, SILVER_STOCK_MHZ);
        if (mockEnabled()) {
            Mock.silver = value;
            return;
        }
        Props.setInt(CPU_MAX_SILVER, value);
    }

    public static void setCpuCapGoldMhz(int mhz) {
        final int value = clampMhz(mhz, GOLD_MIN_MHZ, GOLD_STOCK_MHZ);
        if (mockEnabled()) {
            Mock.gold = value;
            return;
        }
        Props.setInt(CPU_MAX_GOLD, value);
    }

    public static void setCpuCapPrimeMhz(int mhz) {
        final int value = clampMhz(mhz, PRIME_MIN_MHZ, PRIME_STOCK_MHZ);
        if (mockEnabled()) {
            Mock.prime = value;
            return;
        }
        Props.setInt(CPU_MAX_PRIME, value);
    }

    /** Writes {@code 0} (stock maximum) to all three caps. */
    public static void clearCpuCaps() {
        if (mockEnabled()) {
            Mock.silver = SILVER_STOCK_MHZ;
            Mock.gold = GOLD_STOCK_MHZ;
            Mock.prime = PRIME_STOCK_MHZ;
            return;
        }
        Props.setInt(CPU_MAX_SILVER, STOCK_CAP_MHZ);
        Props.setInt(CPU_MAX_GOLD, STOCK_CAP_MHZ);
        Props.setInt(CPU_MAX_PRIME, STOCK_CAP_MHZ);
    }

    public static String governor() {
        if (mockEnabled()) {
            return Mock.governor;
        }
        return sanitizeGovernor(Props.get(CPU_GOV, GOV_SCHEDUTIL));
    }

    public static void setGovernor(String governor) {
        final String value = sanitizeGovernor(governor);
        if (mockEnabled()) {
            Mock.governor = value;
            return;
        }
        Props.set(CPU_GOV, value);
    }

    /** With {@code powersave} every policy is pinned to its lowest step. */
    public static boolean capsInert() {
        return GOV_POWERSAVE.equals(governor());
    }

    public static String presetId() {
        return mockEnabled() ? Mock.presetId : Props.get(PRESET, "");
    }

    public static void setPresetId(String id) {
        final String value = id == null ? "" : id;
        if (mockEnabled()) {
            Mock.presetId = value;
            return;
        }
        Props.set(PRESET, value);
    }

    /**
     * The active preset: the recorded id when it still exists, otherwise the
     * stock-equivalent built-in.  The page, the tile, the dialog and the
     * Handheld summary all resolve it here, so a stale id can never make the
     * page disagree with the tile.
     */
    public static PowerPreset activePreset(PowerPresetStore store) {
        if (store == null) {
            return null;
        }
        final PowerPreset recorded = store.byId(presetId());
        if (recorded != null) {
            return recorded;
        }
        final PowerPreset balanced = store.byId(PowerPresetStore.ID_BALANCED);
        if (balanced != null) {
            return balanced;
        }
        final java.util.List<PowerPreset> all = store.all();
        return all.isEmpty() ? null : all.get(0);
    }

    /** Applies a preset: the caps, the governor, the GPU cap, then its id. */
    public static void apply(PowerPreset preset) {
        if (preset == null) {
            return;
        }
        setCpuCapSilverMhz(preset.silverMhz);
        setCpuCapGoldMhz(preset.goldMhz);
        setCpuCapPrimeMhz(preset.primeMhz);
        setGovernor(preset.governor);
        setGpuMhz(preset.gpuMhz);
        setPresetId(preset.id);
    }

    /**
     * "Reset to stock": every control on the page returns to what the kernel
     * ships with.  The caps go back to {@code 0} rather than to 1804/2419/2841,
     * because {@code 0} is the contract's own "stock maximum".  The page also
     * restores the built-in Balanced preset, so the preset row cannot claim
     * values the properties do not hold.
     */
    public static void resetToStock() {
        clearCpuCaps();
        setGovernor(GOV_SCHEDUTIL);
        setGpuMhz(GPU_STOCK_MHZ);
        setThrottleOverride(false);
        setPresetId(PowerPresetStore.ID_BALANCED);
    }

    /**
     * Marks a power UI (page, preset editor, Quick Settings dialog) as visible.
     * A counter is used because more than one can be visible at the same time;
     * {@code ashfall-hwd} publishes the perf status every 500 ms while it is
     * set and returns to its own 2 s tick when the last UI closes (#156 §2).
     */
    public static synchronized void setLive(boolean live) {
        if (live) {
            sLiveCount++;
        } else if (sLiveCount > 0) {
            sLiveCount--;
        }
        refreshLive();
    }

    /** Re-publishes the current live flag (used by the 500 ms page refresh). */
    public static synchronized void refreshLive() {
        Props.set(UI_LIVE, sLiveCount > 0 ? "1" : "0");
    }

    // ---------------------------------------------------------------------
    // GPU cap and throttle override (same properties, moved pages)
    // ---------------------------------------------------------------------

    public static int gpuRequestedMhz() {
        return mockEnabled() ? Mock.gpu : Perf.gpuRequestedMhz();
    }

    public static void setGpuMhz(int mhz) {
        if (mockEnabled()) {
            Mock.gpu = clampMhz(mhz, GPU_MIN_MHZ, Perf.GPU_MAX_MHZ_VALUE);
            return;
        }
        Perf.setGpuRequestedMhz(mhz);
    }

    /** The cap the daemon actually applied, or {@link Integer#MIN_VALUE}. */
    public static int gpuActualMhz() {
        if (mockEnabled()) {
            return mockGpuCap();
        }
        return Perf.gpuActualMhz();
    }

    /** 0 stock, 1 OC, -1 sysfs unavailable, {@link Integer#MIN_VALUE} unknown. */
    public static int gpuOc() {
        if (mockEnabled()) {
            return mockGpuCap() > GPU_STOCK_MHZ ? 1 : 0;
        }
        return Perf.gpuOc();
    }

    /** An above-stock request published as stock: the override clamps the GPU. */
    public static boolean gpuClampedToStock() {
        return gpuRequestedMhz() > GPU_STOCK_MHZ && gpuOc() == 0;
    }

    public static boolean throttleActive() {
        return mockEnabled() ? Mock.override : Perf.overrideActive();
    }

    public static void setThrottleOverride(boolean active) {
        if (mockEnabled()) {
            Mock.override = active;
            return;
        }
        Perf.setOverride(active);
    }

    public static boolean throttleSupported() {
        return mockEnabled() || Perf.overrideSupported();
    }

    public static boolean throttlePermanent() {
        return mockEnabled() ? Mock.permanent : Perf.permanent();
    }

    public static void setThrottlePermanent(boolean permanent) {
        if (mockEnabled()) {
            Mock.permanent = permanent;
            return;
        }
        Perf.setPermanent(permanent);
    }

    // ---------------------------------------------------------------------
    // Telemetry (read-only)
    // ---------------------------------------------------------------------

    /**
     * Battery power in milliwatts, {@code -1} while charging and when unknown.
     * Sampled with {@link PowerBattery}, not from a daemon property.
     */
    public static int powerMw(Context context) {
        if (mockEnabled()) {
            return mockPowerMw();
        }
        return PowerBattery.powerMw(context);
    }

    /** Whether a battery reading exists at all, independent of charging. */
    public static boolean powerAvailable(Context context) {
        return mockEnabled() || PowerBattery.available(context);
    }

    /** Whether the {@code -1} power sample means "charging" (not "unknown"). */
    public static boolean powerCharging(Context context) {
        return mockEnabled() ? mockCharging() : PowerBattery.charging(context);
    }

    public static int cpuMhzSilver() {
        if (mockEnabled()) {
            return mockSilver();
        }
        return statusMhz(STATUS_CPU_MHZ_SILVER);
    }

    public static int cpuMhzGold() {
        if (mockEnabled()) {
            return mockGold();
        }
        return statusMhz(STATUS_CPU_MHZ_GOLD);
    }

    public static int cpuMhzPrime() {
        if (mockEnabled()) {
            return mockPrime();
        }
        return statusMhz(STATUS_CPU_MHZ_PRIME);
    }

    public static int gpuMhz() {
        if (mockEnabled()) {
            return mockGpu();
        }
        return statusMhz(STATUS_GPU_MHZ);
    }

    /**
     * Real CPU utilisation 0..100 from the daemon ({@code /proc/stat} idle delta
     * over the total delta), or {@code -1} when it is not published.  The page
     * plots this; it never derives usage from the clocks (#156 §3).
     */
    public static int cpuLoadPct() {
        if (mockEnabled()) {
            return mockCpuLoad();
        }
        return statusPercent(STATUS_CPU_LOAD_PCT);
    }

    /** Real GPU utilisation 0..100 from kgsl {@code gpu_busy_percentage}, or -1. */
    public static int gpuLoadPct() {
        if (mockEnabled()) {
            return mockGpuLoad();
        }
        return statusPercent(STATUS_GPU_LOAD_PCT);
    }

    /** Whether any real utilisation sample exists. */
    public static boolean loadAvailable() {
        return cpuLoadPct() >= 0 || gpuLoadPct() >= 0;
    }

    /** The fastest cluster currently running, or {@code -1}. */
    public static int cpuMhzMax() {
        return Math.max(cpuMhzSilver(), Math.max(cpuMhzGold(), cpuMhzPrime()));
    }

    /**
     * False when {@code ashfall-hwd} has not published any perf status yet.  The
     * battery series is independent of the daemon, so it no longer counts.
     */
    public static boolean daemonAlive() {
        return cpuMhzMax() >= 0 || gpuMhz() >= 0 || loadAvailable();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    public static boolean mockEnabled() {
        // Development images only: the flag is a debug.* property and an
        // in-process fake daemon must never drive a production build.
        final String type = android.os.Build.TYPE;
        return ("userdebug".equals(type) || "eng".equals(type))
                && "1".equals(Props.get(MOCK_PROP, "0"));
    }

    /** {@code schedutil} for anything the kernel does not offer. */
    public static String sanitizeGovernor(String governor) {
        for (String known : GOVERNORS) {
            if (known.equals(governor)) {
                return governor;
            }
        }
        return GOV_SCHEDUTIL;
    }

    /** Every GPU step is valid in a preset now, 940 and 1000 MHz included. */
    public static int sanitizePresetGpuMhz(int mhz) {
        return clampMhz(mhz, GPU_MIN_MHZ, GPU_MAX_MHZ);
    }

    public static int clampMhz(int mhz, int min, int max) {
        return Math.max(min, Math.min(max, mhz));
    }

    private static int statusMhz(String key) {
        final int value = Props.getInt(key, -1);
        return value > 0 ? value : -1;
    }

    /** A 0..100 status value; anything else is "unknown" and becomes a gap. */
    private static int statusPercent(String key) {
        final int value = Props.getInt(key, -1);
        return value >= 0 && value <= 100 ? value : -1;
    }

    // ---------------------------------------------------------------------
    // Emulator fake daemon (test-only)
    // ---------------------------------------------------------------------

    private static final long MOCK_TICK_MS = 500;

    private static long mockTick() {
        return SystemClock.elapsedRealtime() / MOCK_TICK_MS;
    }

    /** A 120-tick (60 s) cycle with a 24-tick charging window (power = -1). */
    private static boolean mockCharging() {
        return mockTick() % 120 >= 96;
    }

    private static int mockPowerMw() {
        final long tick = mockTick() % 120;
        if (tick >= 96) {
            return -1;
        }
        final double phase = tick / 120.0 * 2 * Math.PI;
        return (int) (2600 + 2100 * (1 + Math.sin(phase)));
    }

    private static int mockSilver() {
        return mockClock(Mock.silver, 900, 700);
    }

    private static int mockGold() {
        return mockClock(Mock.gold, 1200, 900);
    }

    private static int mockPrime() {
        return mockClock(Mock.prime, 1500, 1200);
    }

    private static int mockClock(int cap, int base, int swing) {
        final double phase = mockTick() / 120.0 * 2 * Math.PI;
        final int value = (int) (base + swing * (1 + Math.sin(phase)) / 2);
        return Math.min(cap, value);
    }

    private static int mockGpu() {
        final double phase = mockTick() / 120.0 * 2 * Math.PI + 1.2;
        final int value = (int) (305 + 300 * (1 + Math.sin(phase)) / 2);
        return Math.min(mockGpuCap(), value);
    }

    /**
     * A synthetic idle-to-busy cycle, floored by the stress test's own CPU
     * level so the emulator shows the load rising while the page's stress
     * controls run (the real daemon measures {@code /proc/stat} instead).
     */
    private static int mockCpuLoad() {
        final double phase = mockTick() / 120.0 * 2 * Math.PI;
        final int idle = (int) (8 + 22 * (1 + Math.sin(phase - 0.6)) / 2);
        return Math.max(idle, Stress.get().cpuPct());
    }

    private static int mockGpuLoad() {
        final double phase = mockTick() / 120.0 * 2 * Math.PI + 0.9;
        final int idle = (int) (4 + 40 * (1 + Math.sin(phase)) / 2);
        return Math.max(idle, Stress.get().gpuPct());
    }

    /** The fake daemon clamps like the real one: the override wins, then the table. */
    private static int mockGpuCap() {
        if (Mock.override) {
            return GPU_STOCK_MHZ;
        }
        int cap = GPU_MIN_MHZ;
        for (int frequency : GPU_STEPS) {
            if (frequency <= Mock.gpu && frequency > cap) {
                cap = frequency;
            }
        }
        return cap;
    }

    /** In-process state of the emulator fake daemon (test-only). */
    private static final class Mock {
        static int silver = SILVER_STOCK_MHZ;
        static int gold = GOLD_STOCK_MHZ;
        static int prime = PRIME_STOCK_MHZ;
        static String governor = GOV_SCHEDUTIL;
        static String presetId = "";
        static int gpu = GPU_STOCK_MHZ;
        static boolean override;
        static boolean permanent;
    }
}
