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
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Paged app grid. One page is materialised at a time; edge navigation, page
 * flings and the shoulder-button paging all go through {@link #changePage}.
 *
 * <p>DPAD focus is moved manually rather than relying on the framework focus
 * search: on a fixed 5x3 grid that gives deterministic row/column movement and
 * lets a press on the left/right edge turn the page, which is how the stock
 * launcher behaves.
 */
public final class AppPager extends FrameLayout {
    interface Listener {
        void onPageChanged(int page, int pageCount);

        void onFocusedAppChanged(AppEntry entry);

        void onAppActivated(AppEntry entry);

        void onAppOptions(AppEntry entry);
    }

    private final int columns;
    private final int rows;
    private final int perPage;
    private final GridPageView grid;
    private final GestureDetector gestures;

    private List<AppEntry> apps = Collections.emptyList();
    private Listener listener;
    private int page;
    private int pageCount = 1;
    private int lastFocusedIndex;
    private AppEntry focusedEntry;
    private int bindSequence;

    public AppPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        columns = Math.max(1, context.getResources().getInteger(R.integer.grid_columns));
        rows = Math.max(1, context.getResources().getInteger(R.integer.grid_rows));
        perPage = columns * rows;

        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);

        grid = new GridPageView(context, columns, rows);
        addView(grid, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent down, MotionEvent current,
                    float velocityX, float velocityY) {
                if (down == null || current == null) {
                    return false;
                }
                float dx = current.getX() - down.getX();
                if (Math.abs(dx) > dp(64) && Math.abs(velocityX) > dp(200)) {
                    changePage(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Replaces the app list, keeping the previously focused app if it still
     * exists (installs/uninstalls while the launcher is in front do not make
     * the grid jump).
     */
    void setApps(List<AppEntry> newApps) {
        apps = newApps == null ? Collections.emptyList() : newApps;
        pageCount = Math.max(1, (apps.size() + perPage - 1) / perPage);

        int targetPage = Math.min(page, pageCount - 1);
        int targetIndex = 0;
        if (focusedEntry != null) {
            int absolute = indexOfKey(focusedEntry.key());
            if (absolute >= 0) {
                targetPage = absolute / perPage;
                targetIndex = absolute % perPage;
            } else {
                targetIndex = Math.min(lastFocusedIndex, pageSize(targetPage) - 1);
            }
        }
        bindPage(targetPage, Math.max(0, targetIndex), false);
    }

    int getPage() {
        return page;
    }

    int getPageCount() {
        return pageCount;
    }

    AppEntry getFocusedEntry() {
        return focusedEntry;
    }

    boolean moveFocus(int direction) {
        if (grid.getTileCount() == 0) {
            return false;
        }
        int index = grid.indexOfFocused();
        if (index < 0) {
            index = Math.min(lastFocusedIndex, grid.getTileCount() - 1);
        }
        index = Math.max(0, Math.min(index, grid.getTileCount() - 1));

        int column = index % columns;
        int row = index / columns;
        int count = grid.getTileCount();

        switch (direction) {
            case View.FOCUS_LEFT:
                if (column > 0 && index - 1 >= 0) {
                    return focusTile(index - 1);
                }
                if (page > 0) {
                    int target = row * columns + (columns - 1);
                    return bindPage(page - 1, clampToPage(target, page - 1), true);
                }
                return false;

            case View.FOCUS_RIGHT:
                if (column < columns - 1 && index + 1 < count) {
                    return focusTile(index + 1);
                }
                if (page < pageCount - 1) {
                    int target = row * columns;
                    return bindPage(page + 1, clampToPage(target, page + 1), true);
                }
                return false;

            case View.FOCUS_UP:
                if (row > 0 && index - columns >= 0) {
                    return focusTile(index - columns);
                }
                return false;

            case View.FOCUS_DOWN:
                if (index + columns < count) {
                    return focusTile(index + columns);
                }
                return false;

            default:
                return false;
        }
    }

    boolean changePage(int delta) {
        int target = page + delta;
        if (target < 0 || target >= pageCount) {
            return false;
        }
        int index = grid.indexOfFocused();
        int row = index < 0 ? 0 : index / columns;
        int targetIndex = row * columns + (delta < 0 ? columns - 1 : 0);
        return bindPage(target, clampToPage(targetIndex, target), true);
    }

    void goToFirstPage() {
        bindPage(0, 0, false);
    }

    void requestFocusCurrent() {
        focusTile(Math.max(0, Math.min(lastFocusedIndex, grid.getTileCount() - 1)));
    }

    private boolean bindPage(int newPage, int focusIndex, boolean animate) {
        int oldPage = page;
        page = Math.max(0, Math.min(newPage, pageCount - 1));
        int start = page * perPage;
        int end = Math.min(start + perPage, apps.size());
        List<AppEntry> slice = start <= end
                ? new ArrayList<>(apps.subList(start, end))
                : Collections.emptyList();

        grid.setApps(slice);
        for (int i = 0; i < grid.getTileCount(); i++) {
            final AppTileView tile = grid.tileAt(i);
            final AppEntry entry = tile.getEntry();
            tile.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    lastFocusedIndex = grid.indexOfTile(tile);
                    focusedEntry = entry;
                    if (listener != null) {
                        listener.onFocusedAppChanged(entry);
                    }
                }
            });
            tile.setOnClickListener(view -> {
                view.requestFocus();
                if (listener != null) {
                    listener.onAppActivated(entry);
                }
            });
            tile.setOnLongClickListener(view -> {
                if (listener != null) {
                    listener.onAppOptions(entry);
                }
                return true;
            });
        }

        int index = Math.max(0, Math.min(focusIndex, Math.max(0, slice.size() - 1)));
        lastFocusedIndex = index;
        focusedEntry = slice.isEmpty() ? null : slice.get(index);

        if (animate && !slice.isEmpty()) {
            int direction = page < oldPage ? -1 : 1;
            grid.setAlpha(0f);
            grid.setTranslationX(dp(direction * 40));
            grid.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(150)
                    .start();
        } else {
            grid.setAlpha(1f);
            grid.setTranslationX(0f);
        }

        final int sequence = ++bindSequence;
        post(() -> {
            if (sequence == bindSequence) {
                focusTile(index);
            }
        });

        if (listener != null) {
            listener.onPageChanged(page, pageCount);
            listener.onFocusedAppChanged(focusedEntry);
        }
        return true;
    }

    private boolean focusTile(int index) {
        AppTileView tile = grid.tileAt(index);
        if (tile == null) {
            return false;
        }
        lastFocusedIndex = index;
        if (tile.requestFocus()) {
            focusedEntry = tile.getEntry();
            return true;
        }
        return false;
    }

    private int clampToPage(int index, int forPage) {
        return Math.max(0, Math.min(index, Math.max(0, pageSize(forPage) - 1)));
    }

    private int pageSize(int forPage) {
        if (forPage < 0 || forPage >= pageCount) {
            return 0;
        }
        int start = forPage * perPage;
        return Math.max(0, Math.min(perPage, apps.size() - start));
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < apps.size(); i++) {
            if (key.equals(apps.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
