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

    private Perf() {
    }

    /** Whether the runtime override is currently active. */
    public static boolean overrideActive() {
        return Props.getBoolean(THROTTLE_OVERRIDE, false);
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
