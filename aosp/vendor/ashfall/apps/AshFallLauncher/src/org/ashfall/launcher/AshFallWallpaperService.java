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

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/**
 * Live wallpaper for the animated gradient background.
 *
 * <p>The two gradient colours are read from {@link Appearance}; the hue is
 * rotated once per {@link #CYCLE_MS} so the phase is independent of the frame
 * rate. Drawing stops as soon as the wallpaper is not visible (another window
 * on top, screen off, surface destroyed), which is the battery-friendly part:
 * no timer or animation runs in the background. 20 fps is smooth for a slow
 * hue shift and much cheaper than a Choreographer-driven 60 fps repaint.
 */
public final class AshFallWallpaperService extends WallpaperService {
    private static final long CYCLE_MS = 24_000L;
    private static final long FRAME_MS = 50L;

    @Override
    public Engine onCreateEngine() {
        return new GradientEngine();
    }

    private final class GradientEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable frame = this::drawFrame;
        private final float[] hsv = new float[3];

        private boolean visible;
        private boolean animate;
        private boolean horizontal;
        private int gradientStart = Appearance.DEFAULT_GRADIENT_START;
        private int gradientEnd = Appearance.DEFAULT_GRADIENT_END;
        private long phaseStart;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setOffsetNotificationsEnabled(false);
            phaseStart = SystemClock.uptimeMillis();
            Appearance.preferences(AshFallWallpaperService.this)
                    .registerOnSharedPreferenceChangeListener(this);
            loadAppearance();
        }

        @Override
        public void onDestroy() {
            Appearance.preferences(AshFallWallpaperService.this)
                    .unregisterOnSharedPreferenceChangeListener(this);
            handler.removeCallbacks(frame);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            if (visible) {
                loadAppearance();
                drawFrame();
            } else {
                handler.removeCallbacks(frame);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            handler.removeCallbacks(frame);
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            loadAppearance();
            drawFrame();
        }

        private void loadAppearance() {
            Appearance appearance = Appearance.load(AshFallWallpaperService.this);
            gradientStart = appearance.gradientStart;
            gradientEnd = appearance.gradientEnd;
            horizontal = appearance.gradientDirection == Appearance.DIRECTION_HORIZONTAL;
            animate = appearance.gradientAnimate;
        }

        private void drawFrame() {
            if (!visible) {
                return;
            }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas == null) {
                    return;
                }
                float phase = animate
                        ? ((SystemClock.uptimeMillis() - phaseStart) % CYCLE_MS)
                                / (float) CYCLE_MS
                        : 0f;
                int start = animate
                        ? BackgroundArt.shiftedHue(gradientStart, phase, hsv) : gradientStart;
                int end = animate
                        ? BackgroundArt.shiftedHue(gradientEnd, phase, hsv) : gradientEnd;
                BackgroundArt.drawGradient(canvas, canvas.getWidth(), canvas.getHeight(),
                        start, end, horizontal);
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
            if (animate) {
                scheduleNextFrame();
            } else {
                handler.removeCallbacks(frame);
            }
        }

        private void scheduleNextFrame() {
            handler.removeCallbacks(frame);
            if (visible) {
                handler.postDelayed(frame, FRAME_MS);
            }
        }
    }
}
