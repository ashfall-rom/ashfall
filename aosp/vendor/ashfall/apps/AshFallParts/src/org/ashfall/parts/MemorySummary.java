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

import org.ashfall.parts.hw.Memory;

import java.util.Locale;

/**
 * Live summary of the Memory row on the AshFall settings page (#141): the
 * sizes the daemon actually applied, not the ones the user asked for.
 */
public class MemorySummary implements Preference.SummaryProvider<Preference> {

    private final Context mContext;

    public MemorySummary(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public CharSequence provideSummary(Preference preference) {
        final int zram = Memory.statusZramMb();
        final int swap = Memory.statusSwapMb();
        if (zram == Memory.UNAVAILABLE && swap == Memory.UNAVAILABLE) {
            // No daemon: never invent a size.
            return mContext.getString(R.string.mem_home_unavailable);
        }
        return mContext.getString(R.string.mem_home_summary,
                formatMb(mContext, zram), formatMb(mContext, swap));
    }

    /**
     * A requested/applied size in MB: {@code Off} for 0, an em dash for the
     * daemon's {@code -1}, and GB once the number is long enough to be hard to
     * read ({@code 2.0 GB} instead of {@code 2048 MB}).
     */
    public static String formatMb(Context context, int mb) {
        if (mb < 0) {
            return context.getString(R.string.mem_dash);
        }
        if (mb == 0) {
            return context.getString(R.string.mem_off);
        }
        if (mb >= 1024) {
            return context.getString(R.string.mem_size_gb,
                    String.format(Locale.US, "%.1f", mb / 1024f));
        }
        return context.getString(R.string.mem_size_mb, mb);
    }

    /** A KiB value from {@code ashfall.status.mem.*}, scaled like {@link #formatMb}. */
    public static String formatKb(Context context, long kb) {
        if (kb < 0) {
            return context.getString(R.string.mem_dash);
        }
        if (kb >= 1024L * 1024L) {
            return context.getString(R.string.mem_size_gb,
                    String.format(Locale.US, "%.1f", kb / (1024f * 1024f)));
        }
        return context.getString(R.string.mem_size_mb, kb / 1024L);
    }

    /** A byte count from {@code StatFs}, scaled like {@link #formatMb}. */
    public static String formatBytes(Context context, long bytes) {
        if (bytes < 0) {
            return context.getString(R.string.mem_dash);
        }
        return formatKb(context, bytes / 1024L);
    }
}
