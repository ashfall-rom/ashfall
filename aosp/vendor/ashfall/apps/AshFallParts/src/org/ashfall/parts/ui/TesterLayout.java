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

package org.ashfall.parts.ui;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import org.ashfall.parts.R;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Geometry of the controller tester, read from {@code res/raw/tester_layout.json}
 * (schema v1, issue #103).
 *
 * <p>Coordinates are fractions of the view: {@code x}/{@code y} of the width and
 * height, {@code r} of the height for circles, {@code w}/{@code h} of the width
 * and height for rectangles (the rectangle is centred on {@code x}/{@code y}).
 *
 * <p>The file is owned by the temporary web layout editor and may be rewritten
 * at any time, so the loader is deliberately forgiving: every missing, invalid
 * or out-of-range value falls back to the built-in default for that value, an
 * unknown {@code version} or unreadable/invalid JSON falls back to the complete
 * defaults, and nothing ever throws into the UI.
 */
public final class TesterLayout {

    public static final int VERSION = 1;
    private static final String TAG = "AshFallParts";

    /** x/y fractions of w/h, r fraction of h. */
    public static final class Circle {
        public final float x;
        public final float y;
        public final float r;

        Circle(float x, float y, float r) {
            this.x = x;
            this.y = y;
            this.r = r;
        }
    }

    /** Centre x/y fractions of w/h, full size w fraction of w and h fraction of h. */
    public static final class Rect {
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        Rect(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public static final class Point {
        public final float x;
        public final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public final Circle leftStick;
    public final Circle rightStick;
    public final Circle dpad;
    public final Circle buttonX;
    public final Circle buttonY;
    public final Circle buttonA;
    public final Circle buttonB;
    public final Rect l1;
    public final Rect r1;
    public final Rect l2;
    public final Rect r2;
    public final Rect back;
    public final Rect home;
    public final Rect select;
    public final Rect start;
    public final Point status;

    private TesterLayout(Circle leftStick, Circle rightStick, Circle dpad, Circle buttonX,
            Circle buttonY, Circle buttonA, Circle buttonB, Rect l1, Rect r1, Rect l2, Rect r2,
            Rect back, Rect home, Rect select, Rect start, Point status) {
        this.leftStick = leftStick;
        this.rightStick = rightStick;
        this.dpad = dpad;
        this.buttonX = buttonX;
        this.buttonY = buttonY;
        this.buttonA = buttonA;
        this.buttonB = buttonB;
        this.l1 = l1;
        this.r1 = r1;
        this.l2 = l2;
        this.r2 = r2;
        this.back = back;
        this.home = home;
        this.select = select;
        this.start = start;
        this.status = status;
    }

    /** The schema defaults, also the fallback for anything the JSON gets wrong. */
    public static TesterLayout defaults() {
        return new TesterLayout(
                new Circle(0.165f, 0.300f, 0.175f),   // left_stick
                new Circle(0.835f, 0.720f, 0.175f),   // right_stick
                new Circle(0.165f, 0.720f, 0.128f),   // dpad (r = arm length)
                new Circle(0.835f, 0.2107f, 0.0533f), // button_x
                new Circle(0.7735f, 0.320f, 0.0533f), // button_y
                new Circle(0.8965f, 0.320f, 0.0533f), // button_a
                new Circle(0.835f, 0.4293f, 0.0533f), // button_b
                new Rect(0.115f, 0.075f, 0.130f, 0.072f),  // l1
                new Rect(0.885f, 0.075f, 0.130f, 0.072f),  // r1
                new Rect(0.028f, 0.505f, 0.0339f, 0.690f), // l2
                new Rect(0.972f, 0.505f, 0.0339f, 0.690f), // r2
                new Rect(0.415f, 0.145f, 0.110f, 0.074f),  // back
                new Rect(0.585f, 0.145f, 0.110f, 0.074f),  // home
                new Rect(0.445f, 0.885f, 0.124f, 0.086f),  // select
                new Rect(0.555f, 0.885f, 0.124f, 0.086f),  // start
                new Point(0.365f, 0.240f));                // status
    }

    /** Reads {@code res/raw/tester_layout.json}; never throws. */
    public static TesterLayout load(Context context) {
        if (context == null) {
            return defaults();
        }
        final Resources resources = context.getResources();
        if (resources == null) {
            return defaults();
        }
        try (InputStream in = resources.openRawResource(R.raw.tester_layout)) {
            return parse(readFully(in));
        } catch (Exception e) {
            // Missing resource (old build), unreadable file or invalid JSON.
            Log.w(TAG, "tester layout: cannot read res/raw/tester_layout, using defaults", e);
            return defaults();
        }
    }

    /** Package-private so the host check can feed bad JSON without an Android runtime. */
    static TesterLayout parse(String json) {
        final TesterLayout def = defaults();
        if (json == null || json.trim().isEmpty()) {
            Log.w(TAG, "tester layout: empty file, using defaults");
            return def;
        }
        try {
            final JSONObject root = new JSONObject(json);
            final Object version = root.opt("version");
            if (!(version instanceof Number) || ((Number) version).intValue() != VERSION) {
                Log.w(TAG, "tester layout: unknown version " + version + ", using defaults");
                return def;
            }
            final JSONObject controls = root.optJSONObject("controls");
            if (controls == null) {
                Log.w(TAG, "tester layout: no controls object, using defaults");
                return def;
            }
            return new TesterLayout(
                    circle(controls, "left_stick", def.leftStick),
                    circle(controls, "right_stick", def.rightStick),
                    circle(controls, "dpad", def.dpad),
                    circle(controls, "button_x", def.buttonX),
                    circle(controls, "button_y", def.buttonY),
                    circle(controls, "button_a", def.buttonA),
                    circle(controls, "button_b", def.buttonB),
                    rect(controls, "l1", def.l1),
                    rect(controls, "r1", def.r1),
                    rect(controls, "l2", def.l2),
                    rect(controls, "r2", def.r2),
                    rect(controls, "back", def.back),
                    rect(controls, "home", def.home),
                    rect(controls, "select", def.select),
                    rect(controls, "start", def.start),
                    point(controls, "status", def.status));
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "tester layout: invalid JSON, using defaults", e);
            return def;
        }
    }

    private static Circle circle(JSONObject controls, String name, Circle def) {
        final JSONObject o = control(controls, name);
        if (o == null) {
            return def;
        }
        return new Circle(fraction(o, name, "x", def.x, false),
                fraction(o, name, "y", def.y, false),
                fraction(o, name, "r", def.r, true));
    }

    private static Rect rect(JSONObject controls, String name, Rect def) {
        final JSONObject o = control(controls, name);
        if (o == null) {
            return def;
        }
        return new Rect(fraction(o, name, "x", def.x, false),
                fraction(o, name, "y", def.y, false),
                fraction(o, name, "w", def.w, true),
                fraction(o, name, "h", def.h, true));
    }

    private static Point point(JSONObject controls, String name, Point def) {
        final JSONObject o = control(controls, name);
        if (o == null) {
            return def;
        }
        return new Point(fraction(o, name, "x", def.x, false),
                fraction(o, name, "y", def.y, false));
    }

    private static JSONObject control(JSONObject controls, String name) {
        final Object value = controls.opt(name);
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value != null) {
            Log.w(TAG, "tester layout: " + name + " is not an object, using its defaults");
        }
        return null;
    }

    /**
     * One 0..1 fraction.  {@code positive} is true for sizes (r/w/h), which must
     * be greater than zero; x/y may be exactly 0 or 1.
     */
    private static float fraction(JSONObject o, String name, String key, float def,
            boolean positive) {
        final Object value = o.opt(key);
        if (!(value instanceof Number)) {
            Log.w(TAG, "tester layout: " + name + "." + key + " missing or not a number, using "
                    + def);
            return def;
        }
        final double number = ((Number) value).doubleValue();
        final boolean valid = Double.isFinite(number)
                && (positive ? number > 0 && number <= 1 : number >= 0 && number <= 1);
        if (!valid) {
            Log.w(TAG, "tester layout: " + name + "." + key + "=" + value
                    + " out of range, using " + def);
            return def;
        }
        return (float) number;
    }

    private static String readFully(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
