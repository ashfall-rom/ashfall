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

import android.os.SystemClock;

import org.ashfall.parts.ipc.Props;

/**
 * Stick ring lighting property interface (docs/phase5.md §2.2).
 *
 * <p>There is no explicit "target" property: the UI's Left/Right/Both selector
 * maps onto {@code led.sync}.  Both means sync=1 (the right ring follows the
 * left colour), Left/Right mean sync=0 and only one colour is changed.
 */
public final class Led {

    public static final String MODE = "persist.ashfall.led.mode";
    public static final String COLOR_LEFT = "persist.ashfall.led.color_left";
    public static final String COLOR_RIGHT = "persist.ashfall.led.color_right";
    public static final String SYNC = "persist.ashfall.led.sync";
    public static final String BRIGHTNESS = "persist.ashfall.led.brightness";
    public static final String SPEED = "persist.ashfall.led.speed";

    /**
     * UI-only flag for "Match screen" (#167).  The daemon never reads it: the
     * sampler publishes {@code ashfall.ui.led_override} instead, and
     * {@link #MODE} keeps the last real effect.
     */
    public static final String SCREEN_SYNC = "persist.ashfall.led.screen_sync";
    /** Capture rate of the screen sampler, frames per second: 10 (default) / 15 / 30. */
    public static final String SCREEN_RATE = "persist.ashfall.led.screen_rate";
    /**
     * Non-persistent suspend token of the gamepad tester (#167).  It changes
     * every 500 ms while the tester is visible, so a killed tester cannot pause
     * screen sync forever; {@link #suspendActive()} applies the same 1.5 s
     * window the daemon uses for the override heartbeat (#193).
     */
    public static final String SUSPEND = "ashfall.ui.led_suspend";
    public static final long SUSPEND_TIMEOUT_MS = 1500;

    public static final String MODE_OFF = "off";
    public static final String MODE_STATIC = "static";
    public static final String MODE_BREATHING = "breathing";
    public static final String MODE_RAINBOW = "rainbow";
    public static final String MODE_DUO = "duo";
    /**
     * Sixth effect entry, "Match screen".  Never stored in {@link #MODE}: it is
     * a UI value that turns {@link #SCREEN_SYNC} on and leaves the last real
     * effect in place, so switching it off restores that effect.
     */
    public static final String MODE_SCREEN = "screen";

    public static final int DEFAULT_SCREEN_RATE = 10;
    public static final int[] SCREEN_RATES = {10, 15, 30};

    public static final String DEFAULT_COLOR = "FF8A3D";
    public static final int DEFAULT_BRIGHTNESS = 60;
    public static final int DEFAULT_SPEED = 5;

    public static final int TARGET_BOTH = 0;
    public static final int TARGET_LEFT = 1;
    public static final int TARGET_RIGHT = 2;

    public static final String[] MODES = {
            MODE_OFF, MODE_STATIC, MODE_BREATHING, MODE_RAINBOW, MODE_DUO
    };

    private static long sSuspendToken;
    private static long sSuspendAt;

    private Led() {
    }

    public static String mode() {
        final String mode = Props.get(MODE, MODE_STATIC);
        for (String candidate : MODES) {
            if (candidate.equals(mode)) {
                return mode;
            }
        }
        return MODE_STATIC;
    }

    /**
     * Writes the real effect.  {@link #MODE_SCREEN} is the virtual sixth entry:
     * it turns screen sync on and does not touch {@link #MODE}, so the effect
     * that was active before comes back when screen sync is switched off.
     * Turning lighting off always clears screen sync.
     */
    public static void setMode(String mode) {
        if (MODE_SCREEN.equals(mode)) {
            if (MODE_OFF.equals(mode())) {
                // Screen sync needs a real effect behind it for the daemon's
                // fallback when the sampler stops.
                Props.set(MODE, MODE_STATIC);
            }
            setScreenSync(true);
            return;
        }
        setScreenSync(false);
        Props.set(MODE, mode);
    }

    /** Screen sync as seen by the UI; off whenever lighting is off. */
    public static boolean screenSync() {
        return Props.getBoolean(SCREEN_SYNC, false) && isOn();
    }

    /** Raw flag; the service uses this so it can clean up after lighting off. */
    public static boolean screenSyncFlag() {
        return Props.getBoolean(SCREEN_SYNC, false);
    }

    /**
     * True while the gamepad tester's suspend token keeps changing within
     * {@link #SUSPEND_TIMEOUT_MS}.  A stale token (tester killed) is inactive,
     * so Match screen resumes on its own (#193).
     */
    public static synchronized boolean suspendActive() {
        final String value = Props.get(SUSPEND, "");
        if (value.isEmpty()) {
            sSuspendToken = 0;
            sSuspendAt = 0;
            return false;
        }
        long token;
        try {
            token = Long.parseLong(value);
        } catch (NumberFormatException e) {
            token = value.hashCode();
        }
        final long now = SystemClock.elapsedRealtime();
        if (token != sSuspendToken) {
            sSuspendToken = token;
            sSuspendAt = now;
            return true;
        }
        return sSuspendAt != 0 && now - sSuspendAt <= SUSPEND_TIMEOUT_MS;
    }

    public static void setScreenSync(boolean on) {
        Props.set(SCREEN_SYNC, on ? "1" : "0");
    }

    /**
     * The effect the UI shows: "Match screen" while screen sync is on, the
     * stored real effect otherwise.
     */
    public static String effect() {
        return screenSync() ? MODE_SCREEN : mode();
    }

    public static int screenRate() {
        final int value = Props.getInt(SCREEN_RATE, DEFAULT_SCREEN_RATE);
        for (int candidate : SCREEN_RATES) {
            if (candidate == value) {
                return candidate;
            }
        }
        return DEFAULT_SCREEN_RATE;
    }

    public static void setScreenRate(int hz) {
        for (int candidate : SCREEN_RATES) {
            if (candidate == hz) {
                Props.setInt(SCREEN_RATE, hz);
                return;
            }
        }
    }

    public static boolean isOn() {
        return !MODE_OFF.equals(mode());
    }

    public static String colorLeft() {
        return sanitizeColor(Props.get(COLOR_LEFT, DEFAULT_COLOR), DEFAULT_COLOR);
    }

    public static String colorRight() {
        return sanitizeColor(Props.get(COLOR_RIGHT, DEFAULT_COLOR), DEFAULT_COLOR);
    }

    public static boolean sync() {
        return Props.getBoolean(SYNC, true);
    }

    public static void setSync(boolean sync) {
        Props.set(SYNC, sync ? "1" : "0");
    }

    /** Applies a colour to Both / Left / Right, matching the sync semantics. */
    public static void setColor(int target, String rgb) {
        final String color = sanitizeColor(rgb, DEFAULT_COLOR);
        switch (target) {
            case TARGET_LEFT:
                setSync(false);
                Props.set(COLOR_LEFT, color);
                break;
            case TARGET_RIGHT:
                setSync(false);
                Props.set(COLOR_RIGHT, color);
                break;
            case TARGET_BOTH:
            default:
                setSync(true);
                Props.set(COLOR_LEFT, color);
                Props.set(COLOR_RIGHT, color);
                break;
        }
    }

    /** The colour currently edited for a target. */
    public static String colorFor(int target) {
        return target == TARGET_RIGHT ? colorRight() : colorLeft();
    }

    public static int brightness() {
        return clamp(Props.getInt(BRIGHTNESS, DEFAULT_BRIGHTNESS), 0, 100);
    }

    public static void setBrightness(int value) {
        Props.setInt(BRIGHTNESS, clamp(value, 0, 100));
    }

    public static int speed() {
        return clamp(Props.getInt(SPEED, DEFAULT_SPEED), 1, 10);
    }

    public static void setSpeed(int value) {
        Props.setInt(SPEED, clamp(value, 1, 10));
    }

    public static String sanitizeColor(String rgb, String def) {
        if (rgb == null) {
            return def;
        }
        String value = rgb.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return def;
        }
        for (int i = 0; i < 6; i++) {
            final char c = value.charAt(i);
            final boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return def;
            }
        }
        return value.toUpperCase(java.util.Locale.US);
    }

    public static int colorToArgb(String rgb) {
        try {
            return 0xFF000000 | Integer.parseInt(sanitizeColor(rgb, DEFAULT_COLOR), 16);
        } catch (NumberFormatException e) {
            return 0xFFFF8A3D;
        }
    }

    public static String argbToColor(int argb) {
        return String.format(java.util.Locale.US, "%06X", argb & 0xFFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
