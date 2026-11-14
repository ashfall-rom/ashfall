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

import android.animation.ValueAnimator;
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
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen appearance layer: solid color, animated/static gradient, or a
 * center-cropped image with a dim scrim.
 */
public final class LauncherBackgroundView extends View {
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ashfall-background");
        thread.setDaemon(true);
        return thread;
    });

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final float[] hsv = new float[3];

    private int backgroundType = Appearance.BACKGROUND_SOLID;
    private int solidColor = Appearance.DEFAULT_SOLID;
    private int gradientStart = Appearance.DEFAULT_GRADIENT_START;
    private int gradientEnd = Appearance.DEFAULT_GRADIENT_END;
    private int gradientDirection = Appearance.DIRECTION_VERTICAL;
    private boolean gradientAnimate;
    private int imageDim = 40;

    private String imageUri;
    private String loadedUri;
    private Bitmap bitmap;

    private ValueAnimator animator;
    private float phase;

    public LauncherBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
    }

    void apply(Appearance appearance) {
        backgroundType = appearance.backgroundType;
        solidColor = appearance.solidColor;
        gradientStart = appearance.gradientStart;
        gradientEnd = appearance.gradientEnd;
        gradientDirection = appearance.gradientDirection;
        gradientAnimate = appearance.gradientAnimate;
        imageDim = appearance.imageDim;

        imageUri = appearance.imageUri;
        if (backgroundType == Appearance.BACKGROUND_IMAGE && imageUri != null) {
            loadImage(imageUri);
        } else if (backgroundType == Appearance.BACKGROUND_IMAGE) {
            recycleBitmap();
            loadedUri = null;
        } else {
            recycleBitmap();
            loadedUri = null;
        }

        updateAnimator();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (backgroundType == Appearance.BACKGROUND_IMAGE && bitmap == null
                && imageUri != null) {
            loadImage(imageUri);
        }
        updateAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setShader(null);
        paint.setAlpha(255);

        switch (backgroundType) {
            case Appearance.BACKGROUND_GRADIENT:
                drawGradient(canvas);
                break;
            case Appearance.BACKGROUND_IMAGE:
                if (bitmap != null && !bitmap.isRecycled()) {
                    drawImage(canvas);
                    if (imageDim > 0) {
                        canvas.drawColor(Color.argb(Math.round(imageDim * 2.55f), 0, 0, 0));
                    }
                } else {
                    canvas.drawColor(Color.BLACK);
                }
                break;
            case Appearance.BACKGROUND_SOLID:
            default:
                canvas.drawColor(solidColor);
                break;
        }
    }

    private void drawGradient(Canvas canvas) {
        int start = gradientStart;
        int end = gradientEnd;
        if (gradientAnimate) {
            start = shiftHue(start, phase);
            end = shiftHue(end, phase);
        }
        float x0 = 0f;
        float y0 = 0f;
        float x1 = gradientDirection == Appearance.DIRECTION_HORIZONTAL ? getWidth() : 0f;
        float y1 = gradientDirection == Appearance.DIRECTION_HORIZONTAL ? 0f : getHeight();
        paint.setShader(new LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
    }

    private void drawImage(Canvas canvas) {
        float scale = Math.max(getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight());
        float dx = (getWidth() - bitmap.getWidth() * scale) / 2f;
        float dy = (getHeight() - bitmap.getHeight() * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        canvas.drawBitmap(bitmap, matrix, paint);
    }

    private int shiftHue(int color, float amount) {
        Color.colorToHSV(color, hsv);
        if (hsv[1] <= 0.01f) {
            return color;
        }
        hsv[0] = (hsv[0] + amount * 360f) % 360f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private void loadImage(String uriString) {
        if (uriString.equals(loadedUri)) {
            return;
        }
        final String requestedUri = uriString;
        LOADER.execute(() -> {
            Bitmap decoded = decode(requestedUri);
            post(() -> {
                if (!requestedUri.equals(imageUri)) {
                    if (decoded != null) {
                        decoded.recycle();
                    }
                    return;
                }
                recycleBitmap();
                bitmap = decoded;
                loadedUri = decoded == null ? null : requestedUri;
                invalidate();
            });
        });
    }

    private Bitmap decode(String uriString) {
        ContentResolver resolver = getContext().getApplicationContext().getContentResolver();
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
            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > 2048) {
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

    private void recycleBitmap() {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        bitmap = null;
    }

    private void updateAnimator() {
        boolean shouldAnimate = backgroundType == Appearance.BACKGROUND_GRADIENT
                && gradientAnimate && isAttachedToWindow();
        if (shouldAnimate && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(24_000L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else if (!shouldAnimate) {
            stopAnimator();
        }
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        phase = 0f;
        invalidate();
    }
}
