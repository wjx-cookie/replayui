package com.replayui.log;

import com.moulberry.flashback.Flashback;
import com.replayui.compat.SpatialGuiCompat;

/**
 * One-time startup summary.
 *
 * <p>Prints what the mod found to work with, so the first thing in the log is
 * enough to tell whether the environment is even capable of running: Flashback
 * present, Spatial GUI present, verbose on. Without this, a silent failure has
 * nothing to anchor to.
 */
public final class StartupReport {

    private StartupReport() {
    }

    public static void print() {
        DebugLog.info("--- replayui startup ---");
        DebugLog.info("Flashback: " + flashbackStatus());
        DebugLog.info("Spatial GUI: " + (SpatialGuiCompat.isPresent()
                ? "detected, cursor playback enabled"
                : "not found, cursor playback disabled (screen replay still works)"));
        DebugLog.info("Verbose logging: " + (DebugLog.isVerbose()
                ? "ON"
                : "off (start with -Dreplayui.debug=true to enable)"));
        DebugLog.info("------------------------");
    }

    private static String flashbackStatus() {
        try {
            // Touching the class is enough: if the mod were missing, ReplayUI
            // would never have loaded because fabric.mod.json declares the
            // dependency.
            Package pkg = Flashback.class.getPackage();
            // getPackage() is not guaranteed non-null, and this runs during
            // startup - an NPE here would fail the whole mod load.
            String version = pkg != null ? pkg.getImplementationVersion() : null;
            return version != null ? "present (version " + version + ")" : "present";
        } catch (Throwable t) {
            return "MISSING - this mod cannot work without Flashback";
        }
    }
}
