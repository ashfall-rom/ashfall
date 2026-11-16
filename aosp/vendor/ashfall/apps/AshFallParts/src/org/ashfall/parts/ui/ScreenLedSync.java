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
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.ashfall.parts.hw.Led;
import org.ashfall.parts.ipc.Props;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Screen colour sampler for the "Match screen" effect (#167).
 *
 * <p>Mirrors the default display into a 64x36 {@link ImageReader} with
 * {@link DisplayManager#createVirtualDisplay}, averages each half in linear
 * light, boosts the result and publishes it as
 * {@code ashfall.ui.led_override=RRGGBB:RRGGBB} through the same contract the
 * gamepad tester uses ({@code docs/phase5.md} §6.1).  A heartbeat on
 * {@code ashfall.ui.led_override_live} runs at 400 ms so {@code ashfall-hwd}
 * can drop the override if this process dies (#94).
 *
 * <p>This class only captures and publishes.  {@link org.ashfall.parts.LedSyncService}
 * owns the lifecycle (foreground service, screen off, tester suspend, failure
 * handling) and always calls {@link #stop()}, which clears both properties.
 */
public final class ScreenLedSync {

    private static final String TAG = "AshFallParts";
    private static final String OVERRIDE = "ashfall.ui.led_override";
    private static final String OVERRIDE_LIVE = "ashfall.ui.led_override_live";

    /** 64x36 keeps the readback cheap and still gives each half 32x36 pixels. */
    private static final int CAPTURE_WIDTH = 64;
    private static final int CAPTURE_HEIGHT = 36;

    /**
     * The daemon applies overrides at up to 29 Hz (docs/phase5.md §6.1).
     * Publishing is paced with a millisecond budget instead of a fixed gate so
     * a 30 Hz capture still publishes ~29 times a second instead of every
     * second frame (issue #194).
     */
    private static final double MIN_PUBLISH_MS = 1000.0 / 29.0;
    private static final double MAX_PUBLISH_BUDGET_MS = 2.0 * MIN_PUBLISH_MS;
    /** Contract (#94): the daemon ignores an override without a fresh heartbeat. */
    private static final long HEARTBEAT_MS = 400;

    /** Asymmetric smoothing: scene cuts fade in quickly, out slowly. */
    private static final float ATTACK_MS = 120f;
    private static final float RELEASE_MS = 350f;
    /** A frame whose brightest channel is below this (sRGB 0..1) is "black". */
    private static final float BLACK_LEVEL = 0.05f;
    /** No first frame within this time means the capture is not working. */
    private static final long START_TIMEOUT_MS = 2500;
    /** Rings wash out at low brightness, so lift chroma a little. */
    private static final float CHROMA_GAIN = 1.30f;
    /** Mild value lift after the chroma boost. */
    private static final float VALUE_GAMMA = 0.92f;

    /** Reports a capture failure so the service can fail safe. */
    public interface Listener {
        void onCaptureFailed(String reason);
    }

    private final Context mContext;
    private final Listener mListener;
    private final Object mLock = new Object();
    /**
     * Serialises the whole per-frame lifecycle (acquire, copy, image close)
     * against {@link ImageReader#close()} (#204).  {@link #mReaderClosed} is
     * true whenever no reader may be acquired.
     */
    private final Object mReaderLock = new Object();
    private volatile boolean mReaderClosed = true;

    private DisplayManager mDisplayManager;
    private VirtualDisplay mVirtualDisplay;
    private volatile ImageReader mReader;
    private HandlerThread mThread;
    private Handler mHandler;

    private volatile boolean mRunning;
    private long mLastFrameAt;
    private double mPublishBudgetMs;

    /** Reusable copy of the latest frame, filled under {@link #mReaderLock}. */
    private byte[] mPlane = new byte[0];
    /** Set when another publisher owns the override; we then never write. */
    private boolean mYielded;

    private final float[] mLeft = new float[3];
    private final float[] mRight = new float[3];
    /** Each ring holds its own last colour; one black half never leaks (#196). */
    private volatile boolean mLeftValid;
    private volatile boolean mRightValid;
    private volatile boolean mGotFrame;
    private volatile boolean mHasColour;
    private long mStartedAt;
    private boolean mFailureReported;
    private String mLastValue = "";
    private boolean mHeartbeatPosted;

    public ScreenLedSync(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    /**
     * Starts mirroring.  Returns false when the display service or the virtual
     * display could not be created; the caller must fail safe in that case.
     *
     * <p>Idempotent: calling it while running keeps the current session.
     */
    public boolean start() {
        if (mRunning) {
            return true;
        }
        mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        if (mDisplayManager == null) {
            Log.w(TAG, "screen sync: no display service");
            return false;
        }
        try {
            mThread = new HandlerThread("ashfall-led-sync", Process.THREAD_PRIORITY_DISPLAY);
            mThread.start();
            mHandler = new Handler(mThread.getLooper());

            final ImageReader reader = ImageReader.newInstance(CAPTURE_WIDTH,
                    CAPTURE_HEIGHT, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onImageAvailable, mHandler);
            synchronized (mReaderLock) {
                mReaderClosed = false;
                mReader = reader;
            }

            final int density = mContext.getResources().getDisplayMetrics().densityDpi;
            mVirtualDisplay = mDisplayManager.createVirtualDisplay("ashfall-led-sync",
                    CAPTURE_WIDTH, CAPTURE_HEIGHT, density, reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
            if (mVirtualDisplay == null) {
                throw new IllegalStateException("createVirtualDisplay returned null");
            }
        } catch (Throwable t) {
            Log.w(TAG, "screen sync: capture did not start", t);
            release();
            return false;
        }

        mLastFrameAt = 0;
        mPublishBudgetMs = 0;
        mGotFrame = false;
        mYielded = false;
        mLeftValid = false;
        mRightValid = false;
        mHasColour = false;
        mLeft[0] = mLeft[1] = mLeft[2] = 0f;
        mRight[0] = mRight[1] = mRight[2] = 0f;
        mLastValue = "";
        mStartedAt = SystemClock.elapsedRealtime();
        mFailureReported = false;
        mRunning = true;
        postHeartbeat();
        Log.i(TAG, "screen sync: started at " + Led.screenRate() + " Hz");
        return true;
    }

    /**
     * Stops mirroring and clears the override and the heartbeat.  Safe to call
     * repeatedly and from any thread; the properties are always cleared.
     */
    public void stop() {
        final boolean wasRunning = mRunning;
        mRunning = false;
        mStartedAt = 0;
        // Serialised with publish() so a frame that was already in flight can
        // never re-latch the rings after this clear.
        synchronized (mLock) {
            clearOwnOverrideLocked();
            mLeftValid = false;
            mRightValid = false;
            mHasColour = false;
        }
        release();
        if (wasRunning) {
            Log.i(TAG, "screen sync: stopped");
        }
    }

    public boolean isRunning() {
        return mRunning;
    }

    private void release() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        // Stop the producer before closing the queue.
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.release();
            } catch (Throwable ignored) {
                // Already released.
            }
            mVirtualDisplay = null;
        }
        // Teardown shares mReaderLock with the frame handler: mReader is
        // nulled and closed while no callback can be copying a plane, and a
        // callback that was waiting on the lock sees mReader == null and
        // returns without acquiring (#204).
        synchronized (mReaderLock) {
            mReaderClosed = true;
            final ImageReader reader = mReader;
            mReader = null;
            if (reader != null) {
                try {
                    reader.setOnImageAvailableListener(null, null);
                } catch (Throwable ignored) {
                    // Already closed.
                }
                try {
                    reader.close();
                } catch (Throwable ignored) {
                    // Already closed.
                }
            }
        }
        if (mThread != null) {
            mThread.quitSafely();
            mThread = null;
        }
        mHeartbeatPosted = false;
    }

    private void postHeartbeat() {
        final Handler handler = mHandler;
        if (handler == null || mHeartbeatPosted) {
            return;
        }
        mHeartbeatPosted = true;
        handler.postDelayed(mHeartbeat, HEARTBEAT_MS);
    }

    private final Runnable mHeartbeat = this::heartbeat;

    /** Keeps the override alive for the daemon's stale-override guardian (#94). */
    private void heartbeat() {
        if (!mRunning) {
            return;
        }
        synchronized (mLock) {
            if (mHasColour) {
                writeHeartbeatLocked();
            }
        }
        final Handler handler = mHandler;
        if (handler != null) {
            handler.postDelayed(mHeartbeat, HEARTBEAT_MS);
        }
        // Only "no frame at all" is a capture failure.  A stream of black
        // frames is a hold, not an error (#195).
        if (!mGotFrame && mStartedAt != 0
                && SystemClock.elapsedRealtime() - mStartedAt > START_TIMEOUT_MS) {
            reportFailure("no frame within " + START_TIMEOUT_MS + " ms");
        }
    }

    private void reportFailure(String reason) {
        if (mFailureReported) {
            return;
        }
        mFailureReported = true;
        Log.w(TAG, "screen sync: " + reason);
        final Listener listener = mListener;
        if (listener != null) {
            listener.onCaptureFailed(reason);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        if (!mRunning || mReaderClosed || reader != mReader) {
            // stop() closes the reader and a new session gets a new one; a
            // straggler callback must not fail the new session.
            return;
        }

        // The whole per-frame lifecycle happens under mReaderLock: acquire,
        // copy the plane bytes out, close the Image and (in release()) close
        // the reader.  Processing then runs on the copy, so ImageReader.close()
        // can never free a buffer that is still being read (#204).
        final byte[] frame;
        final int rowStride;
        final int pixelStride;
        final int width;
        final int height;
        try {
            synchronized (mReaderLock) {
                if (!mRunning || mReaderClosed || reader != mReader) {
                    return;
                }
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    // A delivered frame proves the capture works, whatever it
                    // shows (#195).
                    mGotFrame = true;
                    final Image.Plane plane = image.getPlanes()[0];
                    rowStride = plane.getRowStride();
                    pixelStride = plane.getPixelStride();
                    width = image.getWidth();
                    height = image.getHeight();
                    final int size = rowStride * height;
                    if (mPlane.length < size) {
                        mPlane = new byte[size];
                    }
                    final ByteBuffer buffer = plane.getBuffer();
                    if (buffer.remaining() < size) {
                        // Defensive: never read past the producer's buffer.
                        return;
                    }
                    buffer.get(mPlane, 0, size);
                    frame = mPlane;
                } finally {
                    if (image != null) {
                        // Outstanding images never outlive teardown.
                        image.close();
                    }
                }
            }
        } catch (Throwable t) {
            if (!mRunning || reader != mReader) {
                return;
            }
            reportFailure(String.valueOf(t));
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final long interval = 1000L / Math.max(1, Led.screenRate());
        if (now - mLastFrameAt < interval) {
            // Throttled: the virtual display mirrors at panel rate, the effect
            // runs at 10/15/30 Hz to keep the GPU readback cheap.
            return;
        }
        final long dt = mLastFrameAt == 0 ? interval : now - mLastFrameAt;
        mLastFrameAt = now;
        if (Led.suspendActive()) {
            // The gamepad tester owns the rings.  Stop publishing at once and
            // clear only our own colour, never a foreign override (#192); the
            // service also stops the sampler on its next tick.
            synchronized (mLock) {
                clearOwnOverrideLocked();
            }
            return;
        }
        process(frame, rowStride, pixelStride, width, height, dt);
    }

    private void process(byte[] buffer, int rowStride, int pixelStride, int width,
            int height, long dtMs) {
        final int half = width / 2;

        double leftR = 0, leftG = 0, leftB = 0;
        int leftN = 0;
        double rightR = 0, rightG = 0, rightB = 0;
        int rightN = 0;
        // Whole-frame dominant colour: bright/saturated pixels count more, so a
        // small vivid subject wins over a large grey background.
        double allR = 0, allG = 0, allB = 0, allW = 0;

        for (int y = 0; y < height; y++) {
            final int row = y * rowStride;
            for (int x = 0; x < width; x++) {
                final int index = row + x * pixelStride;
                final double r = srgbToLinear((buffer[index] & 0xFF) / 255.0);
                final double g = srgbToLinear((buffer[index + 1] & 0xFF) / 255.0);
                final double b = srgbToLinear((buffer[index + 2] & 0xFF) / 255.0);
                if (x < half) {
                    leftR += r;
                    leftG += g;
                    leftB += b;
                    leftN++;
                } else {
                    rightR += r;
                    rightG += g;
                    rightB += b;
                    rightN++;
                }
                final double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                final double weight = (luma + 0.02) * (luma + 0.02);
                allR += r * weight;
                allG += g * weight;
                allB += b * weight;
                allW += weight;
            }
        }

        if (leftN == 0 || rightN == 0 || allW <= 0) {
            return;
        }

        final float[] left = encode(leftR / leftN, leftG / leftN, leftB / leftN);
        final float[] right = encode(rightR / rightN, rightG / rightN, rightB / rightN);
        final float[] dominant = encode(allR / allW, allG / allW, allB / allW);

        // Target Both = whole-frame dominant colour for both rings; Left/Right
        // keep the split (each ring follows its own half).
        final boolean both = org.ashfall.parts.Prefs.ledTarget(mContext) == Led.TARGET_BOTH;
        final float[] targetLeft = both ? dominant : left;
        final float[] targetRight = both ? dominant : right;

        // A fully black frame (secure surface, DRM, panel off) holds both rings.
        if (targetLeft == null && targetRight == null) {
            return;
        }

        // Each ring is updated only from its own half.  A black half keeps
        // that ring's last valid colour; it is never filled from the other
        // half (#196).  Until a half has shown a usable colour at least once,
        // nothing is published, so the rings keep the configured effect.
        final float scale = smoothingScale(Led.speed());
        if (targetLeft != null) {
            if (!mLeftValid) {
                copy(targetLeft, mLeft);
                mLeftValid = true;
            } else {
                smooth(targetLeft, mLeft, dtMs, scale);
            }
        }
        if (targetRight != null) {
            if (!mRightValid) {
                copy(targetRight, mRight);
                mRightValid = true;
            } else {
                smooth(targetRight, mRight, dtMs, scale);
            }
        }

        if (!mLeftValid || !mRightValid) {
            return;
        }
        mHasColour = true;
        publish(dtMs);
    }

    /** sRGB 0..1 including the ring boost; a black frame never reaches here. */
    private static float[] encode(double r, double g, double b) {
        final float[] out = {
                linearToSrgb(r), linearToSrgb(g), linearToSrgb(b)
        };
        final float peak = Math.max(out[0], Math.max(out[1], out[2]));
        if (peak < BLACK_LEVEL) {
            // Secure surfaces, DRM video and a blank panel read as black: hold
            // the last colour rather than latching black (or a red tint).
            return null;
        }
        return boost(out);
    }

    private static float[] boost(float[] colour) {
        final float luma = 0.2126f * colour[0] + 0.7152f * colour[1]
                + 0.0722f * colour[2];
        for (int i = 0; i < 3; i++) {
            float value = luma + (colour[i] - luma) * CHROMA_GAIN;
            value = clamp01(value);
            colour[i] = (float) Math.pow(value, VALUE_GAMMA);
        }
        return colour;
    }

    private static void smooth(float[] target, float[] current, long dtMs, float scale) {
        if (target == null) {
            return;
        }
        final float dt = Math.max(1f, Math.min(250f, dtMs));
        for (int i = 0; i < 3; i++) {
            final float tau = (target[i] > current[i] ? ATTACK_MS : RELEASE_MS) * scale;
            final float alpha = 1f - (float) Math.exp(-dt / tau);
            current[i] += (target[i] - current[i]) * alpha;
        }
    }

    /**
     * Speed 1..10: higher is snappier.  Speed 5 (the default) keeps the
     * documented 120 ms / 350 ms.
     */
    private static float smoothingScale(int speed) {
        return Math.max(0.25f, 1f + (5 - speed) * 0.12f);
    }

    private void publish(long dtMs) {
        final int brightness = Led.brightness();
        final int rLeft = scale(mLeft[0], brightness);
        final int gLeft = scale(mLeft[1], brightness);
        final int bLeft = scale(mLeft[2], brightness);
        final int rRight = scale(mRight[0], brightness);
        final int gRight = scale(mRight[1], brightness);
        final int bRight = scale(mRight[2], brightness);
        final String value = String.format(Locale.US, "%02X%02X%02X:%02X%02X%02X",
                rLeft, gLeft, bLeft, rRight, gRight, bRight);

        // Last check before every write: the tester may have taken the rings
        // over since the frame was captured (#192).  Read it before taking the
        // publish lock (Led keeps its own lock) and act on it inside.
        final boolean suspended = Led.suspendActive();
        synchronized (mLock) {
            if (!mRunning || !mHasColour) {
                return;
            }
            if (suspended) {
                clearOwnOverrideLocked();
                return;
            }
            // A foreign publisher (the gamepad tester) may own the override.
            // Never overwrite or clear it: stand down until it clears the
            // property, then take over again (#192 follow-up).
            final String current = Props.get(OVERRIDE, "");
            if (mYielded) {
                if (!current.isEmpty()) {
                    return;
                }
                mYielded = false;
            } else if (!mLastValue.isEmpty() && !current.isEmpty()
                    && !current.equals(mLastValue)) {
                mYielded = true;
                mLastValue = "";
                return;
            }
            // Publish pacing: a time budget, not a fixed gate, so the 30 Hz
            // setting writes ~29 times a second (one skipped frame in ~30)
            // rather than every second frame (issue #194).
            mPublishBudgetMs = Math.min(MAX_PUBLISH_BUDGET_MS,
                    mPublishBudgetMs + Math.max(0, dtMs));
            // Only write when the final colours (brightness included) change;
            // the daemon applies every override to both rings.
            if (value.equals(mLastValue)) {
                writeHeartbeatLocked();
                return;
            }
            if (mPublishBudgetMs < MIN_PUBLISH_MS) {
                return;
            }
            mPublishBudgetMs -= MIN_PUBLISH_MS;
            mLastValue = value;
            Props.set(OVERRIDE, value);
            writeHeartbeatLocked();
        }
    }

    /**
     * The heartbeat is a changing token, not a constant: the daemon counts a
     * beat only when the value changes (#171), because writing an identical
     * value need not bump the property serial.  The value is opaque to hwd.
     */
    private void writeHeartbeatLocked() {
        Props.set(OVERRIDE_LIVE, Long.toString(SystemClock.elapsedRealtime()));
    }

    /**
     * Clears the override and heartbeat <b>only while they are still ours</b>.
     * The gamepad tester shares both properties; when it has taken the rings
     * over, its colour and token are left untouched (#192).
     */
    private void clearOwnOverrideLocked() {
        if (mLastValue.isEmpty()) {
            // Nothing of ours is published; leave foreign values and the
            // heartbeat completely alone.
            return;
        }
        final String current = Props.get(OVERRIDE, "");
        if (current.isEmpty() || current.equals(mLastValue)) {
            Props.set(OVERRIDE, "");
            Props.set(OVERRIDE_LIVE, "");
        }
        mLastValue = "";
    }

    private static int scale(float value, int brightness) {
        return Math.round(clamp01(value) * 255f * (brightness / 100f));
    }

    private static void copy(float[] from, float[] to) {
        System.arraycopy(from, 0, to, 0, 3);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static double srgbToLinear(double value) {
        final double v = Math.max(0.0, Math.min(1.0, value));
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static float linearToSrgb(double value) {
        final double v = Math.max(0.0, Math.min(1.0, value));
        final double encoded = v <= 0.0031308 ? v * 12.92
                : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
        // The readback is already RGBA_8888 sRGB, so HDR/wide-gamut content has
        // been tone-mapped by the compositor before it reaches this buffer;
        // clamping keeps any remaining out-of-range value in SDR.
        return (float) Math.max(0.0, Math.min(1.0, encoded));
    }
}
