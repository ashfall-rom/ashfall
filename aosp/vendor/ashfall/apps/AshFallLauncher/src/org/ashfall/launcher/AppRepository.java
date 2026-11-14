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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the launchable activity list off the main thread and keeps it fresh
 * while the launcher is started.
 *
 * <p>Package broadcasts are the only refresh trigger: ACTION_PACKAGE_ADDED,
 * REMOVED, CHANGED and REPLACED are exempt from the implicit-broadcast
 * restrictions, so a dynamically registered receiver sees installs and
 * uninstalls immediately while the launcher is in the foreground; onStart()
 * also reloads, which covers changes made while a game was in front.
 */
final class AppRepository {
    private static final String TAG = "AshFallLauncher";

    interface Listener {
        void onAppsLoaded(List<AppEntry> apps);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ashfall-apps");
                thread.setDaemon(true);
                return thread;
            });

    private boolean started;
    private boolean destroyed;
    private int generation;

    private final Runnable reloadRunnable = this::reload;

    private final android.content.BroadcastReceiver packageReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    // Batch installs/updates arrive as a burst; coalesce them.
                    main.removeCallbacks(reloadRunnable);
                    main.postDelayed(reloadRunnable, 250);
                }
            };

    AppRepository(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (destroyed || started) {
            return;
        }
        started = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        reload();
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        main.removeCallbacks(reloadRunnable);
        try {
            context.unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
    }

    void destroy() {
        destroyed = true;
        stop();
        executor.shutdownNow();
    }

    void reload() {
        if (destroyed) {
            return;
        }
        final int request = ++generation;
        executor.execute(() -> {
            List<AppEntry> apps = loadApps();
            main.post(() -> {
                if (!destroyed && request == generation) {
                    listener.onAppsLoaded(apps);
                }
            });
        });
    }

    private List<AppEntry> loadApps() {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(intent, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not query launchable activities", e);
            return Collections.emptyList();
        }

        String ownPackage = context.getPackageName();
        List<AppEntry> apps = new ArrayList<>(resolved.size());
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.applicationInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (TextUtils.isEmpty(packageName) || ownPackage.equals(packageName)) {
                continue;
            }
            if (!info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) {
                continue;
            }

            CharSequence label;
            try {
                label = info.loadLabel(pm);
            } catch (RuntimeException e) {
                label = packageName;
            }
            if (TextUtils.isEmpty(label)) {
                label = packageName;
            }

            Drawable icon;
            try {
                icon = info.loadIcon(pm);
            } catch (RuntimeException e) {
                icon = null;
            }
            if (icon == null) {
                icon = pm.getDefaultActivityIcon();
            }

            apps.add(new AppEntry(
                    label.toString().trim(),
                    packageName,
                    new android.content.ComponentName(packageName, info.activityInfo.name),
                    icon));
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(apps, (left, right) -> {
            int byLabel = collator.compare(left.label, right.label);
            if (byLabel != 0) {
                return byLabel;
            }
            return left.key().compareTo(right.key());
        });
        return apps;
    }

    /** Cheap fingerprint used to avoid rebuilding identical grids. */
    static String signature(List<AppEntry> apps) {
        StringBuilder builder = new StringBuilder(apps.size() * 48);
        for (AppEntry app : apps) {
            builder.append(app.key()).append('\u0000').append(app.label).append('\u0001');
        }
        return builder.toString();
    }
}
