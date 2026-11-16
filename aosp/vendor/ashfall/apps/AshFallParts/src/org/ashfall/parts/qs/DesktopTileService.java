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

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import org.ashfall.parts.R;
import org.ashfall.parts.hw.Desktop;

/**
 * Desktop mode Quick Settings tile (#131 F2).
 *
 * <p>It is a plain toggle, not a dialog tile: one tap enters or leaves desktop
 * mode and the active state is the display's real windowing mode.  The tile
 * refreshes while the panel is open so a switch made from the chord, Settings
 * or {@code wm} shows up here too; a long-press opens the Display page through
 * {@code qs/TilePreferencesActivity}.
 */
public class DesktopTileService extends TileService {

    /** The page also polls; this only keeps the open panel in step. */
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
        // A mode toggle has no dialog, so there is nothing to unlock for and
        // no DialogGate to hold: the tap is applied immediately.
        final Desktop.Result result = Desktop.isDesktop() ? Desktop.exit() : Desktop.enter();
        if (result != Desktop.Result.OK) {
            Toast.makeText(this,
                    result == Desktop.Result.PARTIAL
                            ? R.string.display_desktop_incomplete
                            : R.string.display_desktop_failed,
                    Toast.LENGTH_SHORT).show();
        }
        updateTile();
    }

    /** Asks SystemUI to bind this tile and run {@link #onStartListening()}. */
    public static void requestUpdate(Context context) {
        try {
            TileService.requestListeningState(context,
                    new ComponentName(context, DesktopTileService.class));
        } catch (Throwable ignored) {
            // SystemUI may be restarting; the normal listening cycle catches up.
        }
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final int mode = Desktop.windowingMode();
        final boolean known = mode >= 0;
        final boolean desktop = mode == Desktop.WINDOWING_MODE_FREEFORM;
        tile.setLabel(getString(R.string.tile_desktop));
        tile.setSubtitle(getString(!known ? R.string.unknown
                : (desktop ? R.string.on : R.string.off)));
        tile.setState(desktop ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
