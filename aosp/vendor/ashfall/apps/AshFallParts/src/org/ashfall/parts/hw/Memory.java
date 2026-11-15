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

package org.ashfall.parts.hw;

import android.os.Environment;
import android.os.StatFs;

import org.ashfall.parts.ipc.Props;

/**
 * Memory page interface: the swapfile (issue #141, {@code docs/phase6.md} §9.3).
 *
 * <p>The page is a settings client.  It writes only
 * {@code persist.ashfall.mem.swap_mb} and reads the
 * {@code ashfall.status.mem.*} values published by {@code ashfall-hwd}; it
 * never writes a status property and never touches {@code /proc/swaps}.
 * {@code ashfall-hwd} re-validates and clamps every request, so nothing here
 * clamps a write on the way out - the page shows what was asked for and the
 * daemon's effective value separately.
 *
 * <p>The page is swapfile-only (#156 §7): compressed swap belongs to the
 * platform, so this class reads and writes only what AshFall owns on
 * {@code /data}.
 */
public final class Memory {

    /** Swapfile size request in MB; {@code 0} = off. */
    public static final String SWAP_MB = "persist.ashfall.mem.swap_mb";

    /** Applied swapfile size in MB, {@code 0} off, {@code -1} unavailable. */
    public static final String STATUS_SWAP_MB = "ashfall.status.mem.swap_mb";
    /** {@code SwapTotal} from /proc/meminfo in KiB, {@code -1} unavailable. */
    public static final String STATUS_SWAP_TOTAL_KB = "ashfall.status.mem.swap_total_kb";
    /** {@code SwapTotal - SwapFree} in KiB, {@code -1} unavailable. */
    public static final String STATUS_SWAP_USED_KB = "ashfall.status.mem.swap_used_kb";

    /** Slider granularity in MB. */
    public static final int STEP_MB = 256;
    /**
     * The swapfile cap.  Deliberately independent of the installed RAM: 16 GB
     * is allowed on the 8 GB unit (issue #141).
     */
    public static final int SWAP_MAX_MB = 16 * 1024;
    /** The daemon's "no value" marker; also what the page reads before a publish. */
    public static final int UNAVAILABLE = -1;
    /**
     * Free space the daemon keeps on /data on top of the swapfile itself.  The
     * page only warns; {@code mem_swap_too_large} is decided against the same
     * reserve the daemon uses.
     */
    public static final long DATA_RESERVE_BYTES = 2L * 1024 * 1024 * 1024;

    private Memory() {
    }

    /** The requested swapfile size, clamped to the fixed 16 GB cap. */
    public static int swapMb() {
        return clamp(Props.getInt(SWAP_MB, 0), 0, SWAP_MAX_MB);
    }

    public static void setSwapMb(int mb) {
        Props.setInt(SWAP_MB, clamp(snapMb(mb), 0, SWAP_MAX_MB));
    }

    /** Rounds a size to the nearest step; keeps {@code 0} at {@code 0}. */
    public static int snapMb(int mb) {
        if (mb <= 0) {
            return 0;
        }
        return Math.round(mb / (float) STEP_MB) * STEP_MB;
    }

    /** Free space on /data in bytes, or {@code -1} when it cannot be read. */
    public static long freeDataBytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return UNAVAILABLE;
        }
    }

    /** Total size of /data in bytes, or {@code -1} when it cannot be read. */
    public static long totalDataBytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getTotalBytes();
        } catch (RuntimeException e) {
            return UNAVAILABLE;
        }
    }

    /** Applied swapfile size in MB, {@code -1} while the daemon reports nothing. */
    public static int statusSwapMb() {
        return Props.getInt(STATUS_SWAP_MB, UNAVAILABLE);
    }

    public static int statusSwapTotalKb() {
        return Props.getInt(STATUS_SWAP_TOTAL_KB, UNAVAILABLE);
    }

    public static int statusSwapUsedKb() {
        return Props.getInt(STATUS_SWAP_USED_KB, UNAVAILABLE);
    }

    public static boolean swapAvailable() {
        return statusSwapMb() != UNAVAILABLE;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
