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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;

import java.io.InputStream;

/**
 * Background painting shared by the live wallpaper and the in-app preview.
 * The HOME activity itself does not draw the background: the appearance is
 * installed as the system wallpaper instead (docs/launcher.md).
 */
final class BackgroundArt {
    private BackgroundArt() {
    }

    /** Rotates the hue of a colour by {@code phase} of a full turn. */
    static int shiftedHue(int color, float phase, float[] hsv) {
        Color.colorToHSV(color, hsv);
        if (hsv[1] <= 0.01f) {
            return color;
        }
        hsv[0] = (hsv[0] + phase * 360f) % 360f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    /** Renders the static appearance (solid, gradient or image) into a bitmap. */
    static Bitmap renderStatic(Context context, Appearance appearance, int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        switch (appearance.backgroundType) {
            case Appearance.BACKGROUND_GRADIENT:
                drawGradient(canvas, w, h, appearance.gradientStart, appearance.gradientEnd,
                        appearance.gradientDirection == Appearance.DIRECTION_HORIZONTAL);
                break;
            case Appearance.BACKGROUND_IMAGE:
                Bitmap image = decode(context, appearance.imageUri, Math.max(w, h));
                if (image == null) {
                    canvas.drawColor(Color.BLACK);
                } else {
                    drawCenterCrop(canvas, image, w, h);
                    image.recycle();
                    if (appearance.imageDim > 0) {
                        canvas.drawColor(Color.argb(
                                Math.round(appearance.imageDim * 2.55f), 0, 0, 0));
                    }
                }
                break;
            case Appearance.BACKGROUND_SOLID:
            default:
                canvas.drawColor(appearance.solidColor);
                break;
        }
        return bitmap;
    }

    static void drawGradient(Canvas canvas, int width, int height, int start, int end,
            boolean horizontal) {
        float x1 = horizontal ? width : 0f;
        float y1 = horizontal ? 0f : height;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0f, 0f, x1, y1, start, end, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, height, paint);
    }

    static void drawCenterCrop(Canvas canvas, Bitmap bitmap, int width, int height) {
        float scale = Math.max(width / (float) bitmap.getWidth(),
                height / (float) bitmap.getHeight());
        float dx = (width - bitmap.getWidth() * scale) / 2f;
        float dy = (height - bitmap.getHeight() * scale) / 2f;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        canvas.drawBitmap(bitmap, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    /**
     * Decodes a document URI no larger than {@code maxSize} on its long edge.
     * Returns {@code null} for an absent, revoked or unreadable image.
     */
    static Bitmap decode(Context context, String uriString, int maxSize) {
        if (uriString == null) {
            return null;
        }
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        Uri uri = Uri.parse(uriString);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = resolver.openInputStream(uri)) {
                if (stream == null) {
                    return null;
                }
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int limit = Math.max(1, maxSize);
            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > limit) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream stream = resolver.openInputStream(uri)) {
                return stream == null ? null : BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
