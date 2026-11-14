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

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installs the launcher appearance as the system wallpaper.
 *
 * <p>Solid colours, static gradients and images become a static wallpaper
 * through {@link WallpaperManager#setBitmap}; the animated gradient is served
 * by {@link AshFallWallpaperService}, a live wallpaper inside this app. Keeping
 * the background in the wallpaper layer (instead of drawing it inside the HOME
 * window) is what makes Recents, the lockscreen and the app-to-home transition
 * show the same surface.
 */
final class WallpaperController {
    private static final String TAG = "AshFallWallpaper";

    private static final ExecutorService RENDERER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ashfall-wallpaper");
        thread.setDaemon(true);
        return thread;
    });

    /** Signature of the bitmap render currently queued or running, if any. */
    private static String pendingSignature;

    private WallpaperController() {
    }

    /**
     * Brings the system wallpaper in line with the stored appearance. Cheap
     * when nothing changed: only the signature comparison and a binder call.
     */
    static void apply(Context context) {
        final Context app = context.getApplicationContext();
        final Appearance appearance = Appearance.load(app);
        final String signature = appearance.wallpaperSignature();
        WallpaperManager manager = WallpaperManager.getInstance(app);
        if (manager == null) {
            return;
        }

        boolean live = appearance.backgroundType == Appearance.BACKGROUND_GRADIENT
                && appearance.gradientAnimate;
        ComponentName service = new ComponentName(app, AshFallWallpaperService.class);
        WallpaperInfo info = manager.getWallpaperInfo();
        boolean ours = info != null && service.equals(info.getComponent());

        if (live) {
            if (!ours) {
                try {
                    manager.setWallpaperComponent(service);
                } catch (RuntimeException e) {
                    Log.w(TAG, "cannot install the live wallpaper", e);
                    return;
                }
            }
            Appearance.setAppliedWallpaper(app, signature);
            return;
        }

        // A foreign or already matching static wallpaper is left alone; only our
        // live wallpaper always has to be replaced by the bitmap.
        if (!ours && signature.equals(Appearance.getAppliedWallpaper(app))) {
            return;
        }
        synchronized (WallpaperController.class) {
            if (signature.equals(pendingSignature)) {
                return;
            }
            pendingSignature = signature;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) app.getSystemService(
                Context.WINDOW_SERVICE);
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.setTo(app.getResources().getDisplayMetrics());
        }
        final int width = Math.max(1, metrics.widthPixels);
        final int height = Math.max(1, metrics.heightPixels);

        RENDERER.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = BackgroundArt.renderStatic(app, appearance, width, height);
                // The appearance may have changed (or switched to the live
                // wallpaper) while the bitmap was rendering; never let a stale
                // render win that race.
                if (!signature.equals(Appearance.load(app).wallpaperSignature())) {
                    return;
                }
                // The 3-argument overload sets FLAG_SYSTEM | FLAG_LOCK.
                manager.setBitmap(bitmap, null, false);
                Appearance.setAppliedWallpaper(app, signature);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "cannot set the system wallpaper", e);
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                synchronized (WallpaperController.class) {
                    if (signature.equals(pendingSignature)) {
                        pendingSignature = null;
                    }
                }
            }
        });
    }
}
