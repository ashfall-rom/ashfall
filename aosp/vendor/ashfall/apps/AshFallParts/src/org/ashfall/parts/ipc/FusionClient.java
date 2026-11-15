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

package org.ashfall.parts.ipc;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.ashfall.parts.R;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for the gamepad-fusion commands of the {@code ashfall_input} control
 * socket (contract {@code docs/phase6.md} §9.8), plus the three settings
 * properties the daemon reads.
 *
 * <p>The socket is the same {@code SOCK_SEQPACKET} local socket and framing as
 * {@link CalClient}: one text command per packet and one reply per packet.  The
 * app only writes settings ({@code persist.ashfall.pad.fusion},
 * {@code .layouts}, {@code .remap}) and asks the daemon to apply them; the
 * daemon is the only writer of the pad list, which it returns from
 * {@code FUSION STATUS}.  There is no {@code su}, no {@code dumpsys} and no
 * shell anywhere on this path.
 */
public final class FusionClient implements Closeable {

    public static final String SOCKET_PATH = CalClient.SOCKET_PATH;
    private static final int TIMEOUT_MS = 5000;
    /** A pad line that arrives in its own packet; a slow daemon must not hang the UI. */
    private static final int PAD_TIMEOUT_MS = 600;

    public static final String PROP_FUSION = "persist.ashfall.pad.fusion";
    public static final String PROP_LAYOUTS = "persist.ashfall.pad.layouts";
    public static final String PROP_REMAP = "persist.ashfall.pad.remap";

    public static final String MODE_DEFAULT = "DEFAULT";
    public static final String MODE_XBOX = "XBOX";
    public static final String MODE_NINTENDO = "NINTENDO";
    public static final String MODE_SWAP_AB = "SWAP_AB";
    public static final String MODE_SWAP_XY = "SWAP_XY";
    public static final String MODE_CUSTOM = "CUSTOM";

    /** The layout modes, in dialog order; {@link #MODE_LABELS} matches. */
    public static final String[] MODES = {
            MODE_DEFAULT, MODE_XBOX, MODE_NINTENDO, MODE_SWAP_AB, MODE_SWAP_XY, MODE_CUSTOM,
    };

    public static final int[] MODE_LABELS = {
            R.string.fusion_layout_default,
            R.string.fusion_layout_xbox,
            R.string.fusion_layout_nintendo,
            R.string.fusion_layout_swap_ab,
            R.string.fusion_layout_swap_xy,
            R.string.fusion_layout_custom,
    };

    /** Last {@code FUSION STATUS} reply, shared by the homepage row and the tile. */
    private static volatile Status sCached;
    private static volatile long sLastAttempt;
    private static final AtomicBoolean sFetching = new AtomicBoolean();

    private final LocalSocket mSocket;
    private final InputStream mInput;
    private final OutputStream mOutput;

    private FusionClient(LocalSocket socket) throws IOException {
        mSocket = socket;
        mInput = socket.getInputStream();
        mOutput = socket.getOutputStream();
    }

    public static FusionClient open() throws IOException {
        final LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
        try {
            socket.connect(new LocalSocketAddress(SOCKET_PATH,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(TIMEOUT_MS);
            return new FusionClient(socket);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            throw e;
        }
    }

    public String execute(String command) throws IOException {
        mOutput.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        mOutput.flush();
        return read();
    }

    /** Sends one command and accepts only {@code OK}; used by the write paths. */
    public void command(String request) throws IOException {
        final String reply = execute(request);
        if (!reply.startsWith("OK")) {
            throw new IOException(reply);
        }
    }

    /**
     * {@code FUSION STATUS}: the header packet plus one {@code PAD} line per
     * pad.  A reply that does not start with the documented header, or a pad
     * line with an unknown layout, degrades rather than throwing: the caller
     * shows the daemon-unavailable state instead of a half-parsed list.
     */
    public Status status() throws IOException {
        final List<String> lines = new ArrayList<>();
        addLines(lines, execute("FUSION STATUS"));
        Status status = Status.parse(lines);
        if (status == null) {
            throw new IOException("unexpected FUSION STATUS reply: "
                    + (lines.isEmpty() ? "<empty packet>" : lines.get(0)));
        }
        if (status.pads.size() >= status.count) {
            return status;
        }
        // The daemon may send the pad lines as separate packets.
        mSocket.setSoTimeout(PAD_TIMEOUT_MS);
        try {
            while (status.pads.size() < status.count) {
                final String packet;
                try {
                    packet = read();
                } catch (SocketTimeoutException timeout) {
                    break;
                }
                addLines(lines, packet);
                final Status next = Status.parse(lines);
                if (next == null) {
                    break;
                }
                status = next;
            }
        } finally {
            mSocket.setSoTimeout(TIMEOUT_MS);
        }
        return status;
    }

    private String read() throws IOException {
        final byte[] buffer = new byte[8192];
        final int read = mInput.read(buffer);
        if (read <= 0) {
            throw new IOException("no reply from " + SOCKET_PATH);
        }
        return new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
    }

    private static void addLines(List<String> lines, String packet) {
        for (String line : packet.split("\n")) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
    }

    /** One blocking round trip, or {@code null} when the daemon is unreachable. */
    public static Status query() {
        final FusionClient client = openQuietly();
        if (client == null) {
            return null;
        }
        try {
            return client.status();
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(client);
        }
    }

    private static FusionClient openQuietly() {
        try {
            return open();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        mSocket.close();
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    /** The last answered {@code FUSION STATUS}, or {@code null} before the first. */
    public static Status cache() {
        return sCached;
    }

    /**
     * Refreshes {@link #cache()} on a background thread, at most once per
     * {@code minIntervalMs}.  The homepage row and the tile call this from
     * their existing ticks, so no surface adds a timer of its own.
     */
    public static void refreshCacheAsync(long minIntervalMs) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (!isOn() || now - sLastAttempt < minIntervalMs) {
            return;
        }
        if (!sFetching.compareAndSet(false, true)) {
            return;
        }
        sLastAttempt = now;
        new Thread(() -> {
            sCached = query();
            sFetching.set(false);
        }, "ashfall-fusion-cache").start();
    }

    // ---- settings properties -------------------------------------------------

    /** The switch state.  The app is the writer, so this is never stale. */
    public static boolean isOn() {
        return Props.getBoolean(PROP_FUSION, false);
    }

    public static void setOn(boolean on) {
        Props.setInt(PROP_FUSION, on ? 1 : 0);
    }

    /** The persisted layout of one pad, {@link #MODE_DEFAULT} when unset. */
    public static String layoutFor(String key) {
        return sanitizeMode(valueFor(PROP_LAYOUTS, key));
    }

    public static void setLayoutFor(String key, String mode) {
        putValue(PROP_LAYOUTS, key, sanitizeMode(mode));
    }

    /** The persisted custom map of one pad, empty when unset. */
    public static String remapFor(String key) {
        return sanitizeMap(valueFor(PROP_REMAP, key));
    }

    /** Stores the map; {@code null} or an empty map removes the entry. */
    public static void setRemapFor(String key, String map) {
        final String clean = sanitizeMap(map);
        putValue(PROP_REMAP, key, clean.isEmpty() ? null : clean);
    }

    /** {@code from->to,from->to}: the v1 raw code list (docs/phase6.md §9.7). */
    public static boolean isValidMap(String map) {
        if (map == null) {
            return false;
        }
        final String text = map.trim();
        return !text.isEmpty() && text.matches("\\d+->\\d+(,\\d+->\\d+)*");
    }

    public static String sanitizeMap(String map) {
        return isValidMap(map) ? map.trim() : "";
    }

    public static String sanitizeMode(String mode) {
        if (mode != null) {
            for (String known : MODES) {
                if (known.equals(mode)) {
                    return known;
                }
            }
        }
        return MODE_DEFAULT;
    }

    public static int modeIndex(String mode) {
        final String known = sanitizeMode(mode);
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(known)) {
                return i;
            }
        }
        return 0;
    }

    public static String modeName(Context context, String mode) {
        return context.getString(MODE_LABELS[modeIndex(mode)]);
    }

    /**
     * The one-line fusion state shared by the page, the homepage summary and
     * the tile.  The switch state comes from the property (the app writes it,
     * so a toggle shows up immediately); the pad count comes from the reply.
     */
    public static String stateText(Context context, Status status) {
        final boolean available = status != null && status.available;
        if (!isOn()) {
            return context.getString(R.string.fusion_state_off);
        }
        if (!available) {
            return context.getString(R.string.fusion_state_unavailable);
        }
        return status.pads.size() >= 2
                ? context.getString(R.string.fusion_state_pads, status.pads.size())
                : context.getString(R.string.fusion_state_no_pad);
    }

    private static String valueFor(String property, String key) {
        for (String entry : Props.get(property, "").split(";")) {
            final int equals = entry.indexOf('=');
            if (equals > 0 && entry.substring(0, equals).equals(key)) {
                return entry.substring(equals + 1);
            }
        }
        return "";
    }

    /** Rewrites one {@code key=value} entry, keeping every other pad's entry. */
    private static void putValue(String property, String key, String value) {
        final StringBuilder out = new StringBuilder();
        boolean replaced = false;
        for (String entry : Props.get(property, "").split(";")) {
            final int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (entry.substring(0, equals).equals(key)) {
                if (value == null) {
                    continue;
                }
                entry = key + "=" + value;
                replaced = true;
            }
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(entry);
        }
        if (!replaced && value != null) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(key).append('=').append(value);
        }
        Props.set(property, out.toString());
    }

    // ---- reply model ---------------------------------------------------------

    /** One pad from a {@code FUSION STATUS} reply. */
    public static final class Pad {
        public final String key;
        public final String layout;
        public final long seenMs;
        public final String name;

        Pad(String key, String layout, long seenMs, String name) {
            this.key = key;
            this.layout = layout;
            this.seenMs = seenMs;
            this.name = name;
        }

        static Pad parse(String line) {
            final String[] parts = line.split("\\s+", 5);
            if (parts.length < 4 || !"PAD".equals(parts[0]) || parts[1].isEmpty()) {
                return null;
            }
            long seen = -1;
            try {
                seen = Long.parseLong(parts[3]);
            } catch (NumberFormatException ignored) {
                // A missing last-seen is not worth dropping the pad for.
            }
            return new Pad(parts[1], sanitizeMode(parts[2]), seen,
                    parts.length > 4 ? parts[4].trim() : parts[1]);
        }
    }

    /** A parsed {@code FUSION STATUS} reply. */
    public static final class Status {
        public final boolean available;
        public final boolean fusion;
        public final int count;
        public final List<Pad> pads;

        private Status(boolean available, boolean fusion, int count, List<Pad> pads) {
            this.available = available;
            this.fusion = fusion;
            this.count = count;
            this.pads = pads;
        }

        /**
         * {@code OK FUSION <0|1> PADS <n>} followed by {@code PAD <key>
         * <LAYOUT> <seen_ms> <name...>}; {@code null} for anything else.
         */
        public static Status parse(List<String> lines) {
            if (lines == null || lines.isEmpty()) {
                return null;
            }
            final String[] header = lines.get(0).split("\\s+");
            if (header.length < 5 || !"OK".equals(header[0]) || !"FUSION".equals(header[1])
                    || !"PADS".equals(header[3])) {
                return null;
            }
            final boolean fusion;
            if ("1".equals(header[2])) {
                fusion = true;
            } else if ("0".equals(header[2])) {
                fusion = false;
            } else {
                return null;
            }
            final int count;
            try {
                count = Integer.parseInt(header[4]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (count < 0) {
                return null;
            }
            final List<Pad> pads = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                final Pad pad = Pad.parse(lines.get(i));
                if (pad != null) {
                    pads.add(pad);
                }
            }
            return new Status(true, fusion, count, pads);
        }
    }
}
