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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Battery power for the Power page graph (#124), sampled with the framework
 * instead of a daemon property.
 *
 * <p>The page used to read {@code ashfall.status.perf.power_mw}; the daemon
 * sampler is gone because a coredomain cannot read {@code sysfs_batteryinfo}
 * (platform neverallow) and the RP5 files carry a vendor label.  The page now
 * derives the number the same way the daemon did:
 *
 * <pre>power_mw = abs(current_ua) * voltage_mv / 1e6</pre>
 *
 * <p>Voltage and the charging state come from the sticky
 * {@link Intent#ACTION_BATTERY_CHANGED} broadcast; the current comes from
 * {@link BatteryManager#getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)} in
 * microamperes, read once per page tick.  A battery-side number is meaningless
 * while the device is charging, so charging (and an unreadable voltage or
 * current) is {@code -1}, which the graph draws as a gap.
 *
 * <p>The receiver is registered once by the page's lifecycle
 * ({@link #start}/{@link #stop}); while it is registered a sample costs one
 * property read (and none at all while charging).  A consumer without a page
 * lifecycle (the Handheld summary, the Quick Settings dialog) gets a one-shot
 * sticky read instead, still at most once per its own tick.
 */
public final class PowerBattery {

    /** {@code -1}: charging or unknown. */
    public static final int UNKNOWN = -1;

    private static BroadcastReceiver sReceiver;
    private static int sVoltageMv = UNKNOWN;
    private static boolean sCharging;
    private static boolean sKnown;

    private PowerBattery() {
    }

    /**
     * Registers the sticky battery receiver; idempotent, so the page can call
     * it from every {@code onResume}.  The sticky intent is delivered by
     * {@code registerReceiver} itself, so the first tick already has a voltage.
     */
    public static synchronized void start(Context context) {
        if (sReceiver != null) {
            return;
        }
        final Context app = context.getApplicationContext();
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                update(intent);
            }
        };
        final Intent sticky = app.registerReceiver(sReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        update(sticky);
    }

    /** Unregisters the receiver; the cached snapshot is dropped with it. */
    public static synchronized void stop(Context context) {
        if (sReceiver == null) {
            return;
        }
        try {
            context.getApplicationContext().unregisterReceiver(sReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered (e.g. the process was restarted).
        }
        sReceiver = null;
        sVoltageMv = UNKNOWN;
        sCharging = false;
        sKnown = false;
    }

    /**
     * Battery power in milliwatts, or {@link #UNKNOWN} while charging and when
     * the voltage or the current cannot be read.
     */
    public static synchronized int powerMw(Context context) {
        final Context app = context.getApplicationContext();
        int voltageMv = sVoltageMv;
        boolean charging = sCharging;
        boolean known = sKnown;
        if (!known) {
            // No page is open: one sticky read gives the voltage and the
            // charging state without registering anything.
            final Intent sticky = app.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            voltageMv = voltageMv(sticky);
            charging = charging(sticky);
            known = voltageMv > 0;
        }
        if (!known || charging || voltageMv <= 0) {
            return UNKNOWN;
        }
        final BatteryManager battery = app.getSystemService(BatteryManager.class);
        if (battery == null) {
            return UNKNOWN;
        }
        final int currentUa = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (currentUa == Integer.MIN_VALUE) {
            return UNKNOWN;
        }
        // uA * mV / 1e6 = mW.
        final long milliwatts = Math.abs((long) currentUa) * voltageMv / 1000000L;
        return (int) Math.min(Integer.MAX_VALUE, milliwatts);
    }

    /**
     * Whether the device is charging (plugged in, or the battery reports
     * charging).  The page uses it to explain the {@code -1} sample: charging is
     * the one case where the number is deliberately absent, an unknown current is
     * not.
     */
    public static synchronized boolean charging(Context context) {
        if (sKnown) {
            return sCharging;
        }
        final Context app = context.getApplicationContext();
        final Intent sticky = app.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return charging(sticky);
    }

    /**
     * Whether a battery reading exists at all, independent of charging: the row
     * shows the graph when there is one, even with no daemon publishing clocks.
     */
    public static synchronized boolean available(Context context) {
        if (sKnown) {
            return sVoltageMv > 0;
        }
        final Context app = context.getApplicationContext();
        final Intent sticky = app.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return sticky != null && voltageMv(sticky) > 0;
    }

    private static void update(Intent intent) {
        sVoltageMv = voltageMv(intent);
        sCharging = charging(intent);
        sKnown = sVoltageMv > 0;
    }

    private static int voltageMv(Intent intent) {
        if (intent == null) {
            return UNKNOWN;
        }
        return intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, UNKNOWN);
    }

    /**
     * Charging includes "plugged in but full": the battery current says nothing
     * about the load then, which is exactly why the contract publishes -1.
     */
    private static boolean charging(Intent intent) {
        if (intent == null) {
            return false;
        }
        final int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, UNKNOWN);
        return plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING;
    }
}
