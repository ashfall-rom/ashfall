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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A fan curve: 2..8 ascending {@code tempC:pct} points.
 *
 * <p>The wire format is the one documented in {@code docs/phase5.md} §2.1,
 * for example {@code 45:0,55:30,65:55,75:80,82:100}.
 */
public final class Curve {

    public static final String DEFAULT = "45:0,55:30,65:55,75:80,82:100";
    public static final int MIN_POINTS = 2;
    public static final int MAX_POINTS = 8;
    public static final int MIN_TEMP = 25;
    public static final int MAX_TEMP = 95;

    private final int[] mTemp;
    private final int[] mPct;

    private Curve(int[] temp, int[] pct) {
        mTemp = temp;
        mPct = pct;
    }

    public static Curve defaults() {
        return parse(DEFAULT);
    }

    /** Parses a property value; invalid input yields the built-in default. */
    public static Curve parse(String value) {
        final Curve parsed = tryParse(value);
        return parsed != null ? parsed : defaults();
    }

    public static Curve tryParse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        final String[] parts = value.trim().split(",");
        if (parts.length < MIN_POINTS || parts.length > MAX_POINTS) {
            return null;
        }
        final int[] temp = new int[parts.length];
        final int[] pct = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            final String[] pair = parts[i].trim().split(":");
            if (pair.length != 2) {
                return null;
            }
            try {
                temp[i] = Integer.parseInt(pair[0].trim());
                pct[i] = Integer.parseInt(pair[1].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (pct[i] < 0 || pct[i] > 100 || temp[i] < MIN_TEMP || temp[i] > MAX_TEMP) {
                return null;
            }
            if (i > 0 && temp[i] <= temp[i - 1]) {
                return null;
            }
        }
        return new Curve(temp, pct);
    }

    public static Curve of(List<int[]> points) {
        final List<int[]> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        final int[] temp = new int[sorted.size()];
        final int[] pct = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            temp[i] = clamp(sorted.get(i)[0], MIN_TEMP, MAX_TEMP);
            pct[i] = clamp(sorted.get(i)[1], 0, 100);
        }
        return new Curve(temp, pct);
    }

    public int size() {
        return mTemp.length;
    }

    public int temp(int index) {
        return mTemp[index];
    }

    public int pct(int index) {
        return mPct[index];
    }

    public int minTemp() {
        return mTemp[0];
    }

    public int maxTemp() {
        return mTemp[mTemp.length - 1];
    }

    /** Linear interpolation, clamped at both ends.  Used for the live marker. */
    public int percentFor(float temperature) {
        if (temperature <= mTemp[0]) {
            return mPct[0];
        }
        final int last = mTemp.length - 1;
        if (temperature >= mTemp[last]) {
            return mPct[last];
        }
        for (int i = 1; i < mTemp.length; i++) {
            if (temperature <= mTemp[i]) {
                final float span = mTemp[i] - mTemp[i - 1];
                final float ratio = span <= 0 ? 0 : (temperature - mTemp[i - 1]) / span;
                return Math.round(mPct[i - 1] + ratio * (mPct[i] - mPct[i - 1]));
            }
        }
        return mPct[last];
    }

    public Curve addPoint(int temp, int pct) {
        final List<int[]> points = toLists();
        for (int[] point : points) {
            if (point[0] == temp) {
                point[1] = clamp(pct, 0, 100);
                return of(points);
            }
        }
        points.add(new int[] {temp, pct});
        return of(points);
    }

    public Curve removePoint(int index) {
        if (size() <= MIN_POINTS) {
            return this;
        }
        final List<int[]> points = toLists();
        points.remove(index);
        return of(points);
    }

    /** Moves one point, keeping the curve valid (ascending, 2..8 points). */
    public Curve movePoint(int index, int temp, int pct) {
        final List<int[]> points = toLists();
        if (index < 0 || index >= points.size()) {
            return this;
        }
        points.set(index, new int[] {temp, clamp(pct, 0, 100)});
        points.sort((a, b) -> Integer.compare(a[0], b[0]));
        return of(points);
    }

    public List<int[]> points() {
        return toLists();
    }

    public String format() {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < mTemp.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(mTemp[i]).append(':').append(mPct[i]);
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "Curve[%s]", format());
    }

    private List<int[]> toLists() {
        final List<int[]> points = new ArrayList<>(mTemp.length);
        for (int i = 0; i < mTemp.length; i++) {
            points.add(new int[] {mTemp[i], mPct[i]});
        }
        return points;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
