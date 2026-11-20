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

import org.ashfall.parts.R;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A named power configuration: one CPU cap per cluster, one CPU governor for
 * all three policies and the GPU cap (#124).
 *
 * <p>Presets are stored by this app only; the daemon never looks at them.  The
 * id is what {@code persist.ashfall.power.preset} carries, and is limited to
 * 32 characters by the property interface.  The values themselves are written
 * to {@code persist.ashfall.perf.*} when the preset is applied.
 */
public final class PowerPreset {

    public static final int MAX_ID_LENGTH = 32;

    public String id;
    public String name;
    public int silverMhz = Power.SILVER_STOCK_MHZ;
    public int goldMhz = Power.GOLD_STOCK_MHZ;
    public int primeMhz = Power.PRIME_STOCK_MHZ;
    public String governor = Power.GOV_SCHEDUTIL;
    public int gpuMhz = Power.GPU_STOCK_MHZ;
    public boolean builtin;

    public PowerPreset() {
    }

    public PowerPreset(String id, String name, boolean builtin) {
        this.id = id;
        this.name = name;
        this.builtin = builtin;
    }

    public static PowerPreset builtin(String id, String name, int silverMhz, int goldMhz,
            int primeMhz, String governor, int gpuMhz) {
        final PowerPreset preset = new PowerPreset(id, name, true);
        preset.silverMhz = silverMhz;
        preset.goldMhz = goldMhz;
        preset.primeMhz = primeMhz;
        preset.governor = Power.sanitizeGovernor(governor);
        preset.gpuMhz = Power.sanitizePresetGpuMhz(gpuMhz);
        return preset;
    }

    /** Clamps every field into the range the daemon accepts. */
    public void sanitize() {
        silverMhz = Power.clampMhz(silverMhz, Power.SILVER_MIN_MHZ, Power.SILVER_STOCK_MHZ);
        goldMhz = Power.clampMhz(goldMhz, Power.GOLD_MIN_MHZ, Power.GOLD_STOCK_MHZ);
        primeMhz = Power.clampMhz(primeMhz, Power.PRIME_MIN_MHZ, Power.PRIME_STOCK_MHZ);
        governor = Power.sanitizeGovernor(governor);
        // Every GPU step the daemon accepts is a valid preset value, 940 and
        // 1000 MHz included (#156 §5).
        gpuMhz = Power.sanitizePresetGpuMhz(gpuMhz);
    }

    /** The caps are at stock and the governor is the default: nothing limited. */
    /**
     * The name shown by the page, the Handheld summary, the tile and its
     * dialog.  The built-ins are translated here, exactly like
     * {@code Ui.presetName} does for the fan presets; a user preset keeps the
     * name it was given.
     */
    public String displayName(Context context) {
        switch (id) {
            case PowerPresetStore.ID_LOW_POWER:
                return "Low power".equals(name)
                        ? context.getString(R.string.power_preset_low) : name;
            case PowerPresetStore.ID_BALANCED:
                return "Balanced".equals(name)
                        ? context.getString(R.string.power_preset_balanced) : name;
            case PowerPresetStore.ID_PERFORMANCE:
                return "Performance".equals(name)
                        ? context.getString(R.string.power_preset_performance) : name;
            default:
                return name;
        }
    }

    public boolean isStock() {
        return silverMhz >= Power.SILVER_STOCK_MHZ
                && goldMhz >= Power.GOLD_STOCK_MHZ
                && primeMhz >= Power.PRIME_STOCK_MHZ
                && Power.GOV_SCHEDUTIL.equals(governor)
                && gpuMhz <= Power.GPU_STOCK_MHZ;
    }

    /**
     * The one-line value summary shown by the page, the editor and the Quick
     * Settings dialog: the three caps or "Stock clocks", the governor and the
     * GPU step.
     */
    public String summary(Context context) {
        final String governor = Power.sanitizeGovernor(this.governor);
        if (isStock()) {
            return context.getString(R.string.power_preset_summary_stock, governor, gpuMhz);
        }
        return context.getString(R.string.power_preset_summary, silverMhz, goldMhz,
                primeMhz, governor, gpuMhz);
    }

    public PowerPreset copy(String newId, String newName) {
        final PowerPreset preset = new PowerPreset(newId, newName, false);
        preset.silverMhz = silverMhz;
        preset.goldMhz = goldMhz;
        preset.primeMhz = primeMhz;
        preset.governor = governor;
        preset.gpuMhz = gpuMhz;
        return preset;
    }

    public JSONObject toJson() throws JSONException {
        final JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("silver_mhz", silverMhz);
        object.put("gold_mhz", goldMhz);
        object.put("prime_mhz", primeMhz);
        object.put("governor", governor);
        object.put("gpu_mhz", gpuMhz);
        object.put("builtin", builtin);
        return object;
    }

    public static PowerPreset fromJson(JSONObject object) throws JSONException {
        final PowerPreset preset = new PowerPreset();
        preset.id = object.getString("id");
        preset.name = object.getString("name");
        preset.silverMhz = object.optInt("silver_mhz", Power.SILVER_STOCK_MHZ);
        preset.goldMhz = object.optInt("gold_mhz", Power.GOLD_STOCK_MHZ);
        preset.primeMhz = object.optInt("prime_mhz", Power.PRIME_STOCK_MHZ);
        preset.governor = Power.sanitizeGovernor(
                object.optString("governor", Power.GOV_SCHEDUTIL));
        preset.gpuMhz = object.optInt("gpu_mhz", Power.GPU_STOCK_MHZ);
        preset.builtin = object.optBoolean("builtin", false);
        preset.sanitize();
        return preset;
    }
}
