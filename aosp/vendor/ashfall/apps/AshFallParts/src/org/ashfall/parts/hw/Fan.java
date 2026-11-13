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
 * Fan property interface (docs/phase5.md §2.1).
 *
 * <p>The UI never touches sysfs: it writes the {@code persist.ashfall.fan.*}
 * properties and reads the {@code ashfall.status.fan.*} values written by
 * {@code ashfall-hwd}.
 */
public final class Fan {

    public static final String MODE = "persist.ashfall.fan.mode";
    public static final String MANUAL_PCT = "persist.ashfall.fan.manual_pct";
    public static final String CURVE = "persist.ashfall.fan.curve";
    public static final String SAFETY_C = "persist.ashfall.fan.safety_c";
    public static final String PRESET = "persist.ashfall.fan.preset";

    public static final String STATUS_TEMP = "ashfall.status.fan.temp";
    public static final String STATUS_PCT = "ashfall.status.fan.pct";
    public static final String STATUS_RPM = "ashfall.status.fan.rpm";
    public static final String STATUS_SAFETY = "ashfall.status.fan.safety";

    public static final int DEFAULT_MANUAL_PCT = 50;
    public static final int DEFAULT_SAFETY_C = 85;
    public static final int MIN_SAFETY_C = 70;
    public static final int MAX_SAFETY_C = 95;

    private Fan() {
    }

    public static String mode() {
        return Preset.sanitizeMode(Props.get(MODE, Preset.MODE_SMART));
    }

    public static void setMode(String mode) {
        Props.set(MODE, Preset.sanitizeMode(mode));
    }

    public static int manualPct() {
        return clamp(Props.getInt(MANUAL_PCT, DEFAULT_MANUAL_PCT), 0, 100);
    }

    public static void setManualPct(int pct) {
        Props.setInt(MANUAL_PCT, clamp(pct, 0, 100));
    }

    public static String curve() {
        return Props.get(CURVE, Curve.DEFAULT);
    }

    public static Curve parsedCurve() {
        return Curve.parse(Props.get(CURVE, Curve.DEFAULT));
    }

    public static void setCurve(String curve) {
        final Curve parsed = Curve.tryParse(curve);
        Props.set(CURVE, parsed != null ? parsed.format() : Curve.DEFAULT);
    }

    public static int safetyC() {
        return clamp(Props.getInt(SAFETY_C, DEFAULT_SAFETY_C), MIN_SAFETY_C, MAX_SAFETY_C);
    }

    public static void setSafetyC(int celsius) {
        Props.setInt(SAFETY_C, clamp(celsius, MIN_SAFETY_C, MAX_SAFETY_C));
    }

    public static String presetId() {
        return Props.get(PRESET, "");
    }

    public static void setPresetId(String id) {
        Props.set(PRESET, id == null ? "" : id);
    }

    public static boolean isOn() {
        return !Preset.MODE_OFF.equals(mode());
    }

    /** Applies a preset to the daemon-visible properties, then records its id. */
    public static void apply(Preset preset) {
        if (preset == null) {
            return;
        }
        setMode(preset.mode);
        if (Preset.MODE_MANUAL.equals(preset.mode)) {
            setManualPct(preset.manualPct);
        }
        if (Preset.MODE_SMART.equals(preset.mode)) {
            setCurve(preset.curve);
        }
        setPresetId(preset.id);
    }

    /** Hottest CPU/GPU sensor in tenths of a degree Celsius, or -1. */
    public static int temperatureTenths() {
        return Props.getInt(STATUS_TEMP, -1);
    }

    public static int temperatureCelsius() {
        final int tenths = temperatureTenths();
        return tenths < 0 ? -1 : Math.round(tenths / 10f);
    }

    /** Current fan output 0..100, or -1 when the daemon is not running. */
    public static int outputPct() {
        return Props.getInt(STATUS_PCT, -1);
    }

    /** RPM from the tach node, or -1 when unavailable. */
    public static int rpm() {
        return Props.getInt(STATUS_RPM, -1);
    }

    public static boolean safetyActive() {
        return Props.getBoolean(STATUS_SAFETY, false);
    }

    public static boolean daemonAlive() {
        return outputPct() >= 0 || temperatureTenths() >= 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
