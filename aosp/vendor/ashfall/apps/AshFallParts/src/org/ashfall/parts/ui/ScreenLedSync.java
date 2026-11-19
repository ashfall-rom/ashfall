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
 * {@link DisplayManager#createVirtualDisplay}, averages the left and right half
 * independently in linear light and publishes those exact colours as
 * {@code ashfall.ui.led_override=RRGGBB:RRGGBB} through the same contract the
 * gamepad tester uses ({@code docs/phase5.md} §6.1).  The left half always
 * drives the left ring and the right half the right ring (#167 follow-up).  An
 * optional delay holds a new average until it has been stable for the selected
 * time; Off publishes every frame.  An optional "Colour boost" multiplies HSV
 * saturation of the published average (white and fully saturated colours are
 * unchanged) before the brightness scale.  A heartbeat on
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
    /**
     * Match screen "Delay changes", milliseconds.  Lives here rather than in
     * {@code hw/Led} because it tunes the sampler only; the daemon never reads
     * it.  {@code 0} (default) publishes every frame.
     */
    public static final String SCREEN_DELAY = "persist.ashfall.led.screen_delay";
    public static final int DEFAULT_SCREEN_DELAY_MS = 0;
    public static final int[] SCREEN_DELAYS_MS = {0, 100, 250, 500};
    /**
     * Match screen "Colour boost", percent added to HSV saturation:
     * {@code 0} (Off) keeps the literal average, 30/60/100 give a factor of
     * 1.6/2.2/3.0 (factor = 1 + pct/50).  Sampler-only, read live on every
     * publish; the daemon never reads it.
     */
    public static final String SCREEN_BOOST = "persist.ashfall.led.screen_boost";
    public static final int DEFAULT_SCREEN_BOOST_PCT = 60;
    public static final int[] SCREEN_BOOSTS_PCT = {0, 30, 60, 100};

    /**
     * Match screen "Dim dark scenes": scale the ring by the frame's brightness
     * (HSV value) so a near-black frame goes near-off instead of glowing as a
     * bright grey.  On by default; sampler-only, read live on every publish.
     */
    public static final String SCREEN_DIM = "persist.ashfall.led.screen_dim";
    public static final boolean DEFAULT_SCREEN_DIM = true;

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
    /**
     * The mirror is damage-driven: a static screen can stop delivering frames,
     * which would stall both the delay gate and a brightness-only change.  This
     * tick advances the gate and re-applies the current colour with the current
     * brightness even when no frame arrives (#241).
     */
    private static final long TICK_MS = 100;

    /** A frame whose brightest channel is below this (sRGB 0..1) is "black". */
    private static final float BLACK_LEVEL = 0.05f;
    /** No first frame within this time means the capture is not working. */
    private static final long START_TIMEOUT_MS = 2500;

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
    /**
     * Screen off: capture is released but the last colour and its heartbeat are
     * kept for {@link #PAUSE_HEARTBEAT_MS}, so hwd turns the rings off with the
     * last screen colour instead of the configured one, and the colour is still
     * fresh when the panel comes back.
     */
    private volatile boolean mPaused;
    private static final long PAUSE_HEARTBEAT_MS = 2000;
    private long mHeartbeatStopAt;
    private long mLastFrameAt;
    private double mPublishBudgetMs;

    /** Reusable copy of the latest frame, filled under {@link #mReaderLock}. */
    private byte[] mPlane = new byte[0];
    /** Set when another publisher owns the override; we then never write. */
    private boolean mYielded;

    private final float[] mLeft = new float[3];
    private final float[] mRight = new float[3];
    /** Colour-boost scratch, filled in publish() before brightness scaling. */
    private final float[] mBoostedLeft = new float[3];
    private final float[] mBoostedRight = new float[3];
    /**
     * Both rings hold a published colour.  A near-black half keeps that ring's
     * own last colour; it is never filled from the other half (#196).
     */
    private volatile boolean mHasColour;
    private volatile boolean mGotFrame;
    private long mStartedAt;
    private boolean mFailureReported;
    private String mLastValue = "";
    private boolean mHeartbeatPosted;
    /** Delay gate: the pair waiting to become stable, and since when. */
    private long mPendingKey;
    private boolean mPendingValid;
    private long mPendingSince;
    private final float[] mPendingLeft = new float[3];
    private final float[] mPendingRight = new float[3];
    private boolean mTickPosted;

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
        return start(false);
    }

    /**
     * Starts mirroring.  {@code keepLast} is used when the panel comes back:
     * the previous colour and its heartbeat stay published until the first new
     * frame, so hwd never falls back to the configured colour on wake.
     */
    public boolean start(boolean keepLast) {
        if (mRunning) {
            return true;
        }
        mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        if (mDisplayManager == null) {
            Log.w(TAG, "screen sync: no display service");
            return false;
        }
        if (mHandler == null) {
            mThread = new HandlerThread("ashfall-led-sync", Process.THREAD_PRIORITY_DISPLAY);
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
        }
        if (!openCapture()) {
            release();
            return false;
        }

        mLastFrameAt = 0;
        mPublishBudgetMs = 0;
        mGotFrame = false;
        mYielded = false;
        mPendingValid = false;
        mPendingSince = 0;
        if (!keepLast) {
            mHasColour = false;
            mLeft[0] = mLeft[1] = mLeft[2] = 0f;
            mRight[0] = mRight[1] = mRight[2] = 0f;
            mLastValue = "";
        }
        mStartedAt = SystemClock.elapsedRealtime();
        mFailureReported = false;
        mRunning = true;
        mPaused = false;
        mHeartbeatStopAt = 0;
        postHeartbeat();
        postTick();
        Log.i(TAG, "screen sync: started at " + Led.screenRate() + " Hz");
        return true;
    }

    private boolean openCapture() {
        try {
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
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "screen sync: capture did not start", t);
            releaseCapture();
            return false;
        }
    }

    /**
     * Panel off: stop the capture but keep the last override and its heartbeat
     * for two seconds.  hwd turns the rings off 250 ms after the panel goes
     * dark; without this grace it would briefly fall back to the configured
     * lighting because the override had already been cleared.
     */
    public void pauseForScreenOff() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        mPaused = true;
        mHeartbeatStopAt = SystemClock.elapsedRealtime() + PAUSE_HEARTBEAT_MS;
        releaseCapture();
        synchronized (mLock) {
            if (mHasColour) {
                writeHeartbeatLocked();
            }
        }
        postHeartbeat();
        Log.i(TAG, "screen sync: paused for screen off");
    }

    /**
     * Panel on: re-publish the last colour with a fresh heartbeat immediately,
     * then restart the capture.  hwd's wake re-apply then uses the last screen
     * colour, not the configured one.
     */
    public void resumeFromScreenOn() {
        if (!mPaused) {
            return;
        }
        mPaused = false;
        synchronized (mLock) {
            if (!mLastValue.isEmpty()) {
                final String current = Props.get(OVERRIDE, "");
                if (current.isEmpty() || current.equals(mLastValue)) {
                    Props.set(OVERRIDE, mLastValue);
                    writeHeartbeatLocked();
                }
            }
        }
        start(true);
    }

    public boolean isPaused() {
        return mPaused;
    }

    /**
     * Stops mirroring and clears the override and the heartbeat.  Safe to call
     * repeatedly and from any thread; the properties are always cleared.
     */
    public void stop() {
        final boolean wasRunning = mRunning;
        mRunning = false;
        mPaused = false;
        mHeartbeatStopAt = 0;
        mStartedAt = 0;
        // Serialised with publish() so a frame that was already in flight can
        // never re-latch the rings after this clear.
        synchronized (mLock) {
            clearOwnOverrideLocked();
            mHasColour = false;
            mPendingValid = false;
            mPendingSince = 0;
        }
        release();
        if (wasRunning) {
            Log.i(TAG, "screen sync: stopped");
        }
    }

    public boolean isRunning() {
        return mRunning;
    }

    /**
     * "Delay changes" in milliseconds: {@code 0} (Off, default), 100, 250 or
     * 500.  A value that is not in {@link #SCREEN_DELAYS_MS} reads as Off.
     */
    public static int screenDelay() {
        final int value = Props.getInt(SCREEN_DELAY, DEFAULT_SCREEN_DELAY_MS);
        for (int candidate : SCREEN_DELAYS_MS) {
            if (candidate == value) {
                return candidate;
            }
        }
        return DEFAULT_SCREEN_DELAY_MS;
    }

    public static void setScreenDelay(int ms) {
        for (int candidate : SCREEN_DELAYS_MS) {
            if (candidate == ms) {
                Props.setInt(SCREEN_DELAY, ms);
                return;
            }
        }
    }

    /**
     * "Colour boost" percent: {@code 0} (Off), 30 (Low), 60 (Medium, default)
     * or 100 (High).  A value that is not in {@link #SCREEN_BOOSTS_PCT} reads
     * as the default.
     */
    public static int screenBoost() {
        final int value = Props.getInt(SCREEN_BOOST, DEFAULT_SCREEN_BOOST_PCT);
        for (int candidate : SCREEN_BOOSTS_PCT) {
            if (candidate == value) {
                return candidate;
            }
        }
        return DEFAULT_SCREEN_BOOST_PCT;
    }

    public static void setScreenBoost(int pct) {
        for (int candidate : SCREEN_BOOSTS_PCT) {
            if (candidate == pct) {
                Props.setInt(SCREEN_BOOST, pct);
                return;
            }
        }
    }

    /** "Dim dark scenes": on by default, read live on every publish. */
    public static boolean screenDim() {
        return Props.getBoolean(SCREEN_DIM, DEFAULT_SCREEN_DIM);
    }

    public static void setScreenDim(boolean dim) {
        Props.set(SCREEN_DIM, dim ? "1" : "0");
    }

    /**
     * Closes the capture producer but keeps the handler thread alive, so the
     * last override can stay alive through a short screen-off window (600 ms
     * grace) and be re-published before the panel is on again.
     */
    private void releaseCapture() {
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
        mTickPosted = false;
    }

    private void release() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        releaseCapture();
        if (mThread != null) {
            mThread.quitSafely();
            mThread = null;
        }
        mHeartbeatPosted = false;
        mTickPosted = false;
    }

    private void postHeartbeat() {
        final Handler handler = mHandler;
        if (handler == null || mHeartbeatPosted) {
            return;
        }
        mHeartbeatPosted = true;
        handler.postDelayed(mHeartbeat, HEARTBEAT_MS);
    }

    private void postTick() {
        final Handler handler = mHandler;
        if (handler == null || mTickPosted || !mRunning) {
            return;
        }
        mTickPosted = true;
        handler.postDelayed(mTick, TICK_MS);
    }

    private final Runnable mTick = this::tick;

    /**
     * Advances the delay gate and refreshes the published colour without a
     * captured frame (#241).  {@code VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR} is
     * damage-driven, so a static screen (paused video, a still image, an idle
     * app) may stop producing frames; without this tick a pending pair would
     * never be adopted and a brightness change would never be applied.
     *
     * <p>{@link #publish(long)} does the {@code mRunning}/{@code mLock} check
     * itself, so a tick that races {@link #stop()} cannot re-latch the rings.
     */
    private void tick() {
        mTickPosted = false;
        if (!mRunning) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (adoptPending(now)) {
            mHasColour = true;
        }
        if (mHasColour) {
            // Re-publishes the current pair with the current brightness; the
            // value-equality check turns an unchanged pair into a heartbeat.
            publish(TICK_MS);
        }
        postTick();
    }

    private final Runnable mHeartbeat = this::heartbeat;

    /** Keeps the override alive for the daemon's stale-override guardian (#94). */
    private void heartbeat() {
        final long now = SystemClock.elapsedRealtime();
        // While paused the heartbeat only covers the screen-off grace; after
        // it the property stays but goes stale, so sleep has no 400 ms writes.
        if (!mRunning && (!mPaused || now >= mHeartbeatStopAt)) {
            mHeartbeatPosted = false;
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
        // frames is a hold, not an error (#195).  A paused sampler has no
        // capture to judge.
        if (mRunning && !mGotFrame && mStartedAt != 0
                && now - mStartedAt > START_TIMEOUT_MS) {
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
        final float[][] halves = averages(buffer, rowStride, pixelStride, width, height);
        if (halves == null) {
            return;
        }
        final float[] left = halves[0];
        final float[] right = halves[1];

        if (adopt(left, right, SystemClock.elapsedRealtime())) {
            mHasColour = true;
        }
        if (!mHasColour) {
            // No usable colour yet (e.g. the first frame was black): the rings
            // keep the configured effect until a half has shown something.
            return;
        }
        // Published even while a delayed colour is still pending, so the
        // brightness slider stays live on the colour that is on the rings.
        publish(dtMs);
    }

    /**
     * Literal split average of one RGBA_8888 frame, independent of Android
     * services so it can be exercised on the host.
     *
     * <p>Each half is summed in linear light and the mean is re-encoded, so an
     * even red/blue split yields pure red and pure blue rings and a white UI
     * yields white rings.  No chroma weighting, no value lift: the ring shows
     * the average of its half.
     *
     * @return {@code {left, right}} in sRGB 0..1, an entry is {@code null} when
     *         that half is near-black; {@code null} for an empty frame
     */
    static float[][] averages(byte[] buffer, int rowStride, int pixelStride, int width,
            int height) {
        final int half = width / 2;

        double leftR = 0, leftG = 0, leftB = 0;
        int leftN = 0;
        double rightR = 0, rightG = 0, rightB = 0;
        int rightN = 0;
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
            }
        }

        if (leftN == 0 || rightN == 0) {
            return null;
        }
        return new float[][] {
                encode(leftR / leftN, leftG / leftN, leftB / leftN),
                encode(rightR / rightN, rightG / rightN, rightB / rightN),
        };
    }

    /**
     * Decides which ring colours may be published now.
     *
     * <p>A {@code null} half is near-black (secure surface, DRM, panel off) and
     * keeps its ring's last colour.  With a delay the candidate pair must stay
     * unchanged for that many milliseconds before it is adopted, so brief
     * flashes and scrolling do not repaint the rings; Off (0, the default)
     * adopts every frame and publishes immediately.
     *
     * @return true when {@link #mLeft}/{@link #mRight} now hold a publishable
     *         pair (which includes "unchanged, still valid")
     */
    private boolean adopt(float[] left, float[] right, long now) {
        final float[] nextLeft = left != null ? left : (mHasColour ? mLeft : null);
        final float[] nextRight = right != null ? right : (mHasColour ? mRight : null);
        if (nextLeft == null || nextRight == null) {
            return false;
        }
        if (!mHasColour) {
            // The first colour of a session is not a "change": publish it now
            // so switching Match screen on (or the panel coming back) does not
            // show the restored effect for the whole delay window.
            copy(nextLeft, mLeft);
            copy(nextRight, mRight);
            return true;
        }
        final long key = key(nextLeft, nextRight);
        if (key == key(mLeft, mRight)) {
            // Already on the rings: nothing to wait for.
            mPendingValid = false;
            return true;
        }
        final int delay = screenDelay();
        if (delay <= 0) {
            copy(nextLeft, mLeft);
            copy(nextRight, mRight);
            mPendingValid = false;
            return true;
        }
        // Compare 8-bit values: below that the readback noise would restart
        // the stability window forever.
        if (!mPendingValid || key != mPendingKey) {
            mPendingKey = key;
            mPendingValid = true;
            mPendingSince = now;
            copy(nextLeft, mPendingLeft);
            copy(nextRight, mPendingRight);
            return false;
        }
        if (!delayElapsed(mPendingSince, now, delay)) {
            return false;
        }
        copy(nextLeft, mLeft);
        copy(nextRight, mRight);
        mPendingValid = false;
        return true;
    }

    /**
     * Adopts the stored pending pair once its delay has elapsed, without a
     * captured frame (#241).  The tick is the only caller; frames go through
     * {@link #adopt} so they can also start or reset the window.
     */
    private boolean adoptPending(long now) {
        if (!mPendingValid || !mHasColour) {
            return false;
        }
        final int delay = screenDelay();
        if (delay > 0 && !delayElapsed(mPendingSince, now, delay)) {
            return false;
        }
        copy(mPendingLeft, mLeft);
        copy(mPendingRight, mRight);
        mPendingValid = false;
        return true;
    }

    /**
     * True when a pair first seen at {@code since} may be adopted at
     * {@code now}: Off (0) adopts immediately, otherwise the whole delay must
     * have passed.  Pure so the gate timing can be checked on the host.
     */
    static boolean delayElapsed(long since, long now, int delayMs) {
        return delayMs <= 0 || now - since >= delayMs;
    }

    /**
     * "Colour boost": multiplies HSV saturation by {@code factor} and keeps V,
     * applied to an sRGB colour (the literal per-half average).  White/grey
     * (S = 0) and fully saturated colours (S = 1) are copied unchanged, so a
     * white UI stays white and an exact red/blue split stays exact.
     * {@code factor <= 1} is a bit-exact copy, i.e. Off reproduces the literal
     * average.  Pure and allocation-free so the host harness can check it.
     */
    static void boostSaturation(float[] in, float factor, float[] out) {
        final float r = in[0];
        final float g = in[1];
        final float b = in[2];
        final float max = Math.max(r, Math.max(g, b));
        final float min = Math.min(r, Math.min(g, b));
        final float v = max;
        final float delta = max - min;
        if (factor <= 1f || v <= 0f || delta <= 0f) {
            copy(in, out);
            return;
        }
        final float s = delta / v;
        if (s >= 1f) {
            copy(in, out);
            return;
        }
        final float boosted = Math.min(1f, s * factor);
        float hue;
        if (max == r) {
            hue = (g - b) / delta;
        } else if (max == g) {
            hue = 2f + (b - r) / delta;
        } else {
            hue = 4f + (r - g) / delta;
        }
        if (hue < 0f) {
            hue += 6f;
        }
        final int sector = (int) Math.floor(hue);
        final float f = hue - sector;
        final float p = v * (1f - boosted);
        final float q = v * (1f - boosted * f);
        final float t = v * (1f - boosted * (1f - f));
        switch (sector % 6) {
            case 1:
                out[0] = q;
                out[1] = v;
                out[2] = p;
                break;
            case 2:
                out[0] = p;
                out[1] = v;
                out[2] = t;
                break;
            case 3:
                out[0] = p;
                out[1] = q;
                out[2] = v;
                break;
            case 4:
                out[0] = t;
                out[1] = p;
                out[2] = v;
                break;
            case 5:
                out[0] = v;
                out[1] = p;
                out[2] = q;
                break;
            case 0:
            default:
                out[0] = v;
                out[1] = t;
                out[2] = p;
                break;
        }
    }

    private static long key(float[] left, float[] right) {
        return ((long) to8(left[0]) << 40) | ((long) to8(left[1]) << 32)
                | ((long) to8(left[2]) << 24) | ((long) to8(right[0]) << 16)
                | ((long) to8(right[1]) << 8) | to8(right[2]);
    }

    private static int to8(float value) {
        return Math.round(clamp01(value) * 255f);
    }

    /** sRGB 0..1, or null for a near-black half (hold that ring). */
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
        return out;
    }

    private void publish(long dtMs) {
        // Read on every publish, not once per session: the brightness slider
        // must change the rings immediately while Match screen is on.  The
        // daemon applies the override as final intensity, so scaling here is
        // the brightness control (docs/phase5.md §6.1).
        final int brightness = Led.brightness();
        // Colour boost is also read live, and runs before the brightness scale
        // on the literal per-half average.  Off (factor 1.0) copies the
        // average bit-exactly, so it reproduces the unboosted behaviour.
        // 30/60/100 map to 1.6/2.2/3.0 (factor = 1 + pct/50).
        final float boost = 1f + screenBoost() / 50f;
        boostSaturation(mLeft, boost, mBoostedLeft);
        boostSaturation(mRight, boost, mBoostedRight);
        // Dark scenes should dim the ring instead of glowing as a bright
        // grey/white: scale each ring by its own HSV value (max channel).
        if (screenDim()) {
            dimDark(mBoostedLeft);
            dimDark(mBoostedRight);
        }
        final int rLeft = scale(mBoostedLeft[0], brightness);
        final int gLeft = scale(mBoostedLeft[1], brightness);
        final int bLeft = scale(mBoostedLeft[2], brightness);
        final int rRight = scale(mBoostedRight[0], brightness);
        final int gRight = scale(mBoostedRight[1], brightness);
        final int bRight = scale(mBoostedRight[2], brightness);
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

    /**
     * "Dim dark scenes": multiply by HSV value (the brightest channel), so a
     * near-black half goes near-off and a fully bright colour is unchanged.
     */
    private static void dimDark(float[] colour) {
        final float value = Math.max(colour[0], Math.max(colour[1], colour[2]));
        colour[0] *= value;
        colour[1] *= value;
        colour[2] *= value;
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
