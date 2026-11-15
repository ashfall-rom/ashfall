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

import org.ashfall.parts.PowerActivity;
import org.ashfall.parts.R;

/**
 * Power Quick Settings tile (#124).
 *
 * <p>Shell version: it only labels the tile and opens the page.  The #124 app
 * worker replaces this file with the preset dialog (label = active preset,
 * tap = single-choice preset dialog, long-press = Power page).
 */
public class PowerTileService extends TileService {

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
            unlockAndRun(this::openPage);
        } else {
            openPage();
        }
    }

    private void openPage() {
        startActivity(new Intent(this, PowerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setLabel(getString(R.string.tile_power));
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
