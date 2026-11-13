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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code RAW ON} stream from the input daemon.
 *
 * <p>A separate socket is used (the daemon streams to the client that asked for
 * raw samples); closing this object sends {@code RAW OFF} and closes the
 * socket, which also stops the stream on the daemon side.
 */
public final class RawStream implements Closeable {

    private static final String TAG = "AshFallParts";

    public interface Listener {
        void onSample(RawSample sample);

        void onClosed(IOException error);
    }

    private final LocalSocket mSocket;
    private final InputStream mInput;
    private final OutputStream mOutput;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final Thread mThread;

    private RawStream(LocalSocket socket, Listener listener) throws IOException {
        mSocket = socket;
        mInput = socket.getInputStream();
        mOutput = socket.getOutputStream();
        mOutput.write("RAW ON\n".getBytes(StandardCharsets.UTF_8));
        mOutput.flush();
        mThread = new Thread(() -> readLoop(listener), "ashfall-raw");
        mThread.setDaemon(true);
        mThread.start();
    }

    public static RawStream open(Listener listener) throws IOException {
        final LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
        try {
            socket.connect(new LocalSocketAddress(CalClient.SOCKET_PATH,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(0);
            return new RawStream(socket, listener);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            throw e;
        }
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
                final String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    final RawSample sample = RawSample.parse(line);
                    if (sample != null) {
                        listener.onSample(sample);
                    }
                }
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

    public boolean isClosed() {
        return mClosed.get();
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            mOutput.write("RAW OFF\n".getBytes(StandardCharsets.UTF_8));
            mOutput.flush();
        } catch (IOException ignored) {
            // The daemon may already be gone.
        }
        try {
            mSocket.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
        mThread.interrupt();
    }
}
