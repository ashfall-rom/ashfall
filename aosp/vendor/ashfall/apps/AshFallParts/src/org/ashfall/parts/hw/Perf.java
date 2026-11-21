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

import org.ashfall.parts.ipc.Props;

/**
 * The Performance section: the software clock-throttling override
 * (docs/phase5.md §6.1) and the GPU frequency cap (docs/phase6.md §3).
 *
 * <p>{@link #THROTTLE_OVERRIDE} is an {@code ashfall_perf_prop}: it is not
 * persistent, so it always resets on reboot.  The hidden
 * {@link #THROTTLE_OVERRIDE_PERMANENT} switch makes {@code ashfall-hwd} apply
 * the override at every boot.  Only the software mitigation (kernel thermal
 * cooling devices) is disabled; hardware limits stay in place.
 *
 * <p>The GPU cap is a second, independent setting: this app writes only
 * {@link #GPU_MAX_MHZ} ({@code ashfall_perf_prop}) and {@code ashfall-hwd}
 * publishes the actual cap and the OC state in {@link #STATUS_GPU_MAX_MHZ} and
 * {@link #STATUS_GPU_OC} ({@code ashfall_status_prop}).  The app never writes a
 * status property.
 */
public final class Perf {

    public static final String THROTTLE_OVERRIDE = "ashfall.perf.throttle_override";
    public static final String THROTTLE_OVERRIDE_PERMANENT =
            "persist.ashfall.perf.throttle_override_permanent";
    /** Daemon state: 1 active, 0 restored, -1 unsupported/partial. */
    public static final String STATUS_THROTTLE = "ashfall.status.perf.throttle";

    /** Requested GPU cap: 305..1000 MHz, default 670 (docs/phase6.md §3). */
    public static final String GPU_MAX_MHZ = "persist.ashfall.perf.gpu_max_mhz";
    /**
     * GPU idle timer request in ms (issue #130): 0 keeps the stock 80 ms,
     * 10000 is the "keep the GPU clock between bursts" Performance option.
     * The value belongs to the applied preset, not to a global setting.
     */
    public static final String GPU_IDLE_MS = "persist.ashfall.perf.gpu_idle_ms";
    /** Stock KGSL idle timer, and the value the Power editor offers. */
    public static final int GPU_IDLE_STOCK_MS = 80;
    public static final int GPU_IDLE_PERFORMANCE_MS = 10000;
    public static final int GPU_IDLE_MAX_MS = 60000;
    /** Actual cap applied by {@code ashfall-hwd}, in MHz. */
    public static final String STATUS_GPU_MAX_MHZ = "ashfall.status.perf.gpu_max_mhz";
    /** Daemon state: 0 stock (<= 670), 1 OC (> 670), -1 sysfs unavailable. */
    public static final String STATUS_GPU_OC = "ashfall.status.perf.gpu_oc";

    /** The stock top step and the boot/default cap. */
    public static final int GPU_STOCK_MHZ = 670;
    /** The lowest step the daemon accepts for the requested cap. */
    public static final int GPU_MIN_MHZ = 305;
    /** The highest step the daemon accepts (experimental, opt-in). */
    public static final int GPU_MAX_MHZ_VALUE = 1000;
    /** The step that needs the thermal/stability confirmation. */
    public static final int GPU_EXPERIMENTAL_MHZ = 1000;
    /** The five selectable steps, in ascending order. */
    public static final int[] GPU_STEPS = {670, 765, 850, 940, 1000};

    /**
     * Test-only switch for the emulator preview: {@code adb shell setprop
     * debug.ashfall.parts.gpu_mock 1}.  When it is set the Performance category
     * talks to an in-process fake daemon instead of the (absent) properties, so
     * the selector, the confirmation and the throttle-override clamp can be
     * exercised without {@code ashfall-hwd}.  Production never sets it, and the
     * {@code debug.} prefix is the only property the stock emulator image lets
     * the shell write without the AshFall property contexts.
     */
    public static final String MOCK_PROP = "debug.ashfall.parts.gpu_mock";

    /**
     * Frequencies the stock and phase-6 tables expose; the mock clamps a
     * request to the highest one that is {@code <=} the request, exactly like
     * the daemon does with {@code gpu_available_frequencies}.
     */
    private static final int[] GPU_AVAILABLE_MHZ = {
            305, 400, 441, 490, 525, 587, 670, 765, 850, 940, 1000,
    };

    private Perf() {
    }

    /**
     * Whether the override is currently active.
     *
     * <p>A value the UI just wrote wins (the daemon follows within a second, so
     * the switch must not bounce back while it catches up).  After a reboot
     * the non-persistent property is empty; then the daemon's published state
     * {@link #STATUS_THROTTLE} is authoritative, and a permanent override is
     * the fallback until the daemon has published anything (#99).
     */
    public static boolean overrideActive() {
        if (mockEnabled()) {
            return Mock.override;
        }
        final String requested = Props.get(THROTTLE_OVERRIDE, "");
        if (!requested.isEmpty()) {
            return "1".equals(requested);
        }
        final int status = status();
        if (status != Integer.MIN_VALUE) {
            return status == 1;
        }
        return permanent();
    }

    /** Daemon state, or {@link Integer#MIN_VALUE} before the first publish. */
    public static int status() {
        if (mockEnabled()) {
            return Mock.throttleStatus;
        }
        return Props.getInt(STATUS_THROTTLE, Integer.MIN_VALUE);
    }

    /** False when the daemon reports the override as unsupported/partial. */
    public static boolean overrideSupported() {
        return status() != -1;
    }

    public static void setOverride(boolean active) {
        if (mockEnabled()) {
            Mock.override = active;
            Mock.throttleStatus = active ? 1 : 0;
            return;
        }
        Props.set(THROTTLE_OVERRIDE, active ? "1" : "0");
    }

    /** Whether the override is re-applied at every boot. */
    public static boolean permanent() {
        if (mockEnabled()) {
            return Mock.permanent;
        }
        return Props.getBoolean(THROTTLE_OVERRIDE_PERMANENT, false);
    }

    public static void setPermanent(boolean permanent) {
        if (mockEnabled()) {
            Mock.permanent = permanent;
            return;
        }
        Props.set(THROTTLE_OVERRIDE_PERMANENT, permanent ? "1" : "0");
    }

    // ---------------------------------------------------------------------
    // GPU cap (docs/phase6.md §3)
    // ---------------------------------------------------------------------

    /**
     * The requested GPU cap in MHz.  Missing, unparsable or out-of-range values
     * read as the stock 670 MHz; the contract's value range is 305..1000.
     */
    public static int gpuRequestedMhz() {
        if (mockEnabled()) {
            return Mock.gpuRequested;
        }
        return sanitizeGpuMhz(Props.getInt(GPU_MAX_MHZ, GPU_STOCK_MHZ));
    }

    /** The requested GPU idle timer in ms; 0 = stock. */
    public static int gpuIdleMs() {
        if (mockEnabled()) {
            return Mock.gpuIdleMs;
        }
        return sanitizeGpuIdleMs(Props.getInt(GPU_IDLE_MS, 0));
    }

    /** Writes the idle timer request: 0 (stock) or 80..60000 ms. */
    public static void setGpuIdleMs(int ms) {
        final int value = sanitizeGpuIdleMs(ms);
        if (mockEnabled()) {
            Mock.gpuIdleMs = value;
            return;
        }
        Props.setInt(GPU_IDLE_MS, value);
    }

    /** Writes the requested cap; out-of-range values are stored as stock. */
    public static void setGpuRequestedMhz(int mhz) {
        final int value = sanitizeGpuMhz(mhz);
        if (mockEnabled()) {
            Mock.gpuRequested = value;
            return;
        }
        Props.setInt(GPU_MAX_MHZ, value);
    }

    /**
     * The actual cap published by {@code ashfall-hwd}, or
     * {@link Integer#MIN_VALUE} before the first publish / when it is invalid.
     */
    public static int gpuActualMhz() {
        if (mockEnabled()) {
            return mockCap();
        }
        final int value = Props.getInt(STATUS_GPU_MAX_MHZ, Integer.MIN_VALUE);
        return value >= GPU_MIN_MHZ && value <= GPU_MAX_MHZ_VALUE
                ? value : Integer.MIN_VALUE;
    }

    /**
     * The daemon's OC state: 1 OC, 0 stock, -1 sysfs unavailable, or
     * {@link Integer#MIN_VALUE} before the first publish.
     */
    public static int gpuOc() {
        if (mockEnabled()) {
            return mockCap() > GPU_STOCK_MHZ ? 1 : 0;
        }
        return Props.getInt(STATUS_GPU_OC, Integer.MIN_VALUE);
    }

    /**
     * True when an above-stock request is published as stock, i.e. the daemon
     * clamps the GPU while the throttle override is active (docs/phase6.md §3).
     */
    public static boolean gpuClampedToStock() {
        return gpuRequestedMhz() > GPU_STOCK_MHZ && gpuOc() == 0;
    }

    /** Whether the fake daemon is enabled (emulator preview only). */
    public static boolean mockEnabled() {
        // Development images only: the flag is a debug.* property and an
        // in-process fake daemon must never drive a production build, so a
        // user build ignores it (Build.TYPE is readable without SELinux
        // property access, unlike ro.debuggable).
        final String type = android.os.Build.TYPE;
        return ("userdebug".equals(type) || "eng".equals(type))
                && "1".equals(Props.get(MOCK_PROP, "0"));
    }

    private static int sanitizeGpuIdleMs(int ms) {
        if (ms <= 0) {
            return 0;
        }
        return Math.min(GPU_IDLE_MAX_MS, Math.max(GPU_IDLE_STOCK_MS, ms));
    }

    private static int sanitizeGpuMhz(int mhz) {
        return mhz >= GPU_MIN_MHZ && mhz <= GPU_MAX_MHZ_VALUE ? mhz : GPU_STOCK_MHZ;
    }

    /** The cap the fake daemon would apply: the override wins, then the table. */
    private static int mockCap() {
        if (Mock.override) {
            return GPU_STOCK_MHZ;
        }
        int cap = GPU_MIN_MHZ;
        for (int frequency : GPU_AVAILABLE_MHZ) {
            if (frequency <= Mock.gpuRequested && frequency > cap) {
                cap = frequency;
            }
        }
        return cap;
    }

    /** In-process state of the emulator fake daemon (test-only). */
    private static final class Mock {
        static int gpuRequested = GPU_STOCK_MHZ;
        static int gpuIdleMs;
        static boolean override;
        static boolean permanent;
        static int throttleStatus = 0;
    }
}
