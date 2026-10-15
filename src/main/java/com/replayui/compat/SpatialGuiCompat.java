package com.replayui.compat;

import com.replayui.log.DebugLog;

import java.lang.reflect.Method;

/**
 * Optional bridge to Spatial GUI.
 *
 * <p>Reflection on purpose: this mod must work on versions where Spatial GUI
 * does not exist. When it is present we feed it the recorded cursor so its own
 * 3D plane mapping, hover and tooltips behave exactly as they do in normal
 * play. When it is absent every call is a no-op and the rest of the mod is
 * unaffected.
 */
public final class SpatialGuiCompat {

    private static final String UTIL = "org.tastytrash.spatialGUI.util.MouseHandlerUtil";

    private static boolean resolved = false;
    private static Method captureMove;

    /** injectMouse runs every tick, so a failure must be reported only once. */
    private static boolean injectFailed = false;

    private SpatialGuiCompat() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> util = Class.forName(UTIL);
            captureMove = util.getMethod("captureMove", double.class, double.class, boolean.class);
            DebugLog.info("Spatial GUI detected, cursor playback enabled");
        } catch (Throwable t) {
            captureMove = null;
            DebugLog.verbose("Spatial GUI absent, cursor playback disabled");
        }
    }

    public static boolean isPresent() {
        resolve();
        return captureMove != null;
    }

    /**
     * Pushes raw cursor coords into Spatial GUI's own cache.
     * Call every tick: it resets to screen centre whenever a screen opens.
     */
    public static void injectMouse(double rawX, double rawY) {
        resolve();
        if (captureMove == null) {
            return;
        }
        try {
            captureMove.invoke(null, rawX, rawY, false);
        } catch (Throwable t) {
            if (!injectFailed) {
                injectFailed = true;
                DebugLog.warn("failed to inject cursor into Spatial GUI, giving up", t);
            }
            captureMove = null;
        }
    }
}
