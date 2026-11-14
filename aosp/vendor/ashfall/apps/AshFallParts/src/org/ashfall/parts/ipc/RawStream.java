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

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code RAW ON} stream from the input daemon, optionally coupled with
 * {@code GRAB ON} on the same connection (contract {@code docs/phase5.md} §6.2).
 *
 * <p>A separate socket is used (the daemon streams to the client that asked for
 * raw samples).  While grabbed, the daemon releases all held inputs and injects
 * nothing into uinput: every button, including Home, Back and Select, is only
 * reported in the {@code RAW} frames, so this is the tester's input path.  The
 * grab belongs to this connection and ends when it is closed (the daemon
 * releases it automatically), which is why the tester releases it in
 * {@code onPause}.
 */
public final class RawStream implements Closeable {

    private static final String TAG = "AshFallParts";
    private static final int REPLY_TIMEOUT_MS = 800;

    public interface Listener {
        void onSample(RawSample sample);

        void onClosed(IOException error);

        /** The daemon refused {@code GRAB ON}; the stream itself keeps working. */
        default void onGrabFailed(String reason) {
            // Optional: older daemons do not answer GRAB at all.
        }
    }

    private final LocalSocket mSocket;
    private final InputStream mInput;
    private final OutputStream mOutput;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final Thread mThread;
    private volatile boolean mGrabbed;

    private RawStream(LocalSocket socket, Listener listener, boolean grab) throws IOException {
        mSocket = socket;
        mInput = socket.getInputStream();
        mOutput = socket.getOutputStream();
        mOutput.write("RAW ON\n".getBytes(StandardCharsets.UTF_8));
        mOutput.flush();
        if (grab) {
            mOutput.write("GRAB ON\n".getBytes(StandardCharsets.UTF_8));
            mOutput.flush();
            mGrabbed = true;
        }
        // The daemon answers one packet per command.  Read those replies with a
        // short timeout so a daemon that predates GRAB cannot block the tester;
        // anything that is not a reply is forwarded as a sample.
        drainReplies(listener, grab ? 2 : 1);
        mThread = new Thread(() -> readLoop(listener), "ashfall-raw");
        mThread.setDaemon(true);
        mThread.start();
    }

    public static RawStream open(Listener listener) throws IOException {
        return open(listener, false);
    }

    /**
     * @param grab also send {@code GRAB ON} on this connection (tester mode)
     */
    public static RawStream open(Listener listener, boolean grab) throws IOException {
        final LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
        try {
            socket.connect(new LocalSocketAddress(CalClient.SOCKET_PATH,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(REPLY_TIMEOUT_MS);
            return new RawStream(socket, listener, grab);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            throw e;
        }
    }

    /**
     * Reads up to {@code count} {@code OK}/{@code ERR} replies, forwarding any
     * other packet to the listener.  A timeout means the daemon does not answer
     * that command (older build); the stream then simply continues.
     */
    private void drainReplies(Listener listener, int count) throws IOException {
        final byte[] buffer = new byte[4096];
        for (int i = 0; i < count; i++) {
            try {
                final int read = mInput.read(buffer);
                if (read <= 0) {
                    break;
                }
                final String text = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
                if (text.startsWith("ERR")) {
                    Log.w(TAG, "daemon refused: " + text);
                    mGrabbed = false;
                    if (listener != null) {
                        listener.onGrabFailed(text);
                    }
                } else if (!text.startsWith("OK")) {
                    dispatch(listener, text);
                }
            } catch (SocketTimeoutException timeout) {
                Log.i(TAG, "no reply to a stream command (older daemon?)");
                break;
            }
        }
        mSocket.setSoTimeout(0);
    }

    private void readLoop(Listener listener) {
        final byte[] buffer = new byte[4096];
        IOException error = null;
        try {
            while (!mClosed.get()) {
                final int read = mInput.read(buffer);
                if (read < 0) {
                    break;
                }
                dispatch(listener, new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            if (!mClosed.get()) {
                error = e;
                Log.w(TAG, "raw stream ended", e);
            }
        } finally {
            if (listener != null) {
                listener.onClosed(error);
            }
        }
    }

    private static void dispatch(Listener listener, String text) {
        if (listener == null) {
            return;
        }
        for (String line : text.split("\n")) {
            final RawSample sample = RawSample.parse(line);
            if (sample != null) {
                listener.onSample(sample);
            }
        }
    }

    public boolean isClosed() {
        return mClosed.get();
    }

    public boolean isGrabbed() {
        return mGrabbed && !mClosed.get();
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (mGrabbed) {
                mOutput.write("GRAB OFF\n".getBytes(StandardCharsets.UTF_8));
            }
            mOutput.write("RAW OFF\n".getBytes(StandardCharsets.UTF_8));
            mOutput.flush();
        } catch (IOException ignored) {
            // The daemon may already be gone; closing the socket releases the grab.
        }
        mGrabbed = false;
        try {
            mSocket.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
        mThread.interrupt();
    }
}
