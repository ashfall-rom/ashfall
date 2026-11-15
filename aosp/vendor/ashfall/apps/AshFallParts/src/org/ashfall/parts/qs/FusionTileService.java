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

import org.ashfall.parts.ControllerActivity;
import org.ashfall.parts.R;
import org.ashfall.parts.ipc.FusionClient;

/**
 * Fusion Quick Settings tile (#138, docs/phase6.md §9.5).
 *
 * <p>Active when fusion is on, label "Fusion", subtitle the pad count (or "Off",
 * or "unavailable" while the daemon does not answer).  Tapping opens
 * {@link FusionDialog}; long-press opens the Controller page through
 * {@code qs/TilePreferencesActivity}.  The tile refreshes on
 * {@code onStartListening}/{@code onTileAdded} and after every dialog change,
 * like the Fan and Lighting tiles.
 */
public class FusionTileService extends TileService {

    /** The daemon reports hotplug; the tile only has to catch up. */
    private static final long TICK_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateTile();
            mHandler.postDelayed(this, TICK_MS);
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
            unlockAndRun(this::showFusionDialog);
        } else {
            showFusionDialog();
        }
    }

    public void showFusionDialog() {
        if (isLocked()) {
            return;
        }
        final FusionDialog dialog = new FusionDialog(this, new FusionDialog.Listener() {
            @Override
            public void onChanged() {
                updateTile();
            }

            @Override
            public void onMoreSettings() {
                startActivity(new Intent(FusionTileService.this, ControllerActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        showDialog(dialog);
    }

    private void updateTile() {
        FusionClient.refreshCacheAsync(TICK_MS);
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final boolean on = FusionClient.isOn();
        final FusionClient.Status status = FusionClient.cache();
        final boolean available = status != null && status.available;

        tile.setLabel(getString(R.string.tile_fusion));
        tile.setSubtitle(subtitle(on, available, available ? status.pads.size() : 0));
        // Active whenever fusion is on, even if the daemon is silent: an
        // unusable tile could not be tapped to open the dialog and turn it off
        // again.  The subtitle is what reports the missing daemon.
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private String subtitle(boolean on, boolean available, int pads) {
        if (!on) {
            return getString(R.string.off);
        }
        if (!available) {
            return getString(R.string.fusion_tile_unavailable);
        }
        return pads < 2
                ? getString(R.string.fusion_state_no_pad)
                : getString(R.string.fusion_tile_pads, pads);
    }
}
