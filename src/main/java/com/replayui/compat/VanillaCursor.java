package com.replayui.compat;

import com.mojang.blaze3d.platform.Window;
import com.replayui.ReplayUIConfig;
import net.minecraft.client.Minecraft;

/**
 * Holds the recorded cursor so the rebuilt screen can be drawn from it.
 *
 * <p>Two cursors, two separate territories. The editor keeps their own pointer
 * - it is what flies the camera and what drives Flashback's own editor UI -
 * and the recorded player gets a cursor of their own inside the rebuilt screen.
 * Nothing here touches the viewer's pointer, so the two never fight.
 *
 * <p>That is why this used to be a write into {@code MouseHandler} and now is
 * not. Overwriting {@code xpos / ypos} does move the drawn stack, but those two
 * fields are also the pair vanilla subtracts the next GLFW position from to
 * work out how far the mouse moved. Once they hold a recorded position, the
 * first time the editor moves their own mouse the difference is the whole gap
 * between the two - reported as viewer input, which swings the camera across
 * the replay. Taking the recorded coordinates in at
 * {@code AbstractContainerScreen#render} instead leaves the handler alone.
 *
 * <p>The position itself is eased between samples by {@link CursorInterpolator};
 * {@code update} still runs once per tick, but every rendered frame reads a
 * fresh interpolated value, which also works during video export.
 */
public final class VanillaCursor {

    private static boolean active;

    private VanillaCursor() {
    }

    /**
     * Called once per tick while one of our screens is up.
     *
     * <p>Recorded values are raw window pixels, which is what
     * {@code MouseHandler} stores; {@code Screen#render} is handed GUI
     * coordinates instead. Converting here keeps the two sides honest if the
     * window is a different size or scale during playback.
     */
    public static void update(double rawX, double rawY) {
        update(rawX, rawY, 0, 0);
    }

    /**
     * Maps recorded raw pixels onto the viewer's current GUI size.
     *
     * <p>Flashback's video export resizes the display and doubles the GUI
     * scale for SSAA, so dividing by the current scale alone would put the
     * cursor somewhere else entirely. Normalising by the window the sample
     * was captured in makes the position resolution-independent.
     *
     * <p>{@code recordedWidth <= 0} means an old sample without the size;
     * fall back to the scale-only conversion (the previous behaviour).
     */
    public static void update(double rawX, double rawY,
                              int recordedWidth, int recordedHeight) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        Window window = client.getWindow();
        if (window == null) {
            return;
        }
        double guiX;
        double guiY;
        if (recordedWidth > 0 && recordedHeight > 0) {
            double nx = rawX / (double) recordedWidth;
            double ny = rawY / (double) recordedHeight;
            guiX = nx * window.getGuiScaledWidth();
            guiY = ny * window.getGuiScaledHeight();
        } else {
            double scale = window.getGuiScale();
            if (scale <= 0.0) {
                return;
            }
            guiX = rawX / scale;
            guiY = rawY / scale;
        }
        CursorInterpolator.update(guiX, guiY,
                System.nanoTime(), ReplayUIConfig.smoothCursor);
        active = true;
    }

    /**
     * Stops replacing the coordinates.
     *
     * <p>There is no position to put back: the viewer's own was never touched.
     */
    public static void clear() {
        active = false;
        CursorInterpolator.reset();
    }

    /** Recorded X, or the viewer's own when nothing is being replayed. */
    public static int replaceX(int viewerX) {
        return active ? recordedX() : viewerX;
    }

    /** Recorded Y, or the viewer's own when nothing is being replayed. */
    public static int replaceY(int viewerY) {
        return active ? recordedY() : viewerY;
    }

    /** Whether a recorded position is being replayed at all. */
    public static boolean isActive() {
        return active;
    }

    /** Recorded X in GUI coordinates, interpolated for this frame. */
    public static int recordedX() {
        return (int) Math.round(CursorInterpolator.x(System.nanoTime()));
    }

    /** Recorded Y in GUI coordinates, interpolated for this frame. */
    public static int recordedY() {
        return (int) Math.round(CursorInterpolator.y(System.nanoTime()));
    }
}
