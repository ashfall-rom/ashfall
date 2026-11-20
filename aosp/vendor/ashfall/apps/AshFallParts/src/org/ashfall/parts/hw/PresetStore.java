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
import java.util.Locale;
import java.util.UUID;

/**
 * Device-protected JSON store for fan presets.
 *
 * <p>The device-protected storage is deliberate: the presets describe boot
 * defaults and are needed before the user unlocks the device (the fan page and
 * the Quick Settings tile read them).  See {@code docs/phase5.md} §3.
 *
 * <p>Storage is version 2: a top-level {@code "seeded": true} flag records
 * that the stock defaults have been inserted once.  After that the store never
 * re-inserts a deleted built-in; every preset is user data from then on.  See
 * issue #250 (finding 3).
 */
public final class PresetStore {

    private static final String TAG = "AshFallParts";
    private static final String PREFS = "fan_presets";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_VERSION = "version";
    private static final String KEY_SEEDED = "seeded";
    private static final int VERSION = 2;

    public static final String ID_OFF = "builtin.off";
    public static final String ID_QUIET = "builtin.quiet";
    public static final String ID_BALANCED = "builtin.balanced";
    /**
     * The Performance preset.  The id and its {@code max} mode keep the
     * original names for compatibility; only the display name changed from
     * "Maximum" ({@code docs/parts.md} §2.1).
     */
    public static final String ID_MAX = "builtin.max";
    public static final String ID_MANUAL = "builtin.manual";
    public static final String ID_SMART = "builtin.smart";

    public static final int MAX_PRESETS = 32;

    private static PresetStore sInstance;

    private final SharedPreferences mPreferences;
    private final List<Preset> mPresets = new ArrayList<>();
    private final List<Runnable> mListeners = new ArrayList<>();
    private boolean mSeeded;

    private PresetStore(Context context) {
        mPreferences = context.getApplicationContext()
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized PresetStore get(Context context) {
        if (sInstance == null) {
            sInstance = new PresetStore(context);
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

    public synchronized List<Preset> all() {
        return Collections.unmodifiableList(new ArrayList<>(mPresets));
    }

    public synchronized Preset byId(String id) {
        if (id == null) {
            return null;
        }
        for (Preset preset : mPresets) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return null;
    }

    public synchronized Preset add(String name, String mode, int manualPct, String curve) {
        if (mPresets.size() >= MAX_PRESETS) {
            return null;
        }
        final Preset preset = new Preset(newUserId(), name, Preset.sanitizeMode(mode), false);
        preset.manualPct = clamp(manualPct, 0, 100);
        preset.curve = Curve.parse(curve).format();
        mPresets.add(preset);
        persist();
        return preset;
    }

    public synchronized boolean rename(Preset preset, String name) {
        if (preset == null || preset.id == null || preset.id.equals(ID_OFF)
                || name == null || name.trim().isEmpty()) {
            return false;
        }
        final Preset existing = byId(preset.id);
        if (existing == null) {
            return false;
        }
        existing.name = name.trim();
        preset.name = existing.name;
        persist();
        return true;
    }

    public synchronized Preset duplicate(Preset preset) {
        if (preset == null || preset.id == null || preset.id.equals(ID_OFF)
                || mPresets.size() >= MAX_PRESETS) {
            return null;
        }
        final Preset copy = preset.copy(newUserId(), preset.name + " copy");
        mPresets.add(copy);
        persist();
        return copy;
    }

    public synchronized boolean delete(Preset preset) {
        if (preset == null || preset.id == null || preset.id.equals(ID_OFF)) {
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
        final boolean wasActive = preset.id.equals(Fan.presetId());
        persist();
        if (wasActive) {
            Preset fallback = byId(ID_OFF);
            if (fallback == null && !mPresets.isEmpty()) {
                fallback = mPresets.get(0);
            }
            if (fallback != null) {
                Fan.apply(fallback);
            }
        }
        return true;
    }

    /** Saves an edited preset in place (mode, manual percentage, curve). */
    public synchronized void save(Preset preset) {
        if (preset == null || preset.id == null) {
            return;
        }
        if (preset.id.equals(ID_OFF)) {
            // Off is the fan state, not an editable preset.
            return;
        }
        preset.mode = Preset.sanitizeMode(preset.mode);
        preset.manualPct = clamp(preset.manualPct, 0, 100);
        if (Curve.tryParse(preset.curve) == null) {
            preset.curve = Curve.DEFAULT;
        } else {
            preset.curve = Curve.parse(preset.curve).format();
        }
        for (int i = 0; i < mPresets.size(); i++) {
            if (mPresets.get(i).id.equals(preset.id)) {
                final Preset existing = mPresets.get(i);
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
     * Full reset: remove every preset, re-insert the six stock defaults, apply
     * Balanced.
     */
    public synchronized void resetUserPresets() {
        mPresets.clear();
        insertStockDefaults();
        sortPresets();
        persist();
        final Preset balanced = byId(ID_BALANCED);
        if (balanced != null) {
            Fan.apply(balanced);
        }
    }

    public synchronized String newPresetName(Context context) {
        for (int i = 1; i < 1000; i++) {
            final String candidate = context.getString(
                    org.ashfall.parts.R.string.fan_new_preset_default, i);
            boolean taken = false;
            for (Preset preset : mPresets) {
                if (preset.name.equals(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
        return context.getString(org.ashfall.parts.R.string.fan_new_preset);
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
                        final Preset preset = Preset.fromJson(array.getJSONObject(i));
                        if (preset.id == null || preset.id.length() > Preset.MAX_ID_LENGTH) {
                            continue;
                        }
                        mPresets.add(preset);
                    }
                }
                if (!hasSeeded) {
                    // v1 (or older) migration: keep presets, mark seeded.
                    mSeeded = true;
                }
            } catch (JSONException e) {
                Log.w(TAG, "presets are corrupt, restoring the built-ins", e);
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
    private static int builtinOrder(Preset preset) {
        if (!preset.builtin) {
            return 100;
        }
        switch (preset.id) {
            case ID_OFF:
                return 0;
            case ID_QUIET:
                return 1;
            case ID_BALANCED:
                return 2;
            case ID_MAX:
                return 3;
            case ID_MANUAL:
                return 4;
            case ID_SMART:
                return 5;
            default:
                return 6;
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
        mPresets.add(Preset.builtin(ID_OFF, "Off", Preset.MODE_OFF));
        mPresets.add(Preset.builtin(ID_QUIET, "Quiet", Preset.MODE_QUIET));
        mPresets.add(Preset.builtin(ID_BALANCED, "Balanced", Preset.MODE_BALANCED));
        final Preset max = Preset.builtin(ID_MAX, "Performance", Preset.MODE_MAX);
        mPresets.add(max);
        final Preset manual = Preset.builtin(ID_MANUAL, "Manual", Preset.MODE_MANUAL);
        manual.manualPct = Fan.DEFAULT_MANUAL_PCT;
        mPresets.add(manual);
        final Preset smart = Preset.builtin(ID_SMART, "Smart (default)", Preset.MODE_SMART);
        smart.curve = Curve.DEFAULT;
        mPresets.add(smart);
    }

    private void repairActive() {
        final String recorded = Fan.presetId();
        if (byId(recorded) != null) {
            return;
        }
        final String builtinId = Fan.builtinPresetId(Fan.mode());
        if (builtinId != null && byId(builtinId) != null) {
            return;
        }
        Preset fallback = byId(ID_OFF);
        if (fallback == null && !mPresets.isEmpty()) {
            fallback = mPresets.get(0);
        }
        if (fallback != null) {
            Fan.apply(fallback);
        }
    }

    private void persist() {
        try {
            final JSONObject root = new JSONObject();
            root.put(KEY_VERSION, VERSION);
            root.put(KEY_SEEDED, mSeeded);
            final JSONArray array = new JSONArray();
            for (Preset preset : mPresets) {
                array.put(preset.toJson());
            }
            root.put(KEY_PRESETS, array);
            mPreferences.edit().putString(KEY_PRESETS, root.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "cannot persist presets", e);
        }
        for (Runnable listener : new ArrayList<>(mListeners)) {
            listener.run();
        }
    }

    private static String newUserId() {
        final String id = "user." + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return id.length() > Preset.MAX_ID_LENGTH ? id.substring(0, Preset.MAX_ID_LENGTH) : id;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
