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

package org.ashfall.parts.qs;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.ashfall.parts.PowerActivity;
import org.ashfall.parts.R;
import org.ashfall.parts.hw.Power;
import org.ashfall.parts.hw.PowerPreset;
import org.ashfall.parts.hw.PowerPresetStore;

/**
 * Power Quick Settings tile (#124).
 *
 * <p>The label is the active preset name and the subtitle is the live CPU/GPU
 * clock summary (docs/parts.md §3).  Tapping opens the preset picker dialog;
 * long-pressing opens the Power page through {@code TilePreferencesActivity}.
 * The tile is always active: three built-in presets always exist, so some power
 * preset is always in effect.
 */
public class PowerTileService extends TileService {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateTile();
            mHandler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        mHandler.removeCallbacks(mTick);
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::showPresetDialog);
        } else {
            showPresetDialog();
        }
    }

    public void showPresetDialog() {
        if (isLocked()) {
            return;
        }
        final PowerDialog dialog = new PowerDialog(this, new PowerDialog.Listener() {
            @Override
            public void onPresetChanged() {
                updateTile();
            }

            @Override
            public void onEditPresets() {
                // The Power page is its own preset editor (its "Edit presets"
                // row); there is no separate Power preset activity.
                startActivity(new Intent(PowerTileService.this, PowerActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        showDialog(dialog);
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final PowerPresetStore store = PowerPresetStore.get(this);
        final PowerPreset preset = Power.activePreset(store);
        tile.setLabel(preset == null
                ? getString(R.string.tile_power) : preset.displayName(this));
        final int cpu = Power.cpuMhzMax();
        final int gpu = Power.gpuMhz();
        tile.setSubtitle(cpu < 0 || gpu < 0
                ? getString(R.string.power_tile_unavailable)
                : getString(R.string.power_tile_subtitle, cpu, gpu));
        // A preset is always active (the built-ins cannot be deleted), so the
        // tile never has an "off" state to show.
        tile.setState(Tile.STATE_ACTIVE);
        tile.updateTile();
    }
}
