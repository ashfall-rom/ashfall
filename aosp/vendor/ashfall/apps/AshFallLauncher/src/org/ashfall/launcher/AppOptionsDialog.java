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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Controller-friendly app options panel: the stock launcher's long-press menu
 * (AppInfo / Uninstall / Reposition) reduced to the actions AshFall supports.
 */
final class AppOptionsDialog {
    private final Activity activity;
    private final AppEntry entry;
    private Dialog dialog;
    private long shownAt;
    private boolean swallowOpeningAction;
    private static final long INPUT_GUARD_MS = 600L;

    AppOptionsDialog(Activity activity, AppEntry entry) {
        this.activity = activity;
        this.entry = entry;
    }

    void show(Runnable onDismiss, boolean swallowOpeningRelease) {
        this.swallowOpeningAction = swallowOpeningRelease;
        shownAt = SystemClock.uptimeMillis();
        dialog = new Dialog(activity, R.style.Theme_AshFall_Dialog);
        dialog.setContentView(R.layout.dialog_container);
        dialog.setCanceledOnTouchOutside(false);

        TextView title = dialog.findViewById(R.id.app_title);
        TextView packageName = dialog.findViewById(R.id.app_package);
        title.setText(entry.label);
        packageName.setText(entry.packageName);

        View root = dialog.findViewById(R.id.dialog_root);
        View open = dialog.findViewById(R.id.action_open);
        View info = dialog.findViewById(R.id.action_info);
        View uninstall = dialog.findViewById(R.id.action_uninstall);
        View kill = dialog.findViewById(R.id.action_kill);

        int accent = Appearance.load(activity).accent;
        Ui.applyActionBackground(open, accent);
        Ui.applyActionBackground(info, accent);
        Ui.applyActionBackground(uninstall, accent);
        Ui.applyActionBackground(kill, accent);
        tintIcon(open, accent);
        tintIcon(info, accent);
        tintIcon(uninstall, accent);
        tintIcon(kill, accent);

        // Follow the system dialog theme; only the accent outline stays AshFall.
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Ui.dialogBackgroundColor(dialog.getContext()));
        panel.setCornerRadius(activity.getResources().getDimensionPixelSize(
                R.dimen.dialog_radius));
        panel.setStroke(Ui.dp(activity, 1.5f), accent);
        root.setBackground(panel);
        root.setClickable(true);

        View container = dialog.findViewById(R.id.dialog_modal_root);
        container.setOnClickListener(view -> dismiss());

        dialog.setOnDismissListener(d -> {
            dialog = null;
            if (onDismiss != null) {
                onDismiss.run();
            }
        });

        open.setOnClickListener(view -> {
            if (!acceptsInput()) {
                return;
            }
            dismiss();
            if (!Intents.launch(activity, entry)) {
                toast(activity.getString(R.string.launch_failed, entry.label));
            }
        });
        info.setOnClickListener(view -> {
            if (!acceptsInput()) {
                return;
            }
            dismiss();
            if (!Intents.openAppInfo(activity, entry)) {
                toast(activity.getString(R.string.app_info_unavailable, entry.label));
            }
        });
        uninstall.setOnClickListener(view -> {
            if (!acceptsInput()) {
                return;
            }
            dismiss();
            if (!Intents.uninstall(activity, entry)) {
                toast(activity.getString(R.string.menu_uninstall_failed, entry.label));
            }
        });
        kill.setOnClickListener(view -> {
            if (!acceptsInput()) {
                return;
            }
            dismiss();
            try {
                ActivityManager manager = (ActivityManager) activity.getSystemService(
                        Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    manager.killBackgroundProcesses(entry.packageName);
                }
                toast(activity.getString(R.string.menu_kill_done, entry.label));
            } catch (RuntimeException e) {
                toast(activity.getString(R.string.menu_kill_failed, entry.label));
            }
        });

        if (!isUninstallable()) {
            uninstall.setVisibility(View.GONE);
            info.setNextFocusRightId(R.id.action_kill);
            kill.setNextFocusLeftId(R.id.action_info);
        }

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B
                        || keyCode == KeyEvent.KEYCODE_BACK) {
                    dismiss();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (swallowOpeningAction) {
                        swallowOpeningAction = false;
                        return true;
                    }
                    if (!acceptsInput()) {
                        return true;
                    }
                    View focused = dialog.getWindow() == null
                            ? null : dialog.getWindow().getCurrentFocus();
                    if (focused != null && focused.getVisibility() == View.VISIBLE
                            && (focused == open || focused == info
                            || focused == uninstall || focused == kill)) {
                        focused.performClick();
                        return true;
                    }
                }
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                // Consume the down event so the framework does not also click.
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            shownAt = SystemClock.uptimeMillis();
            open.post(open::requestFocus);
            Ui.makeDialogModal(dialog, 0.7f);
        });
        dialog.show();
    }

    private boolean acceptsInput() {
        return SystemClock.uptimeMillis() - shownAt >= INPUT_GUARD_MS;
    }

    private void tintIcon(View action, int accent) {
        if (action instanceof ViewGroup) {
            View first = ((ViewGroup) action).getChildAt(0);
            if (first instanceof ImageView) {
                ((ImageView) first).setColorFilter(accent);
            }
        }
    }

    private boolean isUninstallable() {
        try {
            ApplicationInfo info = activity.getPackageManager()
                    .getApplicationInfo(entry.packageName, 0);
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updatedSystemApp =
                    (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            // A system app can only be uninstalled when it has an update that
            // can be removed (revert to the preinstalled version).
            return !system || updatedSystemApp;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        } else {
            dialog = null;
        }
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
