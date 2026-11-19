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

package org.ashfall.parts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.ashfall.parts.hw.Led;
import org.ashfall.parts.ui.ScreenLedSync;

/**
 * Foreground service for the "Match screen" effect (#167).
 *
 * <p>Started only while the effect is selected, never at boot.  It supervises
 * {@link ScreenLedSync}: sampling runs only while lighting is on, screen sync is
 * selected, the panel is on and the gamepad tester has not taken the rings over
 * ({@code ashfall.ui.led_suspend}).  Every exit path — effect change, lighting
 * off, screen off/doze, tester suspend, the Stop action, service destruction and
 * a capture failure — clears {@code ashfall.ui.led_override} and the
 * {@code ashfall.ui.led_override_live} heartbeat.
 */
public class LedSyncService extends Service implements DisplayManager.DisplayListener {

    private static final String TAG = "AshFallParts";
    private static final String ACTION_START = "org.ashfall.parts.action.LED_SYNC_START";
    private static final String ACTION_STOP = "org.ashfall.parts.action.LED_SYNC_STOP";
    private static final String CHANNEL_ID = "led_screen_sync";
    private static final int NOTIFICATION_ID = 1670;
    private static final long SUPERVISE_MS = 400;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ScreenLedSync mSync;
    private boolean mSupervising;
    private boolean mFailed;
    private boolean mDestroyed;
    private boolean mDisplayListenerRegistered;

    /** Starts or stops the service to match the current effect. */
    public static void sync(Context context) {
        if (Led.screenSync()) {
            start(context);
        } else {
            stop(context);
        }
    }

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context,
                    new Intent(context, LedSyncService.class).setAction(ACTION_START));
        } catch (Throwable t) {
            // A refused foreground start must not leave the effect selected
            // with nothing running: fail the same way a capture error does.
            Log.w(TAG, "screen sync: cannot start the service", t);
            Led.setScreenSync(false);
            Led.setMode(Led.mode());
            Toast.makeText(context.getApplicationContext(),
                    R.string.lighting_screen_failed, Toast.LENGTH_LONG).show();
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, LedSyncService.class));
        } catch (Throwable ignored) {
            // Nothing left to stop.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        // Frame callbacks run on the sampler thread; the failure path touches
        // the UI, so bounce it to the main thread.
        mSync = new ScreenLedSync(this,
                reason -> mHandler.post(() -> onCaptureFailed(reason)));
        // React to panel on/off immediately instead of waiting for the next
        // supervisor tick: hwd re-applies the LEDs 250 ms after a wake, and
        // the last screen colour must already be re-published by then.
        final DisplayManager displays =
                (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displays != null) {
            displays.registerDisplayListener(this, mHandler);
            mDisplayListenerRegistered = true;
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY || mDestroyed || !mSupervising) {
            return;
        }
        if (!screenOn()) {
            if (mSync != null && mSync.isRunning()) {
                mSync.pauseForScreenOff();
            }
            return;
        }
        if (mSync == null || suspended() || !Led.screenSyncFlag() || !Led.isOn()) {
            return;
        }
        if (mSync.isPaused()) {
            mSync.resumeFromScreenOn();
        } else if (!mSync.isRunning()) {
            mSync.start(true);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // The notification's Stop turns the effect off, so the Lighting
            // page does not keep claiming Match screen with nothing running.
            Led.setScreenSync(false);
            Led.setMode(Led.mode());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Led.screenSyncFlag() || !Led.isOn()) {
            // The effect changed before the notification appeared.
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            Log.w(TAG, "screen sync: foreground service refused", t);
            failSafe(getString(R.string.lighting_screen_failed));
            return START_NOT_STICKY;
        }
        if (!mSupervising) {
            mSupervising = true;
            mHandler.post(mSupervise);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        mSupervising = false;
        if (mDisplayListenerRegistered) {
            final DisplayManager displays =
                    (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (displays != null) {
                displays.unregisterDisplayListener(this);
            }
            mDisplayListenerRegistered = false;
        }
        mHandler.removeCallbacksAndMessages(null);
        if (mSync != null) {
            // Clears ashfall.ui.led_override and the heartbeat.
            mSync.stop();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable mSupervise = this::supervise;

    /**
     * Single supervisor for every lifecycle input.  Runs while the service is
     * alive so screen off/doze, the tester's suspend flag and an effect change
     * from any surface are all handled without a second policy path.
     */
    private void supervise() {
        if (!mSupervising) {
            return;
        }
        if (!Led.screenSyncFlag() || !Led.isOn()) {
            // Effect changed or lighting switched off.
            stopSelf();
            return;
        }
        final boolean want = !suspended() && screenOn();
        if (want) {
            if (mSync != null && !mSync.isRunning() && !mSync.start()) {
                onCaptureFailed("capture did not start");
                return;
            }
        } else if (suspended()) {
            // The tester owns the rings: clear our override as before.
            if (mSync != null && mSync.isRunning()) {
                mSync.stop();
            }
        } else if (!screenOn() && mSync != null && mSync.isRunning()) {
            // Keep the last colour and its heartbeat through the screen-off
            // debounce so the configured colour never flashes before off.
            mSync.pauseForScreenOff();
        }
        mHandler.postDelayed(mSupervise, SUPERVISE_MS);
    }

    private void onCaptureFailed(String reason) {
        if (mDestroyed) {
            return;
        }
        Log.w(TAG, "screen sync failed: " + reason);
        failSafe(getString(R.string.lighting_screen_failed));
    }

    /**
     * Disables the effect, restores the previous real effect, clears the
     * override and stops the service.  Used when capture cannot start or
     * throws, so the rings can never stay latched.
     */
    private void failSafe(String message) {
        if (mFailed) {
            return;
        }
        mFailed = true;
        mSupervising = false;
        mHandler.removeCallbacksAndMessages(null);
        if (mSync != null) {
            mSync.stop();
        }
        // setMode() also clears the UI flag; the stored mode is still the last
        // real effect, so lighting returns to it.
        Led.setScreenSync(false);
        Led.setMode(Led.mode());
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        stopSelf();
    }

    /** True only while the tester's suspend token keeps changing (#193). */
    private boolean suspended() {
        return Led.suspendActive();
    }

    private boolean screenOn() {
        final PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) {
            return false;
        }
        final DisplayManager displays =
                (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displays == null) {
            return false;
        }
        final Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        return display != null && display.getState() == Display.STATE_ON;
    }

    private void createChannel() {
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.led_sync_title), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.led_sync_text));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, LightingActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LedSyncService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ashfall_lighting)
                .setContentTitle(getString(R.string.led_sync_title))
                .setContentText(getString(R.string.led_sync_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(0,
                        getString(R.string.led_sync_stop), stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
