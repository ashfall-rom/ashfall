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

import android.os.SystemClock;
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
 *   <li>{@code getBaseDisplayDensity(int)} / {@code getInitialDisplayDensity(int)};</li>
 *   <li>{@code setForcedDisplayDensityForUser(int,int,int)} /
 *       {@code clearForcedDisplayDensityForUser(int,int)}.</li>
 * </ul>
 *
 * <p>{@code setWindowingMode} and {@code getWindowingMode} need
 * {@code INTERNAL_SYSTEM_WINDOW}; the density calls need
 * {@code WRITE_SECURE_SETTINGS}.  Both are declared in the manifest and are
 * granted by the platform signature.  {@link #resetForBoot()} is called by
 * {@code DesktopBootReceiver} so a crash cannot leave a persisted desk behind
 * (#207).
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

    /** How long a failed WindowManager lookup waits before retrying (#222). */
    private static final long INIT_RETRY_MS = 2000;

    /**
     * WindowManager lookup state: {@code NEW} -> {@code READY} on success, or
     * {@code RETRY_WAIT} after a failure until {@link #sInitRetryAtMs}.  A
     * failed lookup is never permanent, so a later page/tile tick or the
     * second boot broadcast can recover (#222).
     */
    private enum InitState { NEW, READY, RETRY_WAIT }

    private static volatile InitState sInitState = InitState.NEW;
    private static volatile long sInitRetryAtMs;
    private static volatile boolean sQueryFailed;
    private static Object sService;
    private static Method sGetWindowingMode;
    private static Method sSetWindowingMode;
    private static Method sGetBaseDisplayDensity;
    private static Method sGetInitialDisplayDensity;
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
     * Boot guard (#207): display 0 fullscreen, the saved density back, and
     * {@code persist.ashfall.pad.mode=game}.
     *
     * <p>WindowManager persists the windowing mode and the density override
     * (F1 §2.1), so a kill between {@link #enter()} and {@link #exit()} would
     * otherwise boot straight back into a desk.  Every call is idempotent and
     * the receiver handles both boot broadcasts, so a retry cannot loop.
     */
    public static void resetForBoot() {
        setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        if (!restoreDensityForBoot()) {
            Log.w(TAG, "desktop: boot guard could not restore the density; next boot retries");
        }
        if (!setPadMode(PAD_MODE_GAME)) {
            Log.w(TAG, "desktop: boot guard could not reset pad mode to game");
        }
    }

    /**
     * Boot density half of the guard.
     *
     * <p>Only a panel that is still at {@link #DESKTOP_DENSITY} is ours.  A key
     * found there is applied and cleared; 320 with no key is the F1 Step 0 /
     * outside-F2 state and is reset to the panel default.  Any other density is
     * a user override and is not touched; a stale key is dropped so it cannot
     * be applied on a later boot (#216).
     */
    private static boolean restoreDensityForBoot() {
        final int stored = Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN);
        final int base = baseDensity();
        if (base == UNKNOWN) {
            // Cannot tell whose density this is; keep the key for the next boot
            // and do not touch what may be a manual override.
            return true;
        }
        if (base == DESKTOP_DENSITY) {
            if (stored > 0) {
                if (!applyStoredDensity(stored)) {
                    return false;
                }
                return clearRestoreDensity();
            }
            return clearDensityVerified();
        }
        // Not our density: leave the user's override alone, but drop a stale
        // key so it can never be applied later.
        return stored <= 0 || clearRestoreDensity();
    }

    /** Result of an enter/exit request, so the page can report a partial failure. */
    public enum Result {
        /** The requested state is applied and verified. */
        OK,
        /** Nothing changed: every step was rolled back. */
        FAILED,
        /** The display changed but a density or property restore did not complete. */
        PARTIAL
    }

    /**
     * Switches display 0 to desktop windowing at {@link #DESKTOP_DENSITY}.
     *
     * @return {@link Result#OK} only when the display is in desktop mode and
     *         {@code persist.ashfall.pad.mode} reads back {@code desktop};
     *         {@link Result#FAILED} when everything was rolled back;
     *         {@link Result#PARTIAL} when the display changed but the density
     *         or property could not be put back (the caller must report it).
     */
    public static Result enter() {
        final int mode = windowingMode();
        if (mode == UNKNOWN) {
            Log.w(TAG, "desktop: cannot read the display mode, not entering");
            return Result.FAILED;
        }
        if (mode == WINDOWING_MODE_FREEFORM) {
            // Already a desk (for example switched from Settings): only make
            // sure the daemon follows it.  Do not touch the density, it is
            // already whatever put the display in this state.
            return setPadMode(PAD_MODE_DESKTOP) ? Result.OK : Result.FAILED;
        }

        final int previousDensity = baseDensity();
        if (previousDensity <= 0) {
            // Entering without a restorable density would leave the panel at
            // 320 dpi for good; refuse instead.
            Log.w(TAG, "desktop: cannot read the display density, not entering");
            return Result.FAILED;
        }
        // If a reboot left 320 dpi on a fullscreen panel, the key still holds
        // the density to go back to; keep that value instead of overwriting it
        // with the stale desktop value.
        final int staleRestore = previousDensity == DESKTOP_DENSITY
                ? Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN) : UNKNOWN;
        final int restoreValue = staleRestore > 0 ? staleRestore : previousDensity;
        if (!setRestoreDensity(restoreValue)) {
            Log.w(TAG, "desktop: cannot store the restore density, not entering");
            return Result.FAILED;
        }

        final boolean densityChanged = previousDensity != DESKTOP_DENSITY;
        if (densityChanged && !setDensityVerified(DESKTOP_DENSITY)) {
            return rollback(previousDensity, true);
        }

        if (!setWindowingMode(WINDOWING_MODE_FREEFORM)
                || windowingMode() != WINDOWING_MODE_FREEFORM) {
            Log.w(TAG, "desktop: display 0 did not switch to freeform, rolling back");
            return rollback(previousDensity, densityChanged);
        }

        if (!setPadMode(PAD_MODE_DESKTOP)) {
            Log.w(TAG, "desktop: pad mode did not stick, rolling the display back");
            return rollback(previousDensity, densityChanged);
        }
        return Result.OK;
    }

    /**
     * Returns display 0 to fullscreen, restores the density used before entry
     * and sets {@code persist.ashfall.pad.mode=game}.
     *
     * @return {@link Result#OK} only when the display, density and property all
     *         read back as requested; {@link Result#PARTIAL} when the display
     *         changed but the density or property restore did not complete.
     */
    public static Result exit() {
        final int mode = windowingMode();
        if (mode == UNKNOWN) {
            Log.w(TAG, "desktop: cannot read the display mode, not exiting");
            return Result.FAILED;
        }
        final boolean wasDesktop = mode == WINDOWING_MODE_FREEFORM;

        if (mode != WINDOWING_MODE_FULLSCREEN) {
            if (!setWindowingMode(WINDOWING_MODE_FULLSCREEN)) {
                Log.w(TAG, "desktop: display 0 did not return to fullscreen");
                return Result.FAILED;
            }
            final int after = windowingMode();
            // 0 ("undefined") also means the desk is gone; only a read-back
            // of freeform or an unreadable mode means the desk is still there.
            if (after != WINDOWING_MODE_FULLSCREEN && after != WINDOWING_MODE_UNDEFINED) {
                Log.w(TAG, "desktop: display 0 did not return to fullscreen");
                return Result.FAILED;
            }
        }

        // Undo the density when coming out of a real desk, and also when a
        // reboot left the panel at 320 dpi with a fullscreen display but the
        // restore key still present.  Doing nothing when the density is not
        // 320 keeps a user's own override safe.
        final boolean densityOk = restoreStoredDensity(wasDesktop);
        final boolean padOk = setPadMode(PAD_MODE_GAME);
        return densityOk && padOk ? Result.OK : Result.PARTIAL;
    }

    /** Rolls back the display and density after a failed entry. */
    private static Result rollback(int previousDensity, boolean densityChanged) {
        // Put the display back first: a failed read-back can still follow a
        // partially applied transition, and pad.mode=desktop with a fullscreen
        // panel is the one state this feature must never leave.
        final boolean displayOk = returnToFullscreen();
        final boolean densityOk = densityChanged
                ? applyStoredDensity(previousDensity) && clearRestoreDensity()
                : clearRestoreDensity();
        // The write may have landed even though the read-back did not.
        final boolean padOk = setPadMode(PAD_MODE_GAME);
        return displayOk && densityOk && padOk ? Result.FAILED : Result.PARTIAL;
    }

    private static boolean returnToFullscreen() {
        if (!setWindowingMode(WINDOWING_MODE_FULLSCREEN)) {
            return false;
        }
        final int after = windowingMode();
        return after == WINDOWING_MODE_FULLSCREEN || after == WINDOWING_MODE_UNDEFINED;
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

    /** The stock density, used to turn "restore the stock value" into a reset. */
    private static int initialDensity() {
        if (!init()) {
            return UNKNOWN;
        }
        try {
            final Object value = call(sGetInitialDisplayDensity, DISPLAY_ID);
            return value instanceof Integer ? (Integer) value : UNKNOWN;
        } catch (Throwable t) {
            Log.w(TAG, "desktop: cannot read the initial display density", t);
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

    private static boolean clearDensity() {
        if (!init()) {
            return false;
        }
        try {
            call(sClearForcedDisplayDensityForUser, DISPLAY_ID, USER_CURRENT);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "desktop: cannot reset the display density", t);
            return false;
        }
    }

    /** Sets the density and verifies the read-back, retrying once (#210). */
    private static boolean setDensityVerified(int density) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (setDensity(density) && baseDensity() == density) {
                return true;
            }
        }
        return false;
    }

    /** Clears the override and verifies that the panel is back at the stock density. */
    private static boolean clearDensityVerified() {
        final int initial = initialDensity();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!clearDensity()) {
                continue;
            }
            final int after = baseDensity();
            if (after > 0 && (initial <= 0 ? after != DESKTOP_DENSITY : after == initial)) {
                return true;
            }
        }
        return false;
    }

    private static boolean setRestoreDensity(int density) {
        if (!Props.setInt(PROP_DENSITY_RESTORE, density)) {
            return false;
        }
        return Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN) == density;
    }

    /**
     * Applies the density saved before entry, and verifies it.
     *
     * @return {@code false} when the panel could not be confirmed at the saved
     *         value; the caller reports the partial state (#210).
     */
    private static boolean applyStoredDensity(int stored) {
        if (stored <= 0) {
            return false;
        }
        final int initial = initialDensity();
        if (initial > 0 && stored == initial) {
            // The previous value was the stock density: use reset rather than
            // leaving an explicit "Override density: 360" behind.
            return clearDensityVerified();
        }
        return setDensityVerified(stored);
    }

    private static boolean restoreStoredDensity(boolean wasDesktop) {
        final int stored = Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN);
        final int base = baseDensity();
        if (base == UNKNOWN) {
            // Cannot tell; keep the key so a later tap or boot can recover.
            return true;
        }
        if (base == DESKTOP_DENSITY) {
            if (stored > 0) {
                if (!applyStoredDensity(stored)) {
                    return false;
                }
                return clearRestoreDensity();
            }
            if (wasDesktop) {
                // No stored value means the display was put into desktop mode
                // by something other than this page/tile.  320 is the only
                // value this feature applies, so clearing it is the safe way
                // back.
                return clearDensityVerified();
            }
            // A manual 320 on a fullscreen panel is not ours: leave it.
            return true;
        }
        // Not our density: keep the user's override and drop any stale key.
        return stored <= 0 || clearRestoreDensity();
    }

    /** Clears the key after a verified restore, so it cannot outlive the desk. */
    private static boolean clearRestoreDensity() {
        if (!Props.setInt(PROP_DENSITY_RESTORE, 0)) {
            return false;
        }
        return Props.getInt(PROP_DENSITY_RESTORE, UNKNOWN) == 0;
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

    /**
     * Lazily resolves the hidden WindowManager handle.
     *
     * <p>States: {@code NEW} attempts immediately; a failure moves to {@code
     * RETRY_WAIT} for {@link #INIT_RETRY_MS}; an expired retry attempts again
     * and either becomes {@code READY} or re-arms the wait.  A failed lookup
     * is therefore never latched, which is what lets the second boot broadcast
     * and the page/tile ticks recover (#222).
     */
    private static synchronized boolean init() {
        final long now = SystemClock.elapsedRealtime();
        if (sInitState == InitState.READY) {
            return sService != null;
        }
        if (sInitState == InitState.RETRY_WAIT && now < sInitRetryAtMs) {
            return false;
        }
        final boolean logFailure = sInitState != InitState.RETRY_WAIT;
        try {
            final Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            final Object service = global.getMethod("getWindowManagerService").invoke(null);
            if (service == null) {
                if (logFailure) {
                    Log.w(TAG, "WindowManager is not running, desktop mode is unavailable");
                }
                sInitState = InitState.RETRY_WAIT;
                sInitRetryAtMs = now + INIT_RETRY_MS;
                return false;
            }
            final Class<?> iface = Class.forName("android.view.IWindowManager");
            sGetWindowingMode = iface.getMethod("getWindowingMode", int.class);
            sSetWindowingMode = iface.getMethod("setWindowingMode", int.class, int.class);
            sGetBaseDisplayDensity = iface.getMethod("getBaseDisplayDensity", int.class);
            sGetInitialDisplayDensity = iface.getMethod(
                    "getInitialDisplayDensity", int.class);
            sSetForcedDisplayDensityForUser = iface.getMethod(
                    "setForcedDisplayDensityForUser", int.class, int.class, int.class);
            sClearForcedDisplayDensityForUser = iface.getMethod(
                    "clearForcedDisplayDensityForUser", int.class, int.class);
            sService = service;
            sQueryFailed = false;
            sInitState = InitState.READY;
            sInitRetryAtMs = 0;
            return true;
        } catch (Throwable t) {
            if (logFailure) {
                Log.w(TAG, "WindowManager hidden API is unavailable, desktop mode is off", t);
            }
            sInitState = InitState.RETRY_WAIT;
            sInitRetryAtMs = now + INIT_RETRY_MS;
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
