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

import org.ashfall.parts.Prefs;
import org.ashfall.parts.PresetActivity;
import org.ashfall.parts.R;
import org.ashfall.parts.hw.Fan;
import org.ashfall.parts.hw.Preset;
import org.ashfall.parts.hw.PresetStore;
import org.ashfall.parts.ui.Ui;

/**
 * Fan Quick Settings tile.
 *
 * <p>The tile label is the active preset name and the subtitle is the current
 * temperature (docs/phase5.md §3).  Tapping opens the preset picker dialog;
 * long-pressing opens the Fan page.
 */
public class FanTileService extends TileService {

    /** One dialog at a time: a burst of taps must not stack dialogs (#166). */
    private final DialogGate mGate = new DialogGate("Fan");

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
        // A second tap while the first dialog is still being attached is a
        // no-op instead of a second stacked dialog (#166).
        if (!mGate.begin()) {
            return;
        }
        if (isLocked()) {
            unlockAndRun(this::showPresetDialog);
        } else {
            showPresetDialog();
        }
    }

    public void showPresetDialog() {
        if (isLocked()) {
            // The unlockAndRun started by the tap never ran, so nothing will
            // reopen the gate: release it here or the tile stays dead.
            mGate.release();
            return;
        }
        final FanDialog dialog = new FanDialog(this, new FanDialog.Listener() {
            @Override
            public void onPresetChanged() {
                updateTile();
            }

            @Override
            public void onEditPresets() {
                startActivity(new Intent(FanTileService.this, PresetActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        showDialog(mGate.track(dialog));
    }



    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final PresetStore store = PresetStore.get(this);
        Preset preset = Fan.activePreset(store);
        if (!Fan.isOn()) {
            preset = store.byId(PresetStore.ID_OFF);
        }
        tile.setLabel(Ui.presetName(this, preset));
        final int temperature = Fan.temperatureCelsius();
        tile.setSubtitle(temperature < 0 ? ""
                : getString(R.string.temperature_value, temperature));
        tile.setState(Fan.isOn() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
