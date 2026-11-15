package com.replayui.compat;

import com.replayui.log.DebugLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Read-only bridge to Flashback's own export state.
 *
 * <p>This used to look for a "stereo 3D export addon" that was never fixed,
 * with both the class and field names left as placeholders. That made
 * {@link #isExporting()} permanently false: the guard in PlaybackTicker never
 * fired, and the rebuilt screen was never considered during an export.
 *
 * <p>Flashback itself exposes everything needed:
 * <pre>
 *   Flashback.isExporting()                    -> true while a job is running
 *   Flashback.EXPORT_JOB.getSettings().noGui() -> true when the GUI is stripped
 * </pre>
 * Both are read reflectively so this mod still loads when Flashback is absent
 * or when a future version renames them. A missing member is treated as
 * "not exporting" / "GUI is on" - never an error at startup.
 *
 * <p>{@code noGui} matters: Flashback cancels the GUI portion of
 * {@code GameRenderer#render} when it is set, so a screen mounted for that
 * frame would never be drawn and its tail hook would never run. Mounting is
 * skipped in that case instead.
 */
public final class ExportCompat {

    private static final String FLASHBACK_CLASS = "com.moulberry.flashback.Flashback";

    private static boolean resolved = false;
    private static Method isExportingMethod = null;
    private static Field exportJobField = null;

    private ExportCompat() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> flashback = Class.forName(FLASHBACK_CLASS);
            isExportingMethod = flashback.getMethod("isExporting");
            try {
                exportJobField = flashback.getField("EXPORT_JOB");
            } catch (Throwable ignored) {
                // Older/newer builds may keep the job private. isExporting()
                // alone is still enough to know an export is running; noGui
                // then falls back to "GUI is on".
                exportJobField = null;
            }
            DebugLog.info("Flashback export state detected, UI can be included in exported frames");
        } catch (Throwable t) {
            isExportingMethod = null;
            exportJobField = null;
            // Verbose, not warn: Flashback being absent is an unsupported
            // configuration, not a failure of this class.
            DebugLog.verbose("Flashback export state unavailable, export injection disabled");
        }
    }

    /**
     * True while Flashback is exporting a replay.
     *
     * <p>Always false when Flashback is absent, and once reading the method
     * fails the lookup is dropped rather than retried every frame.
     */
    public static boolean isExporting() {
        resolve();
        if (isExportingMethod == null) {
            return false;
        }
        try {
            Object value = isExportingMethod.invoke(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable t) {
            isExportingMethod = null;
            DebugLog.warn("failed to read Flashback export state, assuming not exporting", t);
            return false;
        }
    }

    /**
     * True when this export is configured to strip the GUI entirely.
     *
     * <p>Flashback's "No GUI" option cancels the GUI half of
     * {@code GameRenderer#render}. There is nothing to attach a screen to in
     * that case, so the safest answer is to skip mounting. Any reflection
     * failure answers "GUI is on" - the common case, and mounting still gets
     * cleaned up by PlaybackTicker's export guard on the next tick.
     */
    public static boolean exportHidesGui() {
        resolve();
        if (isExportingMethod == null || exportJobField == null) {
            return false;
        }
        try {
            Object job = exportJobField.get(null);
            if (job == null) {
                return false;
            }
            Method getSettings = job.getClass().getMethod("getSettings");
            Object settings = getSettings.invoke(job);
            if (settings == null) {
                return false;
            }
            Method noGui = settings.getClass().getMethod("noGui");
            Object value = noGui.invoke(settings);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable t) {
            return false;
        }
    }
}
