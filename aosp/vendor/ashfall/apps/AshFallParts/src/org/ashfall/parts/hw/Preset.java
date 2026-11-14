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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A named fan configuration: a mode plus (for {@code manual}) a fixed
 * percentage or (for {@code smart}) a temperature curve.
 *
 * <p>Presets are stored by this app only; the daemon never looks at them.  The
 * id is what {@code persist.ashfall.fan.preset} carries, and is limited to 32
 * characters by the property interface.
 */
public final class Preset {

    public static final String MODE_OFF = "off";
    public static final String MODE_QUIET = "quiet";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_MAX = "max";
    public static final String MODE_MANUAL = "manual";
    public static final String MODE_SMART = "smart";

    public static final int MAX_ID_LENGTH = 32;

    public String id;
    public String name;
    public String mode;
    public int manualPct = 50;
    public String curve = Curve.DEFAULT;
    public boolean builtin;

    public Preset() {
    }

    public Preset(String id, String name, String mode, boolean builtin) {
        this.id = id;
        this.name = name;
        this.mode = mode;
        this.builtin = builtin;
    }

    public static Preset builtin(String id, String name, String mode) {
        return new Preset(id, name, mode, true);
    }

    public boolean isCurveEditable() {
        return MODE_SMART.equals(mode);
    }

    public boolean isOff() {
        return MODE_OFF.equals(mode);
    }

    public Curve parsedCurve() {
        return Curve.parse(curve);
    }

    public Preset copy(String newId, String newName) {
        final Preset preset = new Preset(newId, newName, mode, false);
        preset.manualPct = manualPct;
        preset.curve = curve;
        return preset;
    }

    public JSONObject toJson() throws JSONException {
        final JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("mode", mode);
        object.put("manual_pct", manualPct);
        object.put("curve", curve);
        object.put("builtin", builtin);
        return object;
    }

    public static Preset fromJson(JSONObject object) throws JSONException {
        final Preset preset = new Preset();
        preset.id = object.getString("id");
        preset.name = object.getString("name");
        preset.mode = sanitizeMode(object.optString("mode", MODE_SMART));
        preset.manualPct = clamp(object.optInt("manual_pct", 50), 0, 100);
        preset.curve = Curve.parse(object.optString("curve", Curve.DEFAULT)).format();
        preset.builtin = object.optBoolean("builtin", false);
        return preset;
    }

    public static String sanitizeMode(String mode) {
        if (MODE_OFF.equals(mode) || MODE_QUIET.equals(mode) || MODE_BALANCED.equals(mode)
                || MODE_MAX.equals(mode) || MODE_MANUAL.equals(mode) || MODE_SMART.equals(mode)) {
            return mode;
        }
        return MODE_SMART;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
