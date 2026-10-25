package com.replayui.compat;

import com.replayui.log.DebugLog;
import com.replayui.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;

/**
 * Feeds the recorded cursor back into vanilla's own MouseHandler.
 *
 * <p>This is the path that matters without Spatial GUI. SpatialGuiCompat only
 * writes into Spatial GUI's own cache and quietly does nothing when that mod is
 * absent, so on a plain 2D screen the recorded coordinates were thrown away and
 * the renderer fell back to the viewer's real pointer.
 *
 * <p>Writing the raw window position is enough: vanilla derives the scaled
 * {@code mouseX / mouseY} passed to {@code Screen#render} from these fields,
 * and that is what positions the stack floating under the cursor, the hovered
 * slot highlight and tooltips.
 *
 * <p>The viewer's own pointer is not otherwise disturbed: turning the camera
 * uses accumulated movement deltas, not these fields.
 */
public final class VanillaCursor {

    private static boolean failed;

    /** Viewer's real position, captured once before the first overwrite. */
    private static boolean saved;
    private static double savedX;
    private static double savedY;

    private VanillaCursor() {
    }

    public static void inject(double rawX, double rawY) {
        // A cast that cannot work will not start working. `failed` alone only
        // silences the message - inject() is still called every tick of every
        // replay, which is 20 failed casts a second, each building a stack
        // trace, for a cursor that will never appear.
        if (failed) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.mouseHandler == null) {
            return;
        }
        try {
            MouseHandlerAccessor mouse = (MouseHandlerAccessor) client.mouseHandler;
            if (!saved) {
                savedX = mouse.replayui$getXpos();
                savedY = mouse.replayui$getYpos();
                saved = true;
            }
            mouse.replayui$setXpos(rawX);
            mouse.replayui$setYpos(rawY);
        } catch (Throwable t) {
            if (!failed) {
                failed = true;
                // Note: deliberately NOT clearing ReplayUIConfig.captureMouse.
                // UiStateReader does that on the recording side, and
                // UiSampler reads the same flag to decide whether to zero the
                // recorded coordinates - so clearing it here for a playback
                // failure would silently strip the cursor out of every
                // recording made afterwards in the same session. The early
                // return below is what actually stops the retries.
                DebugLog.warn("could not write cursor position, "
                        + "mouse replay disabled for this session", t);
            }
        }
    }

    /**
     * Puts the viewer's pointer back where it was.
     *
     * <p>Called from the same place that closes the rebuilt screen, so every
     * exit path is covered. Without it the cursor stays parked at the last
     * recorded spot after the screen goes away - and since these fields are
     * only updated by GLFW when the mouse actually moves, a viewer who never
     * touched the mouse would find hover and tooltips on their own screens
     * reading a stale position until they jiggled it.
     */
    public static void restore() {
        if (!saved) {
            return;
        }
        saved = false;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.mouseHandler == null) {
            return;
        }
        try {
            MouseHandlerAccessor mouse = (MouseHandlerAccessor) client.mouseHandler;
            mouse.replayui$setXpos(savedX);
            mouse.replayui$setYpos(savedY);
        } catch (Throwable t) {
            DebugLog.verbose("could not restore cursor position", t);
        }
    }
}
