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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Top strip: the focused app name on the left, Wi-Fi and battery on the right.
 * This mirrors the stock launcher's status strip (avatar/battery/Wi-Fi in
 * launcher_status_bar_layout) minus the avatar.
 */
public final class StatusBarView extends LinearLayout {
    private TextView focusedLabel;
    private ImageView wifiIcon;
    private BatteryView batteryView;
    private TextView batteryText;

    private ConnectivityManager connectivityManager;
    private boolean attached;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                applyBattery(intent);
            }
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    post(StatusBarView.this::updateWifi);
                }

                @Override
                public void onLost(Network network) {
                    post(StatusBarView.this::updateWifi);
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                        NetworkCapabilities capabilities) {
                    post(StatusBarView.this::updateWifi);
                }
            };

    public StatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(28), 0, dp(28), 0);

        LayoutInflater.from(context).inflate(R.layout.view_status_bar, this, true);

        focusedLabel = findViewById(R.id.focused_label);
        focusedLabel.setSelected(true);
        wifiIcon = findViewById(R.id.wifi_icon);
        batteryView = findViewById(R.id.battery_view);
        batteryText = findViewById(R.id.battery_text);
    }

    void setFocusedLabel(CharSequence label) {
        focusedLabel.setText(label);
        // Restart the marquee when a new app takes focus.
        focusedLabel.setSelected(false);
        focusedLabel.setSelected(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (attached) {
            return;
        }
        attached = true;

        Intent sticky = getContext().registerReceiver(
                batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            applyBattery(sticky);
        }

        connectivityManager =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // No default network yet; the icons stay in the disconnected state.
            }
        }
        updateWifi();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (attached) {
            attached = false;
            try {
                getContext().unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            if (connectivityManager != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered.
                }
            }
        }
        super.onDetachedFromWindow();
    }

    private void applyBattery(Intent intent) {
        int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
        int percent = level < 0 ? -1
                : Math.round(100f * level / Math.max(1, scale));
        int status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,
                android.os.BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || status == android.os.BatteryManager.BATTERY_STATUS_FULL;

        batteryView.setLevel(percent, charging);
        batteryText.setText(percent < 0 ? "" : getContext().getString(
                R.string.battery_percent, percent));
    }

    private void updateWifi() {
        boolean connected = false;
        if (connectivityManager != null) {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null
                    ? null : connectivityManager.getNetworkCapabilities(active);
            connected = capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        wifiIcon.setImageResource(connected ? R.drawable.ic_wifi : R.drawable.ic_wifi_off);
        wifiIcon.setAlpha(connected ? 1f : 0.55f);
        wifiIcon.setContentDescription(getContext().getString(
                connected ? R.string.wifi_connected : R.string.wifi_off));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
