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

package org.ashfall.parts;

import android.content.Context;
import android.content.SharedPreferences;

import org.ashfall.parts.hw.Led;

/**
 * Small UI state that is not part of the daemon interface: the last preset, the
 * last lighting mode (for the on/off switch) and the last colour target.
 *
 * <p>Kept in device-protected storage so the fan page and the tiles work before
 * the first unlock.
 */
public final class Prefs {

    private static final String FILE = "ui";
    private static final String KEY_LAST_PRESET = "fan_last_preset";
    private static final String KEY_LED_LAST_MODE = "led_last_mode";
    private static final String KEY_LED_TARGET = "led_target";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().createDeviceProtectedStorageContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String lastPreset(Context context, String def) {
        return prefs(context).getString(KEY_LAST_PRESET, def);
    }

    public static void setLastPreset(Context context, String id) {
        prefs(context).edit().putString(KEY_LAST_PRESET, id).apply();
    }

    public static String lastLedMode(Context context) {
        return prefs(context).getString(KEY_LED_LAST_MODE, Led.MODE_STATIC);
    }

    public static void setLastLedMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_LED_LAST_MODE, mode).apply();
    }

    public static int ledTarget(Context context) {
        if (Led.sync()) {
            return Led.TARGET_BOTH;
        }
        return prefs(context).getInt(KEY_LED_TARGET, Led.TARGET_LEFT);
    }

    public static void setLedTarget(Context context, int target) {
        prefs(context).edit().putInt(KEY_LED_TARGET, target).apply();
        if (target == Led.TARGET_BOTH) {
            Led.setSync(true);
        } else {
            Led.setSync(false);
        }
    }
}
