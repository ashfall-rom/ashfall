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
 * Software clock-throttling override (docs/phase5.md §6.1).
 *
 * <p>{@link #THROTTLE_OVERRIDE} is an {@code ashfall_perf_prop}: it is not
 * persistent, so it always resets on reboot.  The hidden
 * {@link #THROTTLE_OVERRIDE_PERMANENT} switch makes {@code ashfall-hwd} apply
 * the override at every boot.
 *
 * <p>Only the software mitigation (kernel thermal cooling devices) is
 * disabled; hardware limits stay in place.
 */
public final class Perf {

    public static final String THROTTLE_OVERRIDE = "ashfall.perf.throttle_override";
    public static final String THROTTLE_OVERRIDE_PERMANENT =
            "persist.ashfall.perf.throttle_override_permanent";
    /** Daemon state: 1 active, 0 restored, -1 unsupported/partial. */
    public static final String STATUS_THROTTLE = "ashfall.status.perf.throttle";

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
        return Props.getInt(STATUS_THROTTLE, Integer.MIN_VALUE);
    }

    /** False when the daemon reports the override as unsupported/partial. */
    public static boolean overrideSupported() {
        return status() != -1;
    }

    public static void setOverride(boolean active) {
        Props.set(THROTTLE_OVERRIDE, active ? "1" : "0");
    }

    /** Whether the override is re-applied at every boot. */
    public static boolean permanent() {
        return Props.getBoolean(THROTTLE_OVERRIDE_PERMANENT, false);
    }

    public static void setPermanent(boolean permanent) {
        Props.set(THROTTLE_OVERRIDE_PERMANENT, permanent ? "1" : "0");
    }
}
