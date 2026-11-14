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
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed 5x3 page of app tiles. A custom ViewGroup (instead of RecyclerView or
 * GridLayout) keeps the page's focus order explicit and the APK free of
 * AndroidX dependencies; a page is rebuilt on page change, which is cheaper
 * than keeping every page inflated.
 */
final class GridPageView extends ViewGroup {
    private final int columns;
    private final int rows;
    private final int gap;
    private final List<AppTileView> tiles = new ArrayList<>();

    GridPageView(Context context, int columns, int rows) {
        super(context);
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        this.gap = context.getResources().getDimensionPixelSize(R.dimen.grid_gap);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    void setApps(List<AppEntry> apps) {
        removeAllViews();
        tiles.clear();
        for (AppEntry app : apps) {
            AppTileView tile = new AppTileView(getContext(), app);
            tiles.add(tile);
            addView(tile);
        }
    }

    int getTileCount() {
        return tiles.size();
    }

    AppTileView tileAt(int index) {
        if (index < 0 || index >= tiles.size()) {
            return null;
        }
        return tiles.get(index);
    }

    int indexOfFocused() {
        View focused = findFocus();
        return focused == null ? -1 : tiles.indexOf(focused);
    }

    int indexOfTile(View tile) {
        return tiles.indexOf(tile);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);

        int usableWidth = width - getPaddingLeft() - getPaddingRight();
        int usableHeight = height - getPaddingTop() - getPaddingBottom();
        int cellWidth = Math.max(0, usableWidth / columns);
        int cellHeight = Math.max(0, usableHeight / rows);

        int childWidth = Math.max(0, cellWidth - gap);
        int childHeight = Math.max(0, cellHeight - gap);
        int exactWidth = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY);
        int exactHeight = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY);

        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(exactWidth, exactHeight);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int usableWidth = right - left - getPaddingLeft() - getPaddingRight();
        int usableHeight = bottom - top - getPaddingTop() - getPaddingBottom();
        int cellWidth = Math.max(0, usableWidth / columns);
        int cellHeight = Math.max(0, usableHeight / rows);

        for (int i = 0; i < getChildCount(); i++) {
            int column = i % columns;
            int row = i / columns;
            int cellLeft = getPaddingLeft() + column * cellWidth;
            int cellTop = getPaddingTop() + row * cellHeight;
            View child = getChildAt(i);
            int halfGap = gap / 2;
            child.layout(
                    cellLeft + halfGap,
                    cellTop + halfGap,
                    cellLeft + cellWidth - halfGap,
                    cellTop + cellHeight - halfGap);
        }
    }
}
