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
 * Gamepad deadzone properties (docs/phase5.md §2.3) and the external-display
 * pointer switch (docs/rp5/input.md, #131).  The daemon applies the deadzones
 * after calibration, so they affect every app and the emulators; the pointer
 * switch is read live and only creates the second uinput device.
 */
public final class Pad {

    public static final String DEADZONE_INNER = "persist.ashfall.pad.deadzone_inner";
    public static final String DEADZONE_OUTER = "persist.ashfall.pad.deadzone_outer";
    public static final String TRIGGER_DEADZONE = "persist.ashfall.pad.trigger_deadzone";

    /** External-display gamepad pointer: 1 = on, anything else = off. */
    public static final String POINTER = "persist.ashfall.pad.pointer";

    public static final int MAX_INNER = 30;
    public static final int MAX_OUTER = 20;
    public static final int MAX_TRIGGER = 30;

    private Pad() {
    }

    public static int inner() {
        return clamp(Props.getInt(DEADZONE_INNER, 5), 0, MAX_INNER);
    }

    public static void setInner(int value) {
        Props.setInt(DEADZONE_INNER, clamp(value, 0, MAX_INNER));
    }

    public static int outer() {
        return clamp(Props.getInt(DEADZONE_OUTER, 3), 0, MAX_OUTER);
    }

    public static void setOuter(int value) {
        Props.setInt(DEADZONE_OUTER, clamp(value, 0, MAX_OUTER));
    }

    public static int trigger() {
        return clamp(Props.getInt(TRIGGER_DEADZONE, 3), 0, MAX_TRIGGER);
    }

    public static void setTrigger(int value) {
        Props.setInt(TRIGGER_DEADZONE, clamp(value, 0, MAX_TRIGGER));
    }

    /** The external-display pointer switch; off unless the property says 1. */
    public static boolean pointer() {
        return Props.getInt(POINTER, 0) == 1;
    }

    public static void setPointer(boolean on) {
        Props.setInt(POINTER, on ? 1 : 0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
