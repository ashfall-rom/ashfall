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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Memory page interface: zram and the swapfile (issue #141,
 * {@code docs/phase6.md} §9.3).
 *
 * <p>The page is a settings client.  It writes only the two
 * {@code persist.ashfall.mem.*} values and reads the
 * {@code ashfall.status.mem.*} values published by {@code ashfall-hwd}; it
 * never writes a status property and never touches {@code /sys/block/zram0} or
 * {@code /proc/swaps}.  zram (priority 1) and the swapfile (priority 2)
 * coexist, and the daemon re-validates and clamps every request, so nothing
 * here clamps a write on the way out - the page shows what was asked for and
 * the daemon's effective value separately.
 *
 * <p>The zram cap follows {@code MemTotal}, never a constant: the RP5 ships
 * with 8 GB and 12 GB, so half of RAM is 4 GB on one unit and 6 GB on the
 * other.
 */
public final class Memory {

    /** zram size request in MB; {@code 0} = off. */
    public static final String ZRAM_MB = "persist.ashfall.mem.zram_mb";
    /** Swapfile size request in MB; {@code 0} = off. */
    public static final String SWAP_MB = "persist.ashfall.mem.swap_mb";

    /** Applied zram size in MB, {@code 0} off, {@code -1} unavailable. */
    public static final String STATUS_ZRAM_MB = "ashfall.status.mem.zram_mb";
    /** Applied swapfile size in MB, {@code 0} off, {@code -1} unavailable. */
    public static final String STATUS_SWAP_MB = "ashfall.status.mem.swap_mb";
    /** zram {@code mm_stat} compressed data size in KiB, {@code -1} unavailable. */
    public static final String STATUS_ZRAM_USED_KB = "ashfall.status.mem.zram_used_kb";
    /** zram {@code mm_stat} saving in KiB (uncompressed minus compressed). */
    public static final String STATUS_ZRAM_SAVED_KB = "ashfall.status.mem.zram_saved_kb";
    /** {@code SwapTotal} from /proc/meminfo in KiB, {@code -1} unavailable. */
    public static final String STATUS_SWAP_TOTAL_KB = "ashfall.status.mem.swap_total_kb";
    /** {@code SwapTotal - SwapFree} in KiB, {@code -1} unavailable. */
    public static final String STATUS_SWAP_USED_KB = "ashfall.status.mem.swap_used_kb";

    /** Slider granularity in MB; both controls use it. */
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

    private static final String MEMINFO = "/proc/meminfo";
    private static final long KB_PER_MB = 1024L;

    private Memory() {
    }

    /**
     * {@code MemTotal} in MB, or {@code -1} when {@code /proc/meminfo} cannot
     * be read.  Android exposes it to unprivileged apps, so this is the RAM
     * variant the unit really has.
     */
    public static int memTotalMb() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(MEMINFO));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("MemTotal:")) {
                    continue;
                }
                // "MemTotal:        7644324 kB"
                final String[] fields = line.trim().split("\\s+");
                if (fields.length < 2) {
                    return UNAVAILABLE;
                }
                return (int) (Long.parseLong(fields[1]) / KB_PER_MB);
            }
            return UNAVAILABLE;
        } catch (IOException | NumberFormatException e) {
            return UNAVAILABLE;
        } finally {
            closeQuietly(reader);
        }
    }

    /**
     * The zram slider maximum: half of {@code MemTotal}, rounded down to a
     * step.  {@code 0} when the RAM size is unknown, which disables the row.
     */
    public static int zramMaxMb() {
        final int total = memTotalMb();
        if (total <= 0) {
            return 0;
        }
        return ((total / 2) / STEP_MB) * STEP_MB;
    }

    /** The requested zram size, clamped to the current half-of-RAM cap. */
    public static int zramMb() {
        return clamp(Props.getInt(ZRAM_MB, 0), 0, Math.max(0, zramMaxMb()));
    }

    public static void setZramMb(int mb) {
        final int max = Math.max(Memory.STEP_MB, zramMaxMb());
        Props.setInt(ZRAM_MB, clamp(snapMb(mb), 0, max));
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

    /** Applied zram size in MB, {@code -1} while the daemon reports nothing. */
    public static int statusZramMb() {
        return Props.getInt(STATUS_ZRAM_MB, UNAVAILABLE);
    }

    /** Applied swapfile size in MB, {@code -1} while the daemon reports nothing. */
    public static int statusSwapMb() {
        return Props.getInt(STATUS_SWAP_MB, UNAVAILABLE);
    }

    /** Compressed size of what zram holds in KiB, {@code -1} unavailable. */
    public static int statusZramUsedKb() {
        return Props.getInt(STATUS_ZRAM_USED_KB, UNAVAILABLE);
    }

    /** RAM the compressor saved in KiB, {@code -1} unavailable. */
    public static int statusZramSavedKb() {
        return Props.getInt(STATUS_ZRAM_SAVED_KB, UNAVAILABLE);
    }

    public static int statusSwapTotalKb() {
        return Props.getInt(STATUS_SWAP_TOTAL_KB, UNAVAILABLE);
    }

    public static int statusSwapUsedKb() {
        return Props.getInt(STATUS_SWAP_USED_KB, UNAVAILABLE);
    }

    /** zram is off the table only when the daemon says so or RAM is unknown. */
    public static boolean zramAvailable() {
        return statusZramMb() != UNAVAILABLE && zramMaxMb() > 0;
    }

    public static boolean swapAvailable() {
        return statusSwapMb() != UNAVAILABLE;
    }

    /** {@code part} as a percentage of {@code whole}, rounded; {@code -1} if unknown. */
    public static int percentOf(int part, int whole) {
        if (part < 0 || whole <= 0) {
            return UNAVAILABLE;
        }
        return Math.round(part * 100f / whole);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }
}
