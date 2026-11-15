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

import android.content.Context;

import androidx.preference.Preference;

import org.ashfall.parts.hw.Pad;
import org.ashfall.parts.ipc.FusionClient;

/**
 * Live summary of the Controller row: the deadzones plus the fusion state on
 * one line ("Stick deadzone 5% · 3% · 2 pads -> Fusion").
 *
 * <p>The fusion state needs a socket round trip, and the homepage summarizes
 * every row from the UI thread on its 3 s tick.  The pad list therefore comes
 * from {@link FusionClient#cache()}, refreshed in the background and never from
 * a blocking read here.
 */
public class ControllerSummary implements Preference.SummaryProvider<Preference> {

    /** The socket is asked at most this often while fusion is on. */
    private static final long REFRESH_MS = 5000;

    private final Context mContext;

    public ControllerSummary(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public CharSequence provideSummary(Preference preference) {
        FusionClient.refreshCacheAsync(REFRESH_MS);
        return mContext.getString(R.string.home_controller_summary,
                Pad.inner(), Pad.outer())
                + " · " + FusionClient.stateText(mContext, FusionClient.cache());
    }
}
