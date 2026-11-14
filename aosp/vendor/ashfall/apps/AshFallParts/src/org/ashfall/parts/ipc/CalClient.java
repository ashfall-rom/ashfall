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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Client for the {@code ashfall_input} control socket (docs/phase5.md §2.3).
 *
 * <p>The socket is a {@code SOCK_SEQPACKET} local socket: one text command per
 * packet and one {@code OK}/{@code ERR} reply per command.
 */
public final class CalClient implements Closeable {

    public static final String SOCKET_PATH = "/dev/socket/ashfall_input";
    private static final int TIMEOUT_MS = 5000;

    private final LocalSocket mSocket;
    private final InputStream mInput;
    private final OutputStream mOutput;

    private CalClient(LocalSocket socket) throws IOException {
        mSocket = socket;
        mInput = socket.getInputStream();
        mOutput = socket.getOutputStream();
    }

    public static CalClient open() throws IOException {
        final LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
        try {
            socket.connect(new LocalSocketAddress(SOCKET_PATH,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(TIMEOUT_MS);
            return new CalClient(socket);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            throw e;
        }
    }

    public static boolean isAvailable() {
        CalClient client = null;
        try {
            client = open();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(client);
        }
    }

    public String execute(String command) throws IOException {
        mOutput.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        mOutput.flush();
        final byte[] buffer = new byte[8192];
        final int read = mInput.read(buffer);
        if (read <= 0) {
            throw new IOException("no reply from " + SOCKET_PATH);
        }
        return new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
    }

    public CalData getCal() throws IOException {
        return CalData.parse(execute("GET CAL"));
    }

    public void setCal(CalData data) throws IOException {
        final String reply = execute("SET CAL " + data.toCommandArguments());
        if (!reply.startsWith("OK")) {
            throw new IOException(reply);
        }
    }

    public void reset() throws IOException {
        final String reply = execute("RESET CAL");
        if (!reply.startsWith("OK")) {
            throw new IOException(reply);
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
}
