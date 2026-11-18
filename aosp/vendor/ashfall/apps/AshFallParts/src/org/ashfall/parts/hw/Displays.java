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

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * Which display the external-display gamepad pointer may target (#131, #237).
 *
 * <p>This is the single gate for the pointer: the Fusion QS dialog uses it to
 * enable the switch, and {@code PointerService} uses it to switch the pointer
 * off when the display goes away.  It must answer "is a real, physical,
 * presentation-capable secondary display attached and awake?", not "is there
 * any other display?".
 *
 * <p>The virtual displays this app creates (the "Match screen" LED capture in
 * {@code ui/ScreenLedSync.java}: a private {@code TYPE_VIRTUAL} surface) and any
 * overlay or cast display are enumerated by {@link DisplayManager} as well.
 * Treating them as a target enabled the switch with only the internal panel
 * present, which consumed the pad's L1/L2/sticks with nothing to point at
 * (#237).
 *
 * <p>The predicate:
 * <ul>
 *   <li>not the default display (the internal panel is never a target);</li>
 *   <li>{@link Display#STATE_ON} - a connected but powered-off DP/HDMI sink has
 *       no cursor surface (the RP5's port 130 display idles in
 *       {@code STATE_OFF} when nothing is plugged in);</li>
 *   <li>{@link Display#FLAG_PRESENTATION} set and {@link Display#FLAG_PRIVATE}
 *       clear - a presentation display that is not private to an app.  Every
 *       app-created virtual display (including our own capture surface) is
 *       private, and {@code LocalDisplayAdapter} sets the presentation flag for
 *       every non-internal physical display (HDMI and USB-C DP alike);</li>
 *   <li>the platform display type is not {@code TYPE_VIRTUAL},
 *       {@code TYPE_OVERLAY} or {@code TYPE_WIFI} when it can be read.  The
 *       constants and {@code Display.getType()} are @removed from the API 36
 *       SDK stubs (both builds must compile, the product build uses
 *       {@code platform_apis}), so they are resolved reflectively once; if that
 *       fails, the two flag checks above decide instead.</li>
 * </ul>
 */
public final class Displays {

    private Displays() {
    }

    /** {@code Display.getType()}, or null when the platform does not expose it. */
    private static final Method GET_TYPE = lookup();

    /* Display.T* constants: reflectively where available, documented values
     * otherwise.  Values are stable ABI (TYPE_EXTERNAL = 2 ... TYPE_VIRTUAL =
     * 5) and are only a fallback. */
    private static final int TYPE_EXTERNAL = constant("TYPE_EXTERNAL", 2);
    private static final int TYPE_WIFI = constant("TYPE_WIFI", 3);
    private static final int TYPE_OVERLAY = constant("TYPE_OVERLAY", 4);
    private static final int TYPE_VIRTUAL = constant("TYPE_VIRTUAL", 5);

    /** True when a physical, awake presentation display is attached. */
    public static boolean hasExternal(Context context) {
        final DisplayManager manager = displayManager(context);
        if (manager == null) {
            return false;
        }
        for (Display display : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (isExternal(display)) {
                return true;
            }
        }
        return false;
    }

    /** The predicate for one display; see the class documentation. */
    public static boolean isExternal(Display display) {
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (display.getState() != Display.STATE_ON) {
            return false;
        }
        final int flags = display.getFlags();
        if ((flags & Display.FLAG_PRESENTATION) == 0) {
            return false;
        }
        if ((flags & Display.FLAG_PRIVATE) != 0) {
            return false;
        }
        /* Display.TYPE_UNKNOWN is 0; treat "cannot read the type" as unknown and
         * let the flags decide, but reject any known non-external type. */
        final int type = type(display);
        return type == 0 || type == TYPE_EXTERNAL;
    }

    private static int type(Display display) {
        if (GET_TYPE == null) {
            return 0;
        }
        try {
            final Object value = GET_TYPE.invoke(display);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Method lookup() {
        try {
            return Display.class.getMethod("getType");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int constant(String name, int fallback) {
        try {
            return Display.class.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static DisplayManager displayManager(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
