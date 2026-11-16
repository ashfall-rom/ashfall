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

import android.util.Log;

import org.ashfall.parts.ipc.Props;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Internal-display desktop mode (#131 F2).
 *
 * <p>The actual windowing mode of display 0 in {@code WindowManager} is the
 * source of truth: windowing mode 5 (freeform) means desktop mode, 1 means
 * fullscreen.  Reading it instead of the property keeps the page and the tile
 * correct when the display is switched from somewhere else, and it means the
 * UI never claims desktop mode while the panel is still fullscreen.
 *
 * <p>Entry applies the density first and the windowing mode second
 * ({@code docs/desktop-mode.md} §4), then writes
 * {@value #PROP_PAD_MODE}={@value #PAD_MODE_DESKTOP} for the input daemon.
 * Exit does the reverse: fullscreen first, then the stored density, then
 * {@value #PAD_MODE_GAME}.  The density in use before entry is kept in
 * {@value #PROP_DENSITY_RESTORE}.
 *
 * <p>The app is a platform-signed system app, but this class reaches the
 * hidden {@code IWindowManager} through reflection (the same pattern as
 * {@link Props}) so the Gradle preview build still compiles against the SDK:
 *
 * <ul>
 *   <li>{@code WindowManagerGlobal.getWindowManagerService()};</li>
 *   <li>{@code IWindowManager.getWindowingMode(int)} / {@code setWindowingMode(int,int)};</li>
 *   <li>{@code getBaseDisplayDensity(int)};</li>
 *   <li>{@code setForcedDisplayDensityForUser(int,int,int)} /
 *       {@code clearForcedDisplayDensityForUser(int,int)}.</li>
 * </ul>
 *
 * <p>{@code setWindowingMode} and {@code getWindowingMode} need
 * {@code INTERNAL_SYSTEM_WINDOW}; the density calls need
 * {@code WRITE_SECURE_SETTINGS}.  Both are declared in the manifest and are
 * granted by the platform signature.
 */
public final class Desktop {

    private static final String TAG = "AshFallParts";

    /** The daemon's mode property; the app/QS tile is the writer. */
    public static final String PROP_PAD_MODE = "persist.ashfall.pad.mode";
    /** The density in use before desktop mode was entered. */
    public static final String PROP_DENSITY_RESTORE = "persist.ashfall.display.density_restore";

    public static final String PAD_MODE_GAME = "game";
    public static final String PAD_MODE_DESKTOP = "desktop";

    /** Desktop windowing on the RP5 is readable at 960x540 dp. */
    public static final int DESKTOP_DENSITY = 320;

    /** display 0 is the internal panel; USB-C DP is out of scope in v1. */
    private static final int DISPLAY_ID = 0;

    /**
     * {@code UserHandle.USER_CURRENT}; the SDK does not expose the constant,
     * but it is the value {@code wm density} passes to the same binder call.
     */
    private static final int USER_CURRENT = -2;

    /** WindowManager's desktop/freeform display windowing mode. */
    public static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_UNDEFINED = 0;
    private static final int UNKNOWN = -1;

    private static volatile boolean sInitialised;
    private static volatile boolean sQueryFailed;
    private static Object sService;
    private static Method sGetWindowingMode;
    private static Method sSetWindowingMode;
    private static Method sGetBaseDisplayDensity;
    private static Method sSetForcedDisplayDensityForUser;
    private static Method sClearForcedDisplayDensityForUser;

    private Desktop() {
    }

    /**
     * The live display windowing mode, or {@code -1} when WindowManager cannot
     * be reached (preview build, a build without the permission, a dead
     * system_server).
     */
    public static int windowingMode() {
        if (!init()) {
            return UNKNOWN;
        }
        try {
            final Object value = call(sGetWindowingMode, DISPLAY_ID);
            return value instanceof Integer ? (Integer) value : UNKNOWN;
        } catch (Throwable t) {
            logQueryFailure(t);
            return UNKNOWN;
        }
    }

    /** True when display 0 is actually in desktop/freeform windowing. */
    public static boolean isDesktop() {
        return windowingMode() == WINDOWING_MODE_FREEFORM;
    }

    /**
     * Switches display 0 to desktop windowing at {@link #DESKTOP_DENSITY}.
     *
     * @return {@code true} only when the display is in desktop mode and
     *         {@code persist.ashfall.pad.mode} reads back {@code desktop};
     *         on failure the density, the display and the property are put
     *         back the way they were.
     */
    public static boolean enter() {
        final int mode = windowingMode();
        if (mode == UNKNOWN) {
            Log.w(TAG, "desktop: cannot read the display mode, not entering");
            return false;
        }
        if (mode == WINDOWING_MODE_FREEFORM) {
            // Already a desk (for example switched from Settings): only make
            // sure the daemon follows it.  Do not touch the density, it is
            // already whatever put the display in this state.
            return setPadMode(PAD_MODE_DESKTOP);
        }

        final int previousDensity = baseDensity();
        if (previousDensity <= 0) {
            // Entering without a restorable density would leave the panel at
            // 320 dpi for good; refuse instead.
            Log.w(TAG, "desktop: cannot read the display density, not entering");
            return false;
        }
        // If a reboot left 320 dpi on a fullscreen panel, the key still holds
        // the density to go back to; keep that value instead of overwriting it
        // with the stale desktop value.
        final int staleRestore = previousDensity == DESKTOP_DENSITY
                ? Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN) : UNKNOWN;
        final int restoreValue = staleRestore > 0 ? staleRestore : previousDensity;
        if (!setRestoreDensity(restoreValue)) {
            Log.w(TAG, "desktop: cannot store the restore density, not entering");
            return false;
        }

        final boolean densityChanged = previousDensity != DESKTOP_DENSITY;
        if (densityChanged && !setDensity(DESKTOP_DENSITY)) {
            restoreDensity(previousDensity);
            return false;
        }

        if (!setWindowingMode(WINDOWING_MODE_FREEFORM)
                || windowingMode() != WINDOWING_MODE_FREEFORM) {
            Log.w(TAG, "desktop: display 0 did not switch to freeform, rolling back");
            // Put the display back first: a failed read-back can still follow
            // a partially applied transition, and pad.mode=desktop with a
            // fullscreen panel is the one state this feature must never leave.
            setWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (densityChanged) {
                restoreDensity(previousDensity);
            }
            setPadMode(PAD_MODE_GAME);
            return false;
        }

        if (!setPadMode(PAD_MODE_DESKTOP)) {
            Log.w(TAG, "desktop: pad mode did not stick, rolling the display back");
            setWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (densityChanged) {
                restoreDensity(previousDensity);
            }
            // The write may have landed even though the read-back did not.
            setPadMode(PAD_MODE_GAME);
            return false;
        }
        return true;
    }

    /**
     * Returns display 0 to fullscreen, restores the density used before entry
     * and sets {@code persist.ashfall.pad.mode=game}.
     *
     * @return {@code false} when the display or the property could not be
     *         switched; the display is left in whatever mode it really reached.
     */
    public static boolean exit() {
        final int mode = windowingMode();
        if (mode == UNKNOWN) {
            Log.w(TAG, "desktop: cannot read the display mode, not exiting");
            return false;
        }
        final boolean wasDesktop = mode == WINDOWING_MODE_FREEFORM;

        if (mode != WINDOWING_MODE_FULLSCREEN) {
            if (!setWindowingMode(WINDOWING_MODE_FULLSCREEN)) {
                Log.w(TAG, "desktop: display 0 did not return to fullscreen");
                return false;
            }
            final int after = windowingMode();
            // 0 ("undefined") also means the desk is gone; only a read-back
            // of freeform or an unreadable mode means the desk is still there.
            if (after != WINDOWING_MODE_FULLSCREEN && after != WINDOWING_MODE_UNDEFINED) {
                Log.w(TAG, "desktop: display 0 did not return to fullscreen");
                return false;
            }
        }

        // Undo the density when coming out of a real desk, and also when a
        // reboot left the panel at 320 dpi with a fullscreen display but the
        // restore key still present.  Doing nothing when the density is not
        // 320 keeps a user's own override safe.
        restoreStoredDensity(wasDesktop);
        return setPadMode(PAD_MODE_GAME);
    }

    private static int baseDensity() {
        if (!init()) {
            return UNKNOWN;
        }
        try {
            final Object value = call(sGetBaseDisplayDensity, DISPLAY_ID);
            return value instanceof Integer ? (Integer) value : UNKNOWN;
        } catch (Throwable t) {
            Log.w(TAG, "desktop: cannot read the display density", t);
            return UNKNOWN;
        }
    }

    private static boolean setDensity(int density) {
        if (!init() || density <= 0) {
            return false;
        }
        try {
            call(sSetForcedDisplayDensityForUser, DISPLAY_ID, density, USER_CURRENT);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "desktop: cannot set the display density", t);
            return false;
        }
    }

    private static void clearDensity() {
        if (!init()) {
            return;
        }
        try {
            call(sClearForcedDisplayDensityForUser, DISPLAY_ID, USER_CURRENT);
        } catch (Throwable t) {
            Log.w(TAG, "desktop: cannot reset the display density", t);
        }
    }

    private static boolean setRestoreDensity(int density) {
        if (!Props.setInt(PROP_DENSITY_RESTORE, density)) {
            return false;
        }
        return Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN) == density;
    }

    private static void restoreDensity(int density) {
        if (density > 0 && baseDensity() != density) {
            setDensity(density);
        }
    }

    private static void restoreStoredDensity(boolean wasDesktop) {
        final int stored = Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN);
        if (stored > 0) {
            // Only act on the value this feature applies; a 360 dpi panel is
            // already restored, and a manual override must be left alone.
            if (baseDensity() == DESKTOP_DENSITY && stored != DESKTOP_DENSITY) {
                setDensity(stored);
            }
        } else if (wasDesktop) {
            // No stored value means the display was put into desktop mode by
            // something other than this page/tile.  320 is the only value this
            // feature applies, so clearing it is the safe way back.
            clearDensity();
        }
    }

    private static boolean setWindowingMode(int mode) {
        if (!init()) {
            return false;
        }
        try {
            call(sSetWindowingMode, DISPLAY_ID, mode);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "desktop: cannot set the display windowing mode", t);
            return false;
        }
    }

    private static boolean setPadMode(String mode) {
        if (!Props.set(PROP_PAD_MODE, mode)) {
            return false;
        }
        return mode.equals(Props.get(PROP_PAD_MODE, ""));
    }

    private static synchronized boolean init() {
        if (sInitialised) {
            return sService != null;
        }
        sInitialised = true;
        try {
            final Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            final Object service = global.getMethod("getWindowManagerService").invoke(null);
            if (service == null) {
                Log.w(TAG, "WindowManager is not running, desktop mode is unavailable");
                return false;
            }
            final Class<?> iface = Class.forName("android.view.IWindowManager");
            sGetWindowingMode = iface.getMethod("getWindowingMode", int.class);
            sSetWindowingMode = iface.getMethod("setWindowingMode", int.class, int.class);
            sGetBaseDisplayDensity = iface.getMethod("getBaseDisplayDensity", int.class);
            sSetForcedDisplayDensityForUser = iface.getMethod(
                    "setForcedDisplayDensityForUser", int.class, int.class, int.class);
            sClearForcedDisplayDensityForUser = iface.getMethod(
                    "clearForcedDisplayDensityForUser", int.class, int.class);
            sService = service;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WindowManager hidden API is unavailable, desktop mode is off", t);
            return false;
        }
    }

    private static Object call(Method method, Object... args) throws Throwable {
        try {
            return method.invoke(sService, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    private static void logQueryFailure(Throwable t) {
        if (!sQueryFailed) {
            sQueryFailed = true;
            Log.w(TAG, "desktop: cannot read the display mode", t);
        }
    }
}
