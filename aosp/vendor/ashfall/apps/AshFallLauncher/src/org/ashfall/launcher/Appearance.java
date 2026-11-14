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

package org.ashfall.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/** Persisted look-and-feel of the launcher. */
final class Appearance {
    static final int BACKGROUND_SOLID = 0;
    static final int BACKGROUND_GRADIENT = 1;
    static final int BACKGROUND_IMAGE = 2;

    static final int DIRECTION_VERTICAL = 0;
    static final int DIRECTION_HORIZONTAL = 1;

    static final int DEFAULT_ACCENT = 0xFFF24646;
    static final int DEFAULT_SOLID = 0xFF000000;
    static final int DEFAULT_GRADIENT_START = 0xFF000000;
    static final int DEFAULT_GRADIENT_END = 0xFF3A0A0A;

    static final int[] PALETTE = {
            0xFF000000, 0xFF0A0A0A, 0xFF1C1C1E, 0xFF0B1220,
            0xFF120B1E, 0xFF062018, 0xFF201A00, 0xFF2A0A0A,
            0xFFF24646, 0xFF4C8DFF, 0xFF8B5CF6, 0xFF22C55E,
            0xFFF59E0B, 0xFFE5E7EB,
    };

    static final int[] ACCENTS = {
            0xFFF24646, // ember red (default)
            0xFFFF7A1A, // orange
            0xFFF5C542, // amber
            0xFF22C55E, // green
            0xFF14B8A6, // teal
            0xFF4C8DFF, // blue
            0xFF8B5CF6, // violet
            0xFFEC4899, // pink
            0xFFE5E7EB, // white
    };

    private static final String PREFS = "appearance";
    private static final String KEY_BACKGROUND_TYPE = "background_type";
    private static final String KEY_SOLID_COLOR = "solid_color";
    private static final String KEY_GRADIENT_START = "gradient_start";
    private static final String KEY_GRADIENT_END = "gradient_end";
    private static final String KEY_GRADIENT_DIRECTION = "gradient_direction";
    private static final String KEY_GRADIENT_ANIMATE = "gradient_animate";
    private static final String KEY_IMAGE_URI = "image_uri";
    private static final String KEY_IMAGE_DIM = "image_dim";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_WALLPAPER_APPLIED = "wallpaper_applied";

    final int backgroundType;
    final int solidColor;
    final int gradientStart;
    final int gradientEnd;
    final int gradientDirection;
    final boolean gradientAnimate;
    final String imageUri;
    final int imageDim;
    final int accent;

    private Appearance(int backgroundType, int solidColor, int gradientStart, int gradientEnd,
            int gradientDirection, boolean gradientAnimate, String imageUri, int imageDim,
            int accent) {
        this.backgroundType = backgroundType;
        this.solidColor = solidColor;
        this.gradientStart = gradientStart;
        this.gradientEnd = gradientEnd;
        this.gradientDirection = gradientDirection;
        this.gradientAnimate = gradientAnimate;
        this.imageUri = imageUri;
        this.imageDim = imageDim;
        this.accent = accent;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Appearance load(Context context) {
        SharedPreferences prefs = preferences(context);
        return new Appearance(
                prefs.getInt(KEY_BACKGROUND_TYPE, BACKGROUND_SOLID),
                prefs.getInt(KEY_SOLID_COLOR, DEFAULT_SOLID),
                prefs.getInt(KEY_GRADIENT_START, DEFAULT_GRADIENT_START),
                prefs.getInt(KEY_GRADIENT_END, DEFAULT_GRADIENT_END),
                prefs.getInt(KEY_GRADIENT_DIRECTION, DIRECTION_VERTICAL),
                prefs.getBoolean(KEY_GRADIENT_ANIMATE, false),
                prefs.getString(KEY_IMAGE_URI, null),
                clamp(prefs.getInt(KEY_IMAGE_DIM, 40), 0, 80),
                prefs.getInt(KEY_ACCENT, DEFAULT_ACCENT));
    }

    static void setBackgroundType(Context context, int type) {
        preferences(context).edit().putInt(KEY_BACKGROUND_TYPE, type).apply();
    }

    static void setSolidColor(Context context, int color) {
        preferences(context).edit().putInt(KEY_SOLID_COLOR, color).apply();
    }

    static void setGradientStart(Context context, int color) {
        preferences(context).edit().putInt(KEY_GRADIENT_START, color).apply();
    }

    static void setGradientEnd(Context context, int color) {
        preferences(context).edit().putInt(KEY_GRADIENT_END, color).apply();
    }

    static void setGradientDirection(Context context, int direction) {
        preferences(context).edit().putInt(KEY_GRADIENT_DIRECTION, direction).apply();
    }

    static void setGradientAnimate(Context context, boolean animate) {
        preferences(context).edit().putBoolean(KEY_GRADIENT_ANIMATE, animate).apply();
    }

    static void setImageUri(Context context, String uri) {
        preferences(context).edit().putString(KEY_IMAGE_URI, uri).apply();
    }

    static void setImageDim(Context context, int dim) {
        preferences(context).edit().putInt(KEY_IMAGE_DIM, clamp(dim, 0, 80)).apply();
    }

    static void setAccent(Context context, int accent) {
        preferences(context).edit().putInt(KEY_ACCENT, accent).apply();
    }

    /**
     * Identifies the appearance currently installed as the system wallpaper, so
     * the launcher only rewrites it when something changed.
     */
    String wallpaperSignature() {
        if (backgroundType == BACKGROUND_GRADIENT && gradientAnimate) {
            // The live wallpaper reads the colours itself; only a change here
            // needs a redraw (the accent and the image settings are irrelevant).
            return "live:" + Integer.toHexString(gradientStart) + ":"
                    + Integer.toHexString(gradientEnd) + ":" + gradientDirection;
        }
        return "static:" + backgroundType + ":"
                + Integer.toHexString(solidColor) + ":"
                + Integer.toHexString(gradientStart) + ":"
                + Integer.toHexString(gradientEnd) + ":"
                + gradientDirection + ":"
                + (imageUri == null ? "" : imageUri) + ":" + imageDim;
    }

    static String getAppliedWallpaper(Context context) {
        return preferences(context).getString(KEY_WALLPAPER_APPLIED, null);
    }

    static void setAppliedWallpaper(Context context, String signature) {
        preferences(context).edit().putString(KEY_WALLPAPER_APPLIED, signature).apply();
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    static String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
