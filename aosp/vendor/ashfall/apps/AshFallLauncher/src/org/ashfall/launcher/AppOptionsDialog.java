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
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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

    AppOptionsDialog(Activity activity, AppEntry entry) {
        this.activity = activity;
        this.entry = entry;
    }

    void show(Runnable onDismiss) {
        dialog = new Dialog(activity, R.style.Theme_AshFall_Dialog);
        dialog.setContentView(R.layout.dialog_app_options);
        dialog.setCanceledOnTouchOutside(true);

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

        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.BLACK);
        panel.setCornerRadius(activity.getResources().getDimensionPixelSize(
                R.dimen.dialog_radius));
        panel.setStroke(Ui.dp(activity, 1.5f), accent);
        root.setBackground(panel);

        dialog.setOnDismissListener(d -> {
            dialog = null;
            if (onDismiss != null) {
                onDismiss.run();
            }
        });

        open.setOnClickListener(view -> {
            dismiss();
            if (!Intents.launch(activity, entry)) {
                toast(activity.getString(R.string.launch_failed, entry.label));
            }
        });
        info.setOnClickListener(view -> {
            dismiss();
            if (!Intents.openAppInfo(activity, entry)) {
                toast(activity.getString(R.string.app_info_unavailable, entry.label));
            }
        });
        uninstall.setOnClickListener(view -> {
            dismiss();
            if (!Intents.uninstall(activity, entry)) {
                toast(activity.getString(R.string.menu_uninstall_failed, entry.label));
            }
        });
        kill.setOnClickListener(view -> {
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
            open.post(open::requestFocus);
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams params = window.getAttributes();
                params.width = activity.getResources().getDimensionPixelSize(
                        R.dimen.dialog_width);
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.gravity = android.view.Gravity.CENTER;
                params.dimAmount = 0.7f;
                window.setAttributes(params);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        dialog.show();
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
