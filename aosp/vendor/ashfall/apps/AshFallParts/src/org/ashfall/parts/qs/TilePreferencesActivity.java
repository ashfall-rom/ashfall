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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import org.ashfall.parts.ControllerActivity;
import org.ashfall.parts.FanActivity;
import org.ashfall.parts.LightingActivity;
import org.ashfall.parts.PowerActivity;

/**
 * Long-press target of the tiles.
 *
 * <p>{@code TileService} has no {@code onLongClick()} callback: SystemUI
 * resolves {@code android.service.quicksettings.action.QS_TILE_PREFERENCES}
 * inside the tile's package and passes the tile component in
 * {@link Intent#EXTRA_COMPONENT_NAME}.  This no-UI trampoline opens the full
 * page that belongs to the tile.
 */
public class TilePreferencesActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ComponentName tile = getIntent().getParcelableExtra(Intent.EXTRA_COMPONENT_NAME);
        final Class<?> target;
        if (tile != null && tile.getClassName() != null
                && tile.getClassName().contains("Lighting")) {
            target = LightingActivity.class;
        } else if (tile != null && tile.getClassName() != null
                && tile.getClassName().contains("Fusion")) {
            target = ControllerActivity.class;
        } else if (tile != null && tile.getClassName() != null
                && tile.getClassName().contains("Power")) {
            target = PowerActivity.class;
        } else {
            target = FanActivity.class;
        }
        startActivity(new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
