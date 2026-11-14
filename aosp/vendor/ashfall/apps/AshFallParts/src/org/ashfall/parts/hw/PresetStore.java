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
 */
public final class PresetStore {

    private static final String TAG = "AshFallParts";
    private static final String PREFS = "fan_presets";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_VERSION = "version";
    private static final int VERSION = 1;

    public static final String ID_OFF = "builtin.off";
    public static final String ID_QUIET = "builtin.quiet";
    public static final String ID_BALANCED = "builtin.balanced";
    public static final String ID_MAX = "builtin.max";
    public static final String ID_SMART = "builtin.smart";

    public static final int MAX_PRESETS = 32;

    private static PresetStore sInstance;

    private final SharedPreferences mPreferences;
    private final List<Preset> mPresets = new ArrayList<>();
    private final List<Runnable> mListeners = new ArrayList<>();

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
        if (preset == null || preset.builtin || name == null || name.trim().isEmpty()) {
            return false;
        }
        preset.name = name.trim();
        persist();
        return true;
    }

    public synchronized Preset duplicate(Preset preset) {
        if (preset == null || mPresets.size() >= MAX_PRESETS) {
            return null;
        }
        final Preset copy = preset.copy(newUserId(), preset.name + " copy");
        mPresets.add(copy);
        persist();
        return copy;
    }

    public synchronized boolean delete(Preset preset) {
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

    /** Saves an edited preset in place (mode, manual percentage, curve). */
    public synchronized void save(Preset preset) {
        if (preset == null) {
            return;
        }
        for (int i = 0; i < mPresets.size(); i++) {
            if (mPresets.get(i).id.equals(preset.id)) {
                final Preset existing = mPresets.get(i);
                if (existing.builtin) {
                    // Built-in modes are fixed by the contract; only their
                    // Smart curve is user-editable.
                    preset.builtin = true;
                    preset.name = existing.name;
                    preset.mode = existing.mode;
                }
                mPresets.set(i, preset);
                persist();
                return;
            }
        }
        mPresets.add(preset);
        persist();
    }

    public synchronized void resetUserPresets() {
        final String activeId = Fan.presetId();
        boolean activeRemoved = false;
        for (int i = mPresets.size() - 1; i >= 0; i--) {
            final Preset preset = mPresets.get(i);
            if (!preset.builtin) {
                if (preset.id.equals(activeId)) {
                    activeRemoved = true;
                }
                mPresets.remove(i);
            }
        }
        persist();
        if (activeRemoved) {
            Fan.setPresetId(Fan.builtinPresetId(Fan.mode()));
        }
    }

    /** Re-derives the recorded id from the mode after the active preset went away. */
    private static void remapActivePreset(String removedId) {
        if (removedId != null && removedId.equals(Fan.presetId())) {
            Fan.setPresetId(Fan.builtinPresetId(Fan.mode()));
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
        if (raw != null) {
            try {
                final JSONObject root = new JSONObject(raw);
                final JSONArray array = root.optJSONArray(KEY_PRESETS);
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        final Preset preset = Preset.fromJson(array.getJSONObject(i));
                        if (preset.id.length() > Preset.MAX_ID_LENGTH) {
                            continue;
                        }
                        mPresets.add(preset);
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "presets are corrupt, restoring the built-ins", e);
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
            case ID_SMART:
                return 4;
            default:
                return 5;
        }
    }

    /** Inserts any built-in preset that is missing and keeps their identity. */
    private void ensureBuiltins() {
        ensureBuiltin(ID_OFF, "Off", Preset.MODE_OFF);
        ensureBuiltin(ID_QUIET, "Quiet", Preset.MODE_QUIET);
        ensureBuiltin(ID_BALANCED, "Balanced", Preset.MODE_BALANCED);
        ensureBuiltin(ID_MAX, "Maximum", Preset.MODE_MAX);
        Preset smart = byId(ID_SMART);
        if (smart == null) {
            smart = Preset.builtin(ID_SMART, "Smart (default)", Preset.MODE_SMART);
            smart.curve = Curve.DEFAULT;
            mPresets.add(smart);
        }
        smart.builtin = true;
        smart.mode = Preset.MODE_SMART;
        smart.name = "Smart (default)";
        if (Curve.tryParse(smart.curve) == null) {
            smart.curve = Curve.DEFAULT;
        }
    }

    private void ensureBuiltin(String id, String name, String mode) {
        Preset preset = byId(id);
        if (preset == null) {
            preset = Preset.builtin(id, name, mode);
            mPresets.add(preset);
        }
        preset.builtin = true;
        preset.name = name;
        preset.mode = mode;
    }

    private void persist() {
        try {
            final JSONObject root = new JSONObject();
            root.put(KEY_VERSION, VERSION);
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
