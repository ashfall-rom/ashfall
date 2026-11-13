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
    public static final String SCREEN_OFF = "persist.ashfall.led.screen_off";

    public static final String MODE_OFF = "off";
    public static final String MODE_STATIC = "static";
    public static final String MODE_BREATHING = "breathing";
    public static final String MODE_RAINBOW = "rainbow";
    public static final String MODE_DUO = "duo";

    public static final String DEFAULT_COLOR = "FF2020";
    public static final int DEFAULT_BRIGHTNESS = 60;
    public static final int DEFAULT_SPEED = 5;

    public static final int TARGET_BOTH = 0;
    public static final int TARGET_LEFT = 1;
    public static final int TARGET_RIGHT = 2;

    public static final String[] MODES = {
            MODE_OFF, MODE_STATIC, MODE_BREATHING, MODE_RAINBOW, MODE_DUO
    };

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

    public static void setMode(String mode) {
        Props.set(MODE, mode);
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

    public static boolean screenOffOff() {
        return !"keep".equals(Props.get(SCREEN_OFF, "off"));
    }

    public static void setScreenOffOff(boolean off) {
        Props.set(SCREEN_OFF, off ? "off" : "keep");
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
            return 0xFFFF2020;
        }
    }

    public static String argbToColor(int argb) {
        return String.format(java.util.Locale.US, "%06X", argb & 0xFFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
