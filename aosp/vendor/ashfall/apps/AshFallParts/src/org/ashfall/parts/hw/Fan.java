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
    /** Low output limit: below it the daemon stops the fan completely. */
    public static final String MIN_PCT = "persist.ashfall.fan.min_pct";
    /** High output limit: the daemon never runs the fan above it. */
    public static final String MAX_PCT = "persist.ashfall.fan.max_pct";
    public static final String CURVE = "persist.ashfall.fan.curve";
    public static final String PRESET = "persist.ashfall.fan.preset";
    public static final String KICK = "persist.ashfall.fan.kick";
    public static final String KICK_PCT = "persist.ashfall.fan.kick_pct";
    public static final String SCREEN_OFF = "persist.ashfall.fan.screen_off";

    /** {@code 1} while a fan UI is visible: the daemon polls every 500 ms. */
    public static final String UI_LIVE = "ashfall.ui.fan_live";
    /** Live curve being edited; the daemon follows it instead of the saved curve. */
    public static final String UI_PREVIEW_CURVE = "ashfall.ui.fan_preview_curve";

    public static final String STATUS_TEMP = "ashfall.status.fan.temp";
    public static final String STATUS_PCT = "ashfall.status.fan.pct";
    public static final String STATUS_RPM = "ashfall.status.fan.rpm";
    public static final String STATUS_SAFETY = "ashfall.status.fan.safety";

    public static final int DEFAULT_MANUAL_PCT = 50;
    public static final int DEFAULT_MIN_PCT = 5;
    public static final int DEFAULT_MAX_PCT = 100;
    /**
     * Built-in Quiet preset output (issue #156 §10, previously 2 %).  Only used
     * for the fan-limit note; the daemon owns the value it actually applies.
     */
    public static final int QUIET_PCT = 15;
    public static final int DEFAULT_KICK_PCT = 30;
    public static final int MIN_KICK_PCT = 10;
    public static final int MAX_KICK_PCT = 60;

    /** Number of open fan UIs; the live flag stays set until the last one closes. */
    private static int sLiveCount;

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

    /**
     * Low output limit in percent (docs/phase5.md §7.1), {@code 0..100}.
     * Below it the daemon stops the fan completely; {@code 0} disables the
     * cut-off.  The daemon enforces it, the app only writes the property.
     */
    public static int minPct() {
        return clamp(Props.getInt(MIN_PCT, DEFAULT_MIN_PCT), 0, 100);
    }

    public static void setMinPct(int pct) {
        Props.setInt(MIN_PCT, clamp(pct, 0, 100));
    }

    /**
     * High output limit in percent, {@code 0..100}.  The daemon never runs the
     * fan above it; {@code 100} means no cap.
     */
    public static int maxPct() {
        return clamp(Props.getInt(MAX_PCT, DEFAULT_MAX_PCT), 0, 100);
    }

    public static void setMaxPct(int pct) {
        Props.setInt(MAX_PCT, clamp(pct, 0, 100));
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

    /**
     * The user-visible safety threshold was removed in the phase-5 follow-ups:
     * the daemon keeps a fixed last-resort rule (100 % at 90 °C, released at
     * 80 °C) that has no UI.  {@link #safetyActive()} still reports it.
     */
    public static boolean kick() {
        return Props.getBoolean(KICK, true);
    }

    public static void setKick(boolean enabled) {
        Props.set(KICK, enabled ? "1" : "0");
    }

    public static int kickPct() {
        return clamp(Props.getInt(KICK_PCT, DEFAULT_KICK_PCT), MIN_KICK_PCT, MAX_KICK_PCT);
    }

    /** {@code true} = the fan stops while the panel is off (the default). */
    public static boolean offWhileScreenOff() {
        return !"run".equals(Props.get(SCREEN_OFF, "off"));
    }

    public static void setOffWhileScreenOff(boolean off) {
        Props.set(SCREEN_OFF, off ? "off" : "run");
    }

    /**
     * Marks a fan UI (page, editor, QS dialog) as visible.  A counter is used
     * because the page and a dialog can be visible at the same time; the daemon
     * only sees {@code 1} while at least one of them is open.
     */
    public static synchronized void setLive(boolean live) {
        if (live) {
            sLiveCount++;
        } else if (sLiveCount > 0) {
            sLiveCount--;
        }
        refreshLive();
    }

    /** Re-publishes the current live flag (used by the 500 ms ui refresh). */
    public static synchronized void refreshLive() {
        Props.set(UI_LIVE, sLiveCount > 0 ? "1" : "0");
    }

    /** Publishes the curve being edited; the daemon drops it when cleared. */
    public static void setPreviewCurve(String curve) {
        Props.set(UI_PREVIEW_CURVE, curve == null ? "" : curve);
    }

    public static void clearPreviewCurve() {
        Props.set(UI_PREVIEW_CURVE, "");
    }

    public static String presetId() {
        return Props.get(PRESET, "");
    }

    public static void setPresetId(String id) {
        Props.set(PRESET, id == null ? "" : id);
    }

    /**
     * Built-in preset id for a fan mode.  Every mode has one (Manual included),
     * so the active preset can always be named and written back to.
     */
    public static String builtinPresetId(String mode) {
        switch (Preset.sanitizeMode(mode)) {
            case Preset.MODE_OFF:
                return PresetStore.ID_OFF;
            case Preset.MODE_QUIET:
                return PresetStore.ID_QUIET;
            case Preset.MODE_BALANCED:
                return PresetStore.ID_BALANCED;
            case Preset.MODE_MAX:
                return PresetStore.ID_MAX;
            case Preset.MODE_SMART:
                return PresetStore.ID_SMART;
            case Preset.MODE_MANUAL:
                return PresetStore.ID_MANUAL;
            default:
                return null;
        }
    }

    /**
     * The active preset: the recorded id when it still exists, otherwise the
     * built-in preset for the current mode.  The mode is the daemon-visible
     * source of truth, so a stale or empty {@code persist.ashfall.fan.preset}
     * (fresh install, deleted user preset) never changes what the pages, the
     * tile or the curve editor show.  Manual resolves to the built-in Manual
     * preset, so the output slider always has a preset to write back to.
     */
    public static Preset activePreset(PresetStore store) {
        if (store == null) {
            return null;
        }
        final Preset recorded = store.byId(presetId());
        if (recorded != null) {
            return recorded;
        }
        final String builtin = builtinPresetId(mode());
        return builtin == null ? null : store.byId(builtin);
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
