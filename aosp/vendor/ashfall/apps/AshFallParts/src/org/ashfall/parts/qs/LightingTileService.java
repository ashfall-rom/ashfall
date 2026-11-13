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
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.ashfall.parts.R;
import org.ashfall.parts.hw.Led;
import org.ashfall.parts.ui.Ui;

/**
 * Lighting Quick Settings tile.  Tapping opens the lighting dialog; long-press
 * opens the full Lighting page.
 */
public class LightingTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::showLightingDialog);
        } else {
            showLightingDialog();
        }
    }

    public void showLightingDialog() {
        if (isLocked()) {
            return;
        }
        final LightingDialog dialog = new LightingDialog(this, this::updateTile);
        showDialog(dialog);
    }


    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setLabel(getString(R.string.tile_lighting));
        final boolean on = Led.isOn();
        tile.setSubtitle(on ? Ui.ledModeName(this, Led.mode()) : getString(R.string.tile_off));
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
