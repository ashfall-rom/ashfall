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
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.ashfall.parts.hw.Displays;
import org.ashfall.parts.hw.Pad;

/**
 * Display guard for the external-display gamepad pointer (#131, #238).
 *
 * <p>{@code persist.ashfall.pad.pointer} is the only thing the input daemon
 * reads, and the daemon deliberately has no display check, so something has to
 * clear it when the external display goes away.  The Fusion QS dialog can only
 * do that while it is open.  This service is started by the switch when it
 * turns on, registers a {@link DisplayManager.DisplayListener} and switches the
 * pointer off as soon as no qualifying presentation display remains (unplugged,
 * powered off, or the display changed type).  It stops itself right after;
 * nothing runs while the pointer is off.
 *
 * <p>The notification is the required foreground-service surface: a low
 * importance "Desktop pointer" notification with a Stop action.  Stop turns the
 * switch off, so the property, the daemon device and the notification can never
 * disagree.
 *
 * <p>Idempotent: repeated starts just re-check the same condition, and every
 * exit path (switch off, Stop, no display, foreground start refused, service
 * destruction) clears the property before the service goes away.
 */
public class PointerService extends Service implements DisplayManager.DisplayListener {

    private static final String TAG = "AshFallParts";
    private static final String ACTION_START = "org.ashfall.parts.action.POINTER_START";
    private static final String ACTION_STOP = "org.ashfall.parts.action.POINTER_STOP";
    private static final String CHANNEL_ID = "desktop_pointer";
    private static final int NOTIFICATION_ID = 1310;

    private DisplayManager mDisplays;
    private boolean mRegistered;

    /**
     * Starts (or re-checks) the guard.  Returns false when the foreground
     * service could not be started; the switch is then left off, so a refused
     * start can never leave the pad consumed with nothing guarding it.
     */
    public static boolean start(Context context) {
        try {
            ContextCompat.startForegroundService(context,
                    new Intent(context, PointerService.class).setAction(ACTION_START));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "pointer: cannot start the display guard", t);
            Pad.setPointer(false);
            Toast.makeText(context.getApplicationContext(),
                    R.string.fusion_pointer_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, PointerService.class));
        } catch (Throwable ignored) {
            // Nothing left to stop.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        mDisplays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (mDisplays != null) {
            mDisplays.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
            mRegistered = true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        try {
            // Always go foreground first: a startForegroundService() caller
            // that is stopped without this would be a service crash on A14+.
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            Log.w(TAG, "pointer: foreground service refused", t);
            disable();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            // The notification's Stop turns the switch off.
            disable();
            return START_NOT_STICKY;
        }
        if (!Pad.pointer()) {
            // The switch changed before the notification appeared.
            stopSelf();
            return START_NOT_STICKY;
        }
        checkTarget();
        // Restart after a process kill: the property may still say on, and the
        // guard must survive until the display or the user turns it off.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mRegistered && mDisplays != null) {
            mDisplays.unregisterDisplayListener(this);
            mRegistered = false;
        }
        // The property is deliberately not cleared here: the service is stopped
        // by the switch itself (which already wrote 0), by disable() (which
        // clears it) and by the system, which restarts it (START_STICKY).  A
        // clear here could race an immediate off -> on toggle and cancel the
        // new switch state.
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- display guard -------------------------------------------------------

    @Override
    public void onDisplayAdded(int displayId) {
        // A display appearing does not end the pointer; nothing to do.
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        checkTarget();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        checkTarget();
    }

    /**
     * Turns the pointer off when the switch was cleared elsewhere or when the
     * last qualifying presentation display is gone.
     */
    private void checkTarget() {
        if (!Pad.pointer()) {
            stopSelf();
            return;
        }
        if (Displays.hasExternal(this)) {
            return;
        }
        Log.i(TAG, "pointer: no presentation display left, switching off");
        Toast.makeText(getApplicationContext(), R.string.fusion_pointer_display_gone,
                Toast.LENGTH_LONG).show();
        disable();
    }

    /** Clears the switch and stops the guard; safe to call more than once. */
    private void disable() {
        Pad.setPointer(false);
        if (mRegistered && mDisplays != null) {
            mDisplays.unregisterDisplayListener(this);
            mRegistered = false;
        }
        stopSelf();
    }

    // ---- notification --------------------------------------------------------

    private void createChannel() {
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.fusion_pointer_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.fusion_pointer_notification_text));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, ControllerActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PointerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_gamepad)
                .setContentTitle(getString(R.string.fusion_pointer_title))
                .setContentText(getString(R.string.fusion_pointer_notification_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(0,
                        getString(R.string.fusion_pointer_stop), stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
