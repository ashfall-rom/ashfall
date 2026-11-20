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

import org.ashfall.parts.R;
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
 *
 * <p>Storage is version 2 with a top-level {@code "seeded": true} flag: the
 * stock defaults are inserted only once, on a fresh store.  See issue #250
 * (finding 3).
 */
public final class PowerPresetStore {

    private static final String TAG = "AshFallParts";
    private static final String PREFS = "power_presets";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_VERSION = "version";
    private static final String KEY_SEEDED = "seeded";
    private static final int VERSION = 2;

    public static final String ID_LOW_POWER = "builtin.low_power";
    public static final String ID_BALANCED = "builtin.balanced";
    public static final String ID_PERFORMANCE = "builtin.performance";

    public static final int MAX_PRESETS = 32;

    private static PowerPresetStore sInstance;

    private final SharedPreferences mPreferences;
    private final List<PowerPreset> mPresets = new ArrayList<>();
    private final List<Runnable> mListeners = new ArrayList<>();
    private boolean mSeeded;

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
        if (id == null) {
            return null;
        }
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

    /** The built-in Performance preset: stock CPU, the 850 MHz GPU step, `performance`. */
    public static PowerPreset performance() {
        return PowerPreset.builtin(ID_PERFORMANCE, "Performance", Power.SILVER_STOCK_MHZ,
                Power.GOLD_STOCK_MHZ, Power.PRIME_STOCK_MHZ, Power.GOV_PERFORMANCE,
                Power.PERFORMANCE_GPU_MHZ);
    }

    public synchronized boolean rename(PowerPreset preset, String name) {
        if (preset == null || preset.id == null || name == null || name.trim().isEmpty()) {
            return false;
        }
        final PowerPreset existing = byId(preset.id);
        if (existing == null) {
            return false;
        }
        existing.name = name.trim();
        preset.name = existing.name;
        persist();
        return true;
    }

    public synchronized boolean delete(PowerPreset preset) {
        if (preset == null || preset.id == null) {
            return false;
        }
        int index = -1;
        for (int i = 0; i < mPresets.size(); i++) {
            if (mPresets.get(i).id.equals(preset.id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return false;
        }
        mPresets.remove(index);
        final boolean wasActive = preset.id.equals(Power.presetId());
        final boolean empty = mPresets.isEmpty();
        if (empty) {
            insertStockDefaults();
        }
        persist();
        if (wasActive || empty) {
            PowerPreset fallback = byId(ID_BALANCED);
            if (fallback == null && !mPresets.isEmpty()) {
                fallback = mPresets.get(0);
            }
            if (fallback != null) {
                Power.apply(fallback);
            }
        }
        return true;
    }

    /** Saves an edited preset in place. */
    public synchronized void save(PowerPreset preset) {
        if (preset == null || preset.id == null) {
            return;
        }
        preset.sanitize();
        for (int i = 0; i < mPresets.size(); i++) {
            if (mPresets.get(i).id.equals(preset.id)) {
                final PowerPreset existing = mPresets.get(i);
                preset.builtin = existing.builtin;
                mPresets.set(i, preset);
                persist();
                return;
            }
        }
        if (mPresets.size() < MAX_PRESETS) {
            preset.builtin = false;
            mPresets.add(preset);
            persist();
        }
    }

    /**
     * Restores one preset to its factory values: a built-in returns to the
     * #124 table, a user preset cannot be reset (only renamed or deleted).
     * The active settings are untouched; the page applies the new values when
     * the preset is selected (#156 §5).  Re-creates a deleted stock id.
     */
    public synchronized boolean resetPreset(PowerPreset preset) {
        if (preset == null || preset.id == null) {
            return false;
        }
        final PowerPreset table = tablePreset(preset.id);
        if (table == null) {
            return false;
        }
        final PowerPreset existing = byId(preset.id);
        if (existing == null) {
            mPresets.add(table);
            sortPresets();
            persist();
            return true;
        }
        existing.name = table.name;
        existing.silverMhz = table.silverMhz;
        existing.goldMhz = table.goldMhz;
        existing.primeMhz = table.primeMhz;
        existing.governor = table.governor;
        existing.gpuMhz = table.gpuMhz;
        existing.builtin = true;
        persist();
        return true;
    }

    /**
     * "Reset presets": remove every preset, re-insert the three stock defaults,
     * apply Balanced.
     */
    public synchronized void resetAll() {
        mPresets.clear();
        insertStockDefaults();
        sortPresets();
        persist();
        final PowerPreset balanced = byId(ID_BALANCED);
        if (balanced != null) {
            Power.apply(balanced);
        }
    }

    /** A user preset is created from the active one, never from nothing. */
    public synchronized PowerPreset add(PowerPreset source, String name) {
        if (source == null || mPresets.size() >= MAX_PRESETS) {
            return null;
        }
        final PowerPreset preset = source.copy(newUserId(), uniqueName(name));
        preset.builtin = false;
        mPresets.add(preset);
        persist();
        return preset;
    }

    /** The name a new user preset gets before the user renames it. */
    public String newPresetName(Context context) {
        return uniqueName(context.getString(R.string.power_new_preset_name));
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
        mPresets.clear();
        mSeeded = false;
        boolean corrupt = false;
        if (raw != null) {
            try {
                final JSONObject root = new JSONObject(raw);
                final boolean hasSeeded = root.has(KEY_SEEDED);
                mSeeded = root.optBoolean(KEY_SEEDED, false);
                final JSONArray array = root.optJSONArray(KEY_PRESETS);
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        final PowerPreset preset = PowerPreset.fromJson(array.getJSONObject(i));
                        if (preset.id == null || preset.id.length() > PowerPreset.MAX_ID_LENGTH) {
                            continue;
                        }
                        mPresets.add(preset);
                    }
                }
                if (!hasSeeded) {
                    // v1 migration
                    mSeeded = true;
                }
            } catch (JSONException e) {
                Log.w(TAG, "power presets are corrupt, restoring the built-ins", e);
                mPresets.clear();
                corrupt = true;
                mSeeded = false;
            }
        }
        if (!mSeeded) {
            mPresets.clear();
            insertStockDefaults();
            mSeeded = true;
        }
        if (mPresets.isEmpty()) {
            mPresets.clear();
            insertStockDefaults();
            mSeeded = true;
        }
        sortPresets();
        persist();
        repairActive();
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

    private void sortPresets() {
        Collections.sort(mPresets, (a, b) -> {
            final int ai = builtinOrder(a);
            final int bi = builtinOrder(b);
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    private void insertStockDefaults() {
        mPresets.add(lowPower());
        mPresets.add(balanced());
        mPresets.add(performance());
    }

    private void repairActive() {
        final String recorded = Power.presetId();
        if (byId(recorded) != null) {
            return;
        }
        if (byId(ID_BALANCED) != null) {
            return;
        }
        PowerPreset fallback = byId(ID_BALANCED);
        if (fallback == null && !mPresets.isEmpty()) {
            fallback = mPresets.get(0);
        }
        if (fallback == null) {
            mPresets.clear();
            insertStockDefaults();
            sortPresets();
            fallback = byId(ID_BALANCED);
        }
        if (fallback != null) {
            Power.apply(fallback);
        }
    }

    private void persist() {
        try {
            final JSONObject root = new JSONObject();
            root.put(KEY_VERSION, VERSION);
            root.put(KEY_SEEDED, mSeeded);
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
