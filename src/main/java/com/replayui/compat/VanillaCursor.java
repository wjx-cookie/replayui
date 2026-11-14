package com.replayui.compat;

import com.mojang.blaze3d.platform.Window;
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
 * {@code AbstractContainerScreen#render} instead leaves the handler alone:
 * the screen is drawn from the recorded cursor, and movement deltas, camera
 * work and Flashback's editor all keep reading the viewer's real one.
 *
 * <p>Flashback's editor UI is unaffected either way: it is ImGui, fed by its
 * own GLFW cursor callback with its own stored position, and never reads the
 * handler.
 */
public final class VanillaCursor {

    private static boolean active;
    private static int x;
    private static int y;

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
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        Window window = client.getWindow();
        if (window == null) {
            return;
        }
        double scale = window.getGuiScale();
        if (scale <= 0.0) {
            return;
        }
        x = (int) Math.round(rawX / scale);
        y = (int) Math.round(rawY / scale);
        active = true;
    }

    /**
     * Stops replacing the coordinates.
     *
     * <p>There is no position to put back: the viewer's own was never touched.
     * This only stops the rebuilt screen from being drawn from a cursor that
     * belongs to a recording no longer on screen.
     */
    public static void clear() {
        active = false;
    }

    /** Recorded X, or the viewer's own when nothing is being replayed. */
    public static int replaceX(int viewerX) {
        return active ? x : viewerX;
    }

    /** Whether a recorded position is being replayed at all. */
    public static boolean isActive() {
        return active;
    }

    /** Recorded X in GUI coordinates. Only meaningful while {@link #isActive()}. */
    public static int recordedX() {
        return x;
    }

    /** Recorded Y in GUI coordinates. Only meaningful while {@link #isActive()}. */
    public static int recordedY() {
        return y;
    }

    /** Recorded Y, or the viewer's own when nothing is being replayed. */
    public static int replaceY(int viewerY) {
        return active ? y : viewerY;
    }
}
