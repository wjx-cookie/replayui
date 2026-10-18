package com.replayui.compat;

import com.replayui.log.DebugLog;

import java.lang.reflect.Field;

/**
 * Soft-read bridge to the stereo 3D export addon.
 *
 * <p>That addon renders the replay twice (two eyes) and encodes the result.
 * Flashback's own export path suppresses GUI rendering, but a screen we
 * rebuild here would still be sitting on top of the frame, so during an
 * export this mod must not rebuild anything.
 *
 * <p>The flag is read reflectively on purpose: the addon is entirely optional,
 * and this mod must keep working when it is absent. A missing class is treated
 * as "not exporting" - never an error, never a warning.
 *
 * <p>Negative results are cached as well. Mods do not load mid-session, so
 * there is no point retrying a failed lookup on every frame.
 */
public final class ExportCompat {

    // ===================================================================
    // CONTRACT with the stereo export addon
    //
    // It exposes one public static flag that is true while an export is
    // running. Fill in the fully-qualified class name and field name below
    // once they are fixed. Until then both are left as explicit placeholders
    // so this file cannot silently read the wrong thing.
    // ===================================================================
    private static final String FLAG_CLASS = "com.replayui.compat.ExportCompat$PLACEHOLDER_CLASS_NAME";
    private static final String FLAG_FIELD = "PLACEHOLDER_FIELD_NAME";

    private static boolean resolved = false;
    private static Field flag = null;

    private ExportCompat() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> owner = Class.forName(FLAG_CLASS);
            Field f = owner.getField(FLAG_FIELD);
            f.setAccessible(true);
            flag = f;
            DebugLog.info("stereo export addon detected, UI rebuild pauses during export");
        } catch (Throwable t) {
            flag = null;
            // verbose, not warn: the addon being absent is the normal case.
            DebugLog.verbose("stereo export addon absent, export state ignored");
        }
    }

    /**
     * True while an export is running. Always false when the addon is not
     * installed, and always false if reading the flag ever fails.
     */
    public static boolean isExporting() {
        resolve();
        if (flag == null) {
            return false;
        }
        try {
            // Accepts both boolean and Boolean without caring which.
            Object value = flag.get(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable t) {
            // The field moved or changed type. Stop asking rather than throwing
            // every frame; not exporting is the safe default.
            flag = null;
            DebugLog.warn("failed to read export flag, assuming not exporting", t);
            return false;
        }
    }
}
