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
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.Collections;
import java.util.List;

/**
 * The AshFall HOME activity.
 *
 * <p>Navigation model (controller-first, touch works too):
 * <ul>
 *   <li>D-pad / left stick: move the focus inside the 5x3 page; pushing past a
 *       horizontal edge turns the page (row is preserved).</li>
 *   <li>A / D-pad center / Enter: launch the focused app.</li>
 *   <li>Hold A, or Menu/Start: app options (open, app info, uninstall).</li>
 *   <li>B / Back: dismiss the options panel; on the home screen Back is a
 *       no-op (the stock launcher behaves the same way).</li>
 *   <li>L1 / R1 (or Page Up/Down): previous / next page.</li>
 *   <li>Select: Settings shortcut.</li>
 * </ul>
 */
public final class HomeActivity extends Activity implements AppPager.Listener {
    private static final long LONG_PRESS_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AppPager pager;
    private LauncherBackgroundView backgroundView;
    private PageIndicatorView pageDots;
    private BottomBarView bottomBar;
    private TextView emptyState;
    private ImageButton previousPage;
    private ImageButton nextPage;

    private AppRepository repository;

    private List<AppEntry> apps = Collections.emptyList();
    private String lastSignature;
    private boolean loaded;

    private boolean optionsShowing;
    private boolean aLongPressFired;
    private long lastDirectionAt;
    private int lastStickDirection;
    private long lastStickAt;

    private OnBackInvokedCallback backCallback;

    private final Runnable longPressRunnable = () -> {
        aLongPressFired = true;
        showAppOptions();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        pager = findViewById(R.id.pager);
        pager.setListener(this);

        backgroundView = findViewById(R.id.background);
        pageDots = findViewById(R.id.page_dots);
        bottomBar = findViewById(R.id.bottom_bar);
        emptyState = findViewById(R.id.empty_state);
        previousPage = findViewById(R.id.previous_page);
        nextPage = findViewById(R.id.next_page);

        bottomBar.setOnSettingsClickListener(view -> openSettings());
        previousPage.setOnClickListener(view -> pager.changePage(-1));
        nextPage.setOnClickListener(view -> pager.changePage(1));

        repository = new AppRepository(this, this::onAppsLoaded);
        updateEmptyState();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            backCallback = () -> {
                // Back on the home screen is a no-op: the launcher must never
                // close itself, including for the predictive-back gesture.
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAppearance();
    }

    @Override
    protected void onStop() {
        repository.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        repository.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A fresh HOME press starts at the first page, like the stock
        // launcher's clearTaskOnLaunch behavior. Reloads while the launcher
        // stays in front keep the focused app.
        pager.goToFirstPage();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pager != null) {
            pager.requestFocusCurrent();
        }
    }

    public void onAppsLoaded(List<AppEntry> loadedApps) {
        apps = loadedApps;
        loaded = true;
        String signature = AppRepository.signature(loadedApps);
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            pager.setApps(loadedApps);
        }
        updateEmptyState();
    }

    @Override
    public void onPageChanged(int page, int pageCount) {
        pageDots.setPage(page, pageCount);
        boolean hasPrevious = page > 0;
        boolean hasNext = page < pageCount - 1;
        previousPage.setEnabled(hasPrevious);
        previousPage.setAlpha(hasPrevious ? 1f : 0.22f);
        nextPage.setEnabled(hasNext);
        nextPage.setAlpha(hasNext ? 1f : 0.22f);
    }

    @Override
    public void onFocusedAppChanged(AppEntry entry) {
        bottomBar.setFocusedLabel(entry == null
                ? getString(R.string.focused_none) : entry.label);
    }

    @Override
    public void onAppActivated(AppEntry entry) {
        if (!Intents.launch(this, entry)) {
            toast(getString(R.string.launch_failed, entry.label));
        }
    }

    @Override
    public void onAppOptions(AppEntry entry) {
        showAppOptions(entry);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (optionsShowing) {
            return super.dispatchKeyEvent(event);
        }

        int action = event.getAction();
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                handleDirection(event, View.FOCUS_LEFT);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                handleDirection(event, View.FOCUS_RIGHT);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                handleDirection(event, View.FOCUS_UP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                handleDirection(event, View.FOCUS_DOWN);
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (action == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                    activateFocused();
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_A:
                return handleGamepadA(event);

            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                // Consume: Back on the home screen must not leave the launcher.
                return true;

            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    pager.changePage(-1);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    pager.changePage(1);
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    showAppOptions();
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (action == KeyEvent.ACTION_UP) {
                    openSettings();
                }
                return true;

            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (optionsShowing) {
            return super.dispatchGenericMotionEvent(event);
        }
        if (event.getAction() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_JOYSTICK) == 0
                && (source & InputDevice.SOURCE_GAMEPAD) == 0) {
            return super.dispatchGenericMotionEvent(event);
        }

        // The RP5 D-pad arrives as ABS_HAT0X/Y and is converted to DPAD key
        // events by ViewRootImpl, so only the analog left stick is handled
        // here. Handling the hat here as well would move the focus twice.
        float x = safeAxis(event, MotionEvent.AXIS_X);
        float y = safeAxis(event, MotionEvent.AXIS_Y);
        float deadzone = getResources().getInteger(R.integer.stick_deadzone_percent) / 100f;

        int direction = 0;
        float absX = Math.abs(x);
        float absY = Math.abs(y);
        if (Math.max(absX, absY) >= deadzone) {
            if (absX > absY) {
                direction = x > 0 ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
            } else {
                direction = y > 0 ? View.FOCUS_DOWN : View.FOCUS_UP;
            }
        }

        if (direction == 0) {
            lastStickDirection = 0;
            return true;
        }

        long now = SystemClock.uptimeMillis();
        long repeat = getResources().getInteger(R.integer.stick_repeat_ms);
        if (direction != lastStickDirection || now - lastStickAt >= repeat) {
            pager.moveFocus(direction);
            lastStickAt = now;
        }
        lastStickDirection = direction;
        return true;
    }

    private void handleDirection(KeyEvent event, int direction) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (event.getRepeatCount() > 0) {
            long repeat = getResources().getInteger(R.integer.key_repeat_ms);
            if (now - lastDirectionAt < repeat) {
                return;
            }
        }
        lastDirectionAt = now;
        pager.moveFocus(direction);
    }

    private boolean handleGamepadA(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                aLongPressFired = false;
                handler.removeCallbacks(longPressRunnable);
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longPressRunnable);
            if (!aLongPressFired) {
                activateFocused();
            }
            aLongPressFired = false;
            return true;
        }
        return true;
    }

    private void activateFocused() {
        AppEntry entry = pager.getFocusedEntry();
        if (entry != null) {
            pager.requestFocusCurrent();
            onAppActivated(entry);
        }
    }

    private void showAppOptions() {
        AppEntry entry = pager.getFocusedEntry();
        if (entry != null) {
            showAppOptions(entry);
        }
    }

    private void showAppOptions(AppEntry entry) {
        if (optionsShowing) {
            return;
        }
        optionsShowing = true;
        AppOptionsDialog dialog = new AppOptionsDialog(this, entry);
        dialog.show(() -> {
            optionsShowing = false;
            pager.post(pager::requestFocusCurrent);
        });
    }

    private void openSettings() {
        startActivity(new Intent(this, LauncherSettingsActivity.class));
    }

    private void applyAppearance() {
        Appearance appearance = Appearance.load(this);
        backgroundView.apply(appearance);
        pager.setAccent(appearance.accent);
        pageDots.setAccent(appearance.accent);
        bottomBar.setAccent(appearance.accent);
    }

    private void updateEmptyState() {
        if (!apps.isEmpty()) {
            emptyState.setVisibility(View.GONE);
        } else if (!loaded) {
            emptyState.setText(R.string.loading_apps);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            emptyState.setText(R.string.no_apps);
            emptyState.setVisibility(View.VISIBLE);
        }
    }

    private static float safeAxis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        return Float.isNaN(value) ? 0f : value;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
