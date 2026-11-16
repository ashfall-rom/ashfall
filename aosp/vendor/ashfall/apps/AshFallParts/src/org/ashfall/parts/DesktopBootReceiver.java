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

package org.ashfall.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.ashfall.parts.hw.Desktop;

/**
 * Boot guard for desktop mode (#131 F2, #207).
 *
 * <p>WindowManager persists the display windowing mode and the density
 * override, so a crash or a killed process between entering and leaving
 * desktop mode would otherwise boot into a desk at 320 dpi.  Every boot this
 * receiver runs the same reset the Display page uses: display 0 fullscreen,
 * the saved density back, and {@code persist.ashfall.pad.mode=game}.
 *
 * <p>The two broadcasts are both handled on purpose: {@code
 * LOCKED_BOOT_COMPLETED} runs before the user unlocks, and {@code
 * BOOT_COMPLETED} is a second, idempotent attempt if WindowManager was not
 * ready for the first one.  The receiver never sends a broadcast, so it cannot
 * loop, and every failure is logged by {@link Desktop} rather than crashing the
 * process.
 */
public class DesktopBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        Desktop.resetForBoot();
    }
}
