package com.replayui.compat;

import com.replayui.log.DebugLog;

import java.lang.reflect.Field;
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

    /** Preferred route: write the cached physical cursor directly. */
    private static Field physicalX;
    private static Field physicalY;

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

            // The two fields below, not captureMove.
            //
            // captureMove(x, y, false) does set the physical cursor we want, but
            // it also rewrites lastPhysicalX / lastPhysicalY - and Spatial GUI's
            // own MouseHandlerMixin calls it from onMove purely to get the
            // movement delta it feeds to addFreeLookDelta, which is what turns
            // the camera in crosshair mode. Calling it here every tick meant the
            // next real mouse move computed its delta against the RECORDED
            // position instead of the previous real one: a large bogus delta on
            // every move, and the camera lurching while watching a replay.
            //
            // Writing the fields touches only the hover / tooltip mapping, which
            // is the whole reason this bridge exists.
            physicalX = findField(util, "physicalX");
            physicalY = findField(util, "physicalY");

            // Kept as a fallback for a build where the fields were renamed or
            // made non-static: cursor playback is cosmetic, so degrading to the
            // call that also moves the camera beats losing it outright.
            captureMove = util.getMethod("captureMove", double.class, double.class, boolean.class);

            DebugLog.info("Spatial GUI detected, cursor playback enabled"
                    + (physicalX != null && physicalY != null ? "" : " (field fallback)"));
        } catch (Throwable t) {
            captureMove = null;
            physicalX = null;
            physicalY = null;
            DebugLog.verbose("Spatial GUI absent, cursor playback disabled");
        }
    }

    @SuppressWarnings("rawtypes")
    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            // Static only: an instance field would mean the layout changed and
            // writing it would throw on every call.
            return java.lang.reflect.Modifier.isStatic(field.getModifiers()) ? field : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isPresent() {
        resolve();
        return captureMove != null || physicalX != null;
    }

    /**
     * Pushes raw cursor coords into Spatial GUI's own cache.
     * Call every tick: it resets to screen centre whenever a screen opens.
     */
    public static void injectMouse(double rawX, double rawY) {
        resolve();
        if (captureMove == null && physicalX == null) {
            return;
        }
        try {
            if (physicalX != null && physicalY != null) {
                physicalX.set(null, rawX);
                physicalY.set(null, rawY);
            } else {
                captureMove.invoke(null, rawX, rawY, false);
            }
        } catch (Throwable t) {
            if (!injectFailed) {
                injectFailed = true;
                DebugLog.warn("failed to inject cursor into Spatial GUI, giving up", t);
            }
            captureMove = null;
            physicalX = null;
            physicalY = null;
        }
    }
}
