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

package org.ashfall.parts.ui;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Keeps a draggable control out of the system back/recents gesture strips.
 *
 * <p>{@link View#setSystemGestureExclusionRects} (API 29+) takes its rects in
 * the view's own post-layout coordinates and the framework maps them into the
 * window, so the rects have to be recomputed whenever the view is laid out,
 * resized or scrolled, and kept clear of the parts of the window the user must
 * still be able to gesture in.  {@link #attach} installs the layout, attach and
 * scroll listeners that do that, and publishes only the part of the control's
 * touchable areas that overlaps an edge strip: the rest of the window keeps the
 * normal system gestures.
 *
 * <p>The exclusion is a no-op below API 29.
 */
public final class GestureExclusion implements View.OnLayoutChangeListener,
        View.OnAttachStateChangeListener, ViewTreeObserver.OnScrollChangedListener {

    /** Supplies the draggable areas of the view, in the view's own pixels. */
    public interface TouchArea {
        void touchAreas(@NonNull View view, @NonNull List<Rect> out);
    }

    /** Claim the left window edge (back/recents gesture on that side). */
    public static final int EDGE_LEFT = 1;
    /** Claim the right window edge. */
    public static final int EDGE_RIGHT = 1 << 1;

    /** How much of each window edge is claimed while a drag is possible. */
    public static final int EDGE_BAND_DP = 48;

    /** How far a draggable handle is kept away from the very edge. */
    public static final int EDGE_INSET_DP = 12;

    /** What the platform honours per edge; anything beyond it is ignored. */
    public static final int EDGE_BUDGET_DP = 200;

    private static final String TAG = "AshFallGesture";

    /**
     * One exclusion per view, so a rebound/recycled preference row reuses its
     * listeners.  The value is weak as well: the helper is owned by the view's
     * own listener lists, and a strong value would keep the key alive.
     */
    private static final WeakHashMap<View, WeakReference<GestureExclusion>> ATTACHED =
            new WeakHashMap<>();

    private final WeakReference<View> mView;
    private final Rect mArea = new Rect();
    private final Rect mWhole = new Rect();
    private final List<Rect> mLocal = new ArrayList<>(2);
    private final List<Rect> mLeft = new ArrayList<>(2);
    private final List<Rect> mRight = new ArrayList<>(2);
    private final List<Rect> mPending = new ArrayList<>(2);
    private final List<Rect> mPublished = new ArrayList<>(2);
    private final int[] mLocation = new int[2];

    private int mEdges = EDGE_LEFT | EDGE_RIGHT;
    @Nullable
    private TouchArea mTouchArea;

    private GestureExclusion(View view) {
        mView = new WeakReference<>(view);
    }

    /** Attaches to the whole view; repeated calls reuse the same listener set. */
    @NonNull
    public static GestureExclusion attach(@NonNull View view, int edges) {
        return attach(view, edges, null);
    }

    /**
     * Attaches to the view's draggable areas (for example the curve points of
     * the curve editor) instead of the whole view.
     */
    @NonNull
    public static GestureExclusion attach(@NonNull View view, int edges,
            @Nullable TouchArea touchArea) {
        final GestureExclusion exclusion;
        synchronized (ATTACHED) {
            final WeakReference<GestureExclusion> ref = ATTACHED.get(view);
            GestureExclusion existing = ref != null ? ref.get() : null;
            if (existing == null) {
                existing = new GestureExclusion(view);
                ATTACHED.put(view, new WeakReference<>(existing));
                existing.listen(view);
            }
            existing.mEdges = edges;
            existing.mTouchArea = touchArea;
            exclusion = existing;
        }
        exclusion.refresh();
        return exclusion;
    }

    /** Whether the platform has system gesture exclusion at all. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** The inset, in pixels, that keeps a handle out of the gesture strip. */
    public static int insetPx(@NonNull Context context) {
        return Ui.dp(context, EDGE_INSET_DP);
    }

    /** Recomputes the rects, for a caller whose draggable areas just moved. */
    public void refresh() {
        update();
    }

    private void listen(View view) {
        view.addOnLayoutChangeListener(this);
        view.addOnAttachStateChangeListener(this);
        if (view.isAttachedToWindow()) {
            addScrollListener(view);
        }
    }

    private void addScrollListener(View view) {
        final ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnScrollChangedListener(this);
            observer.addOnScrollChangedListener(this);
        }
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        addScrollListener(view);
        update();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        final ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnScrollChangedListener(this);
        }
        mPending.clear();
        publish();
    }

    @Override
    public void onLayoutChange(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        update();
    }

    @Override
    public void onScrollChanged() {
        update();
    }

    private void update() {
        final View view = mView.get();
        if (view == null) {
            return;
        }
        mPending.clear();
        if (!isSupported() || mEdges == 0 || !view.isAttachedToWindow()) {
            publish();
            return;
        }
        final View root = view.getRootView();
        final int width = root.getWidth();
        final int height = root.getHeight();
        if (width <= 0 || height <= 0) {
            publish();
            return;
        }

        mLocal.clear();
        if (mTouchArea != null) {
            mTouchArea.touchAreas(view, mLocal);
        } else {
            mWhole.set(0, 0, view.getWidth(), view.getHeight());
            mLocal.add(mWhole);
        }
        view.getLocationInWindow(mLocation);

        final int band = Ui.dp(view.getContext(), EDGE_BAND_DP);
        mLeft.clear();
        mRight.clear();
        for (int i = 0; i < mLocal.size(); i++) {
            mArea.set(mLocal.get(i));
            if (mArea.isEmpty()) {
                continue;
            }
            mArea.offset(mLocation[0], mLocation[1]);
            if (!mArea.intersect(0, 0, width, height)) {
                continue;
            }
            if ((mEdges & EDGE_LEFT) != 0) {
                addToBand(mArea, 0, Math.min(band, width), mLeft);
            }
            if ((mEdges & EDGE_RIGHT) != 0) {
                addToBand(mArea, Math.max(0, width - band), width, mRight);
            }
        }

        final int budget = Ui.dp(view.getContext(), EDGE_BUDGET_DP);
        appendWithinBudget(mLeft, budget, mPending);
        appendWithinBudget(mRight, budget, mPending);
        // The API takes the view's own post-layout coordinates and the
        // framework maps them into the window (ViewRootRectTracker), so undo
        // the offset that was only needed to find the window edges.
        for (int i = 0; i < mPending.size(); i++) {
            mPending.get(i).offset(-mLocation[0], -mLocation[1]);
        }
        publish();
    }

    /** Clips {@code area} to one edge band and merges it with what is there. */
    private static void addToBand(Rect area, int bandLeft, int bandRight, List<Rect> out) {
        final Rect clipped = new Rect(area);
        if (!clipped.intersect(bandLeft, area.top, bandRight, area.bottom)) {
            return;
        }
        for (int i = 0; i < out.size(); i++) {
            final Rect existing = out.get(i);
            if (!Rect.intersects(existing, clipped)) {
                continue;
            }
            existing.union(clipped);
            // The union may have grown into another rect of the same band.
            for (int j = out.size() - 1; j >= 0; j--) {
                if (j == i) {
                    continue;
                }
                final Rect other = out.get(j);
                if (Rect.intersects(existing, other)) {
                    existing.union(other);
                    out.remove(j);
                    if (j < i) {
                        i--;
                    }
                }
            }
            return;
        }
        out.add(clipped);
    }

    /**
     * Copies the rects of one edge into {@code out} while they fit the
     * platform's per-edge budget.  The platform honours the bottom of the
     * screen first, so the same order is used here: the request is never
     * silently dropped.
     */
    private static void appendWithinBudget(List<Rect> edgeRects, int budget, List<Rect> out) {
        if (edgeRects.isEmpty()) {
            return;
        }
        edgeRects.sort((a, b) -> Integer.compare(b.bottom, a.bottom));
        int used = 0;
        for (int i = 0; i < edgeRects.size(); i++) {
            final Rect rect = edgeRects.get(i);
            final int room = budget - used;
            if (room <= 0) {
                return;
            }
            if (rect.height() > room) {
                out.add(new Rect(rect.left, rect.bottom - room, rect.right, rect.bottom));
                return;
            }
            out.add(rect);
            used += rect.height();
        }
    }

    private void publish() {
        final View view = mView.get();
        if (view == null || sameAsPublished()) {
            return;
        }
        mPublished.clear();
        for (int i = 0; i < mPending.size(); i++) {
            mPublished.add(new Rect(mPending.get(i)));
        }
        if (isSupported() && view.isAttachedToWindow()) {
            // The view keeps the list it is given, so hand it its own copy.
            view.setSystemGestureExclusionRects(new ArrayList<>(mPublished));
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, view.getClass().getSimpleName() + " edges=" + mEdges
                    + " rects=" + mPublished);
        }
    }

    private boolean sameAsPublished() {
        if (mPending.size() != mPublished.size()) {
            return false;
        }
        for (int i = 0; i < mPending.size(); i++) {
            if (!mPending.get(i).equals(mPublished.get(i))) {
                return false;
            }
        }
        return true;
    }
}
