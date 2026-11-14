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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The 16 calibration values of the stock {@code joys} format:
 * {@code {left,right}_{x,y}_{min,center,max}} and {@code {l2,r2}_{min,max}}.
 */
public final class CalData {

    public static final String[] KEYS = {
            "left_x_min", "left_x_center", "left_x_max",
            "left_y_min", "left_y_center", "left_y_max",
            "right_x_min", "right_x_center", "right_x_max",
            "right_y_min", "right_y_center", "right_y_max",
            "l2_min", "l2_max",
            "r2_min", "r2_max",
    };

    private final LinkedHashMap<String, Float> mValues = new LinkedHashMap<>();
    private String mSource = "default";

    public static CalData empty() {
        return new CalData();
    }

    /** Parses {@code OK <source> k=v ...}. */
    public static CalData parse(String reply) throws IOException {
        if (reply == null || reply.isEmpty()) {
            throw new IOException("empty reply");
        }
        final String[] tokens = reply.trim().split("\\s+");
        if (!"OK".equals(tokens[0])) {
            throw new IOException(reply);
        }
        final CalData data = new CalData();
        if (tokens.length > 1) {
            data.mSource = tokens[1];
        }
        for (int i = 2; i < tokens.length; i++) {
            final String token = tokens[i];
            final int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = token.substring(0, eq);
            try {
                data.mValues.put(key, Float.parseFloat(token.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // Ignore values we cannot parse; the daemon validates on write.
            }
        }
        return data;
    }

    public String source() {
        return mSource;
    }

    public boolean has(String key) {
        return mValues.containsKey(key);
    }

    public float get(String key, float def) {
        final Float value = mValues.get(key);
        return value == null ? def : value;
    }

    public void put(String key, float value) {
        mValues.put(key, value);
    }

    public Map<String, Float> values() {
        return new LinkedHashMap<>(mValues);
    }

    public boolean isComplete() {
        for (String key : KEYS) {
            if (!mValues.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    /** Formats the {@code k=v} list of a {@code SET CAL} command. */
    public String toCommandArguments() {
        final StringBuilder builder = new StringBuilder();
        for (String key : KEYS) {
            final Float value = mValues.get(key);
            if (value == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(key).append('=').append(format(value));
        }
        return builder.toString();
    }

    private static String format(float value) {
        if (value == Math.round(value)) {
            return String.format(Locale.US, "%d", Math.round(value));
        }
        return String.format(Locale.US, "%.4f", value);
    }
}
