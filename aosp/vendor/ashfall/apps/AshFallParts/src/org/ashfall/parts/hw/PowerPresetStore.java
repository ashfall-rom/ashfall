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
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Device-protected JSON store for power presets (#124).
 *
 * <p>Device-protected storage mirrors the fan presets: the Power page, the
 * Quick Settings tile and the Handheld summary read them, and the tile can be
 * opened before the first unlock.
 */
public final class PowerPresetStore {

    private static final String TAG = "AshFallParts";
    private static final String PREFS = "power_presets";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_VERSION = "version";
    private static final int VERSION = 1;

    public static final String ID_LOW_POWER = "builtin.low_power";
    public static final String ID_BALANCED = "builtin.balanced";
    public static final String ID_PERFORMANCE = "builtin.performance";

    public static final int MAX_PRESETS = 32;

    private static PowerPresetStore sInstance;

    private final SharedPreferences mPreferences;
    private final List<PowerPreset> mPresets = new ArrayList<>();
    private final List<Runnable> mListeners = new ArrayList<>();

    private PowerPresetStore(Context context) {
        mPreferences = context.getApplicationContext()
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized PowerPresetStore get(Context context) {
        if (sInstance == null) {
            sInstance = new PowerPresetStore(context);
        }
        return sInstance;
    }

    public void addListener(Runnable listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        mListeners.remove(listener);
    }

    public synchronized List<PowerPreset> all() {
        return Collections.unmodifiableList(new ArrayList<>(mPresets));
    }

    public synchronized PowerPreset byId(String id) {
        for (PowerPreset preset : mPresets) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return null;
    }

    /** The built-in Low power preset of the #124 table. */
    public static PowerPreset lowPower() {
        return PowerPreset.builtin(ID_LOW_POWER, "Low power", 1171, 1478, 1632,
                Power.GOV_CONSERVATIVE, 305);
    }

    /** The built-in Balanced preset: every cap at stock. */
    public static PowerPreset balanced() {
        return PowerPreset.builtin(ID_BALANCED, "Balanced", Power.SILVER_STOCK_MHZ,
                Power.GOLD_STOCK_MHZ, Power.PRIME_STOCK_MHZ, Power.GOV_SCHEDUTIL,
                Power.GPU_STOCK_MHZ);
    }

    /** The built-in Performance preset: stock CPU, the 850 MHz GPU step. */
    public static PowerPreset performance() {
        return PowerPreset.builtin(ID_PERFORMANCE, "Performance", Power.SILVER_STOCK_MHZ,
                Power.GOLD_STOCK_MHZ, Power.PRIME_STOCK_MHZ, Power.GOV_SCHEDUTIL,
                Power.GPU_PRESET_MAX_MHZ);
    }

    /** A user preset built from an existing one (the only way to create one). */
    public synchronized PowerPreset duplicate(PowerPreset preset) {
        if (preset == null || mPresets.size() >= MAX_PRESETS) {
            return null;
        }
        final PowerPreset copy = preset.copy(newUserId(), uniqueName(preset.name + " copy"));
        mPresets.add(copy);
        persist();
        return copy;
    }

    public synchronized boolean rename(PowerPreset preset, String name) {
        if (preset == null || preset.builtin || name == null || name.trim().isEmpty()) {
            return false;
        }
        preset.name = name.trim();
        persist();
        return true;
    }

    public synchronized boolean delete(PowerPreset preset) {
        if (preset == null || preset.builtin) {
            return false;
        }
        final boolean removed = mPresets.remove(preset);
        if (removed) {
            persist();
            remapActivePreset(preset.id);
        }
        return removed;
    }

    /** Saves an edited preset in place; built-in names stay fixed. */
    public synchronized void save(PowerPreset preset) {
        if (preset == null) {
            return;
        }
        preset.sanitize();
        for (int i = 0; i < mPresets.size(); i++) {
            if (mPresets.get(i).id.equals(preset.id)) {
                final PowerPreset existing = mPresets.get(i);
                if (existing.builtin) {
                    preset.builtin = true;
                    preset.name = existing.name;
                }
                mPresets.set(i, preset);
                persist();
                return;
            }
        }
        mPresets.add(preset);
        persist();
    }

    /**
     * "Reset presets": the built-ins go back to the #124 table and every user
     * preset is removed.  The active id is remapped when it named a user preset.
     */
    public synchronized void resetAll() {
        final String activeId = Power.presetId();
        resetBuiltin(ID_LOW_POWER);
        resetBuiltin(ID_BALANCED);
        resetBuiltin(ID_PERFORMANCE);
        boolean activeRemoved = false;
        for (int i = mPresets.size() - 1; i >= 0; i--) {
            final PowerPreset preset = mPresets.get(i);
            if (!preset.builtin) {
                if (preset.id.equals(activeId)) {
                    activeRemoved = true;
                }
                mPresets.remove(i);
            }
        }
        persist();
        if (activeRemoved) {
            Power.setPresetId(ID_BALANCED);
        }
    }

    /** Restores one built-in to its table values, keeping its identity. */
    private void resetBuiltin(String id) {
        final PowerPreset table = tablePreset(id);
        final PowerPreset existing = byId(id);
        if (table == null) {
            return;
        }
        if (existing == null) {
            mPresets.add(table);
            return;
        }
        existing.name = table.name;
        existing.silverMhz = table.silverMhz;
        existing.goldMhz = table.goldMhz;
        existing.primeMhz = table.primeMhz;
        existing.governor = table.governor;
        existing.gpuMhz = table.gpuMhz;
        existing.builtin = true;
    }

    private static PowerPreset tablePreset(String id) {
        switch (id) {
            case ID_LOW_POWER:
                return lowPower();
            case ID_BALANCED:
                return balanced();
            case ID_PERFORMANCE:
                return performance();
            default:
                return null;
        }
    }

    /** Re-derives the recorded id after the active preset went away. */
    private static void remapActivePreset(String removedId) {
        if (removedId != null && removedId.equals(Power.presetId())) {
            Power.setPresetId(ID_BALANCED);
        }
    }

    private String uniqueName(String wanted) {
        String candidate = wanted;
        for (int suffix = 2; suffix < 1000; suffix++) {
            boolean taken = false;
            for (PowerPreset preset : mPresets) {
                if (preset.name.equals(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
            candidate = wanted + " " + suffix;
        }
        return wanted;
    }

    private void load() {
        final String raw = mPreferences.getString(KEY_PRESETS, null);
        if (raw != null) {
            try {
                final JSONObject root = new JSONObject(raw);
                final JSONArray array = root.optJSONArray(KEY_PRESETS);
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        final PowerPreset preset = PowerPreset.fromJson(array.getJSONObject(i));
                        if (preset.id.length() > PowerPreset.MAX_ID_LENGTH) {
                            continue;
                        }
                        mPresets.add(preset);
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "power presets are corrupt, restoring the built-ins", e);
                mPresets.clear();
            }
        }
        ensureBuiltins();
        Collections.sort(mPresets, (a, b) -> {
            final int ai = builtinOrder(a);
            final int bi = builtinOrder(b);
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        persist();
    }

    /** Built-ins are shown in a fixed order; user presets follow, by name. */
    private static int builtinOrder(PowerPreset preset) {
        if (!preset.builtin) {
            return 100;
        }
        switch (preset.id) {
            case ID_LOW_POWER:
                return 0;
            case ID_BALANCED:
                return 1;
            case ID_PERFORMANCE:
                return 2;
            default:
                return 3;
        }
    }

    /**
     * Inserts any built-in that is missing and keeps its identity.  An existing
     * built-in keeps the values the user gave it: only "Reset presets" restores
     * the table.
     */
    private void ensureBuiltins() {
        ensureBuiltin(lowPower());
        ensureBuiltin(balanced());
        ensureBuiltin(performance());
    }

    private void ensureBuiltin(PowerPreset table) {
        final PowerPreset existing = byId(table.id);
        if (existing == null) {
            mPresets.add(table);
            return;
        }
        existing.builtin = true;
        existing.name = table.name;
        existing.sanitize();
    }

    private void persist() {
        try {
            final JSONObject root = new JSONObject();
            root.put(KEY_VERSION, VERSION);
            final JSONArray array = new JSONArray();
            for (PowerPreset preset : mPresets) {
                array.put(preset.toJson());
            }
            root.put(KEY_PRESETS, array);
            mPreferences.edit().putString(KEY_PRESETS, root.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "cannot persist power presets", e);
        }
        for (Runnable listener : new ArrayList<>(mListeners)) {
            listener.run();
        }
    }

    private static String newUserId() {
        final String id = "user." + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return id.length() > PowerPreset.MAX_ID_LENGTH
                ? id.substring(0, PowerPreset.MAX_ID_LENGTH) : id;
    }
}
