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
 * off when the display goes away.  It must answer "is a physical secondary
 * display attached and awake?", not "is there any other display?".
 *
 * <p>The predicate:
 * <ul>
 *   <li>not the default display (the internal panel is never a target);</li>
 *   <li>{@link Display#STATE_ON} - a connected but powered-off DP/HDMI sink has
 *       no cursor surface (the RP5's port-130 display idles in
 *       {@code STATE_OFF} when nothing is plugged in);</li>
 *   <li>{@link Display#FLAG_PRIVATE} clear - every app-created virtual display
 *       (including the *Match screen* capture surface in
 *       {@code ui/ScreenLedSync.java}) and every overlay display is private to
 *       its creator.  This is what keeps #237 fixed: the LED capture display is
 *       a private {@code TYPE_VIRTUAL} surface and no longer enables the
 *       switch;</li>
 *   <li>the platform type is not {@code TYPE_VIRTUAL}, {@code TYPE_OVERLAY} or
 *       {@code TYPE_WIFI}, when it can be read.  {@code TYPE_UNKNOWN} is
 *       accepted: the type is advisory here, not a requirement (see below).
 *       The constants and {@code Display.getType()} are @removed from the API 36
 *       SDK stubs (both builds must compile, the product build uses
 *       {@code platform_apis}), so they are resolved reflectively once; if that
 *       fails, the flags above decide instead.</li>
 * </ul>
 *
 * <p><b>Why there is no {@code FLAG_PRESENTATION} / {@code TYPE_EXTERNAL}
 * requirement</b> (device evidence, #237): the RP5's secondary display is
 * displayId 2, 1280x720, port 130, and {@code dumpsys display} reports
 * {@code type INTERNAL} with
 * {@code FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY|FLAG_ROTATES_WITH_CONTENT|
 * FLAG_SECURE|FLAG_SUPPORTS_PROTECTED_BUFFERS|FLAG_TRUSTED} - no
 * {@code FLAG_PRESENTATION} and not {@code TYPE_EXTERNAL}.  The vendor display
 * config declares the DP sink as an internal port, so an AOSP-shaped
 * "external presentation display" test would disable the pointer on the one
 * device it exists for.  The DP sink is therefore identified by "a second,
 * awake, non-private, non-virtual display", and the strict virtual/overlay
 * exclusion is kept.
 */
public final class Displays {

    private Displays() {
    }

    /** {@code Display.getType()}, or null when the platform does not expose it. */
    private static final Method GET_TYPE = lookup();

    /* Display.T* constants: reflectively where available, documented values
     * otherwise.  Values are stable ABI (TYPE_WIFI = 3, TYPE_OVERLAY = 4,
     * TYPE_VIRTUAL = 5). */
    private static final int TYPE_WIFI = constant("TYPE_WIFI", 3);
    private static final int TYPE_OVERLAY = constant("TYPE_OVERLAY", 4);
    private static final int TYPE_VIRTUAL = constant("TYPE_VIRTUAL", 5);

    /** True when a physical, awake secondary display is attached. */
    public static boolean hasExternal(Context context) {
        final DisplayManager manager = displayManager(context);
        if (manager == null) {
            return false;
        }
        // Not DISPLAY_CATEGORY_PRESENTATION: the RP5's DP sink does not carry
        // FLAG_PRESENTATION, so that category would be empty there.
        for (Display display : manager.getDisplays()) {
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
        if ((display.getFlags() & Display.FLAG_PRIVATE) != 0) {
            return false;
        }
        final int type = type(display);
        return type != TYPE_VIRTUAL && type != TYPE_OVERLAY && type != TYPE_WIFI;
    }

    private static int type(Display display) {
        if (GET_TYPE == null) {
            return 0; /* TYPE_UNKNOWN: accepted */
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
