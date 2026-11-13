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

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads and writes Android system properties.
 *
 * <p>{@code android.os.SystemProperties} is a hidden platform API because the
 * property namespace is a privileged interface.  This app is a platform-signed
 * system app, so it is allowed to use it; reflection keeps the same source
 * compilable outside the platform tree (the emulator preview build) and falls
 * back to {@code getprop}/{@code setprop} when reflection is blocked.
 */
public final class Props {
    private static final String TAG = "AshFallParts";

    private static boolean sInitialised;
    private static java.lang.reflect.Method sGet;
    private static java.lang.reflect.Method sGetDefault;
    private static java.lang.reflect.Method sSet;

    private Props() {
    }

    private static synchronized void init() {
        if (sInitialised) {
            return;
        }
        sInitialised = true;
        try {
            final Class<?> cls = Class.forName("android.os.SystemProperties");
            sGet = cls.getMethod("get", String.class);
            sGetDefault = cls.getMethod("get", String.class, String.class);
            sSet = cls.getMethod("set", String.class, String.class);
        } catch (Throwable t) {
            Log.w(TAG, "SystemProperties is not reachable, using getprop/setprop", t);
        }
    }

    public static String get(String key, String def) {
        init();
        if (sGetDefault != null) {
            try {
                final Object value = sGetDefault.invoke(null, key, def);
                if (value instanceof String) {
                    final String text = (String) value;
                    return text.isEmpty() ? def : text;
                }
            } catch (Throwable t) {
                Log.w(TAG, "read " + key + " failed", t);
            }
        }
        return execGet(key, def);
    }

    public static int getInt(String key, int def) {
        final String value = get(key, null);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean getBoolean(String key, boolean def) {
        final String value = get(key, null);
        if (value == null) {
            return def;
        }
        return "1".equals(value) || "true".equals(value) || "on".equals(value);
    }

    public static boolean set(String key, String value) {
        init();
        if (sSet != null) {
            try {
                sSet.invoke(null, key, value);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "write " + key + " failed", t);
            }
        }
        return execSet(key, value);
    }

    public static boolean setInt(String key, int value) {
        return set(key, Integer.toString(value));
    }

    private static String execGet(String key, String def) {
        BufferedReader reader = null;
        try {
            final Process process = new ProcessBuilder("/system/bin/getprop", key)
                    .redirectErrorStream(true).start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            final String line = reader.readLine();
            process.waitFor();
            if (line == null || line.isEmpty()) {
                return def;
            }
            return line;
        } catch (Throwable t) {
            Log.w(TAG, "getprop " + key + " failed", t);
            return def;
        } finally {
            closeQuietly(reader);
        }
    }

    private static boolean execSet(String key, String value) {
        try {
            final Process process = new ProcessBuilder("/system/bin/setprop", key, value)
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Throwable t) {
            Log.w(TAG, "setprop " + key + " failed", t);
            return false;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing to do.
        }
    }
}
