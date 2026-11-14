package com.replayui.render;

import com.replayui.ReplayUIConfig;
import com.replayui.compat.VanillaCursor;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The recorded player's own pointer, drawn inside the rebuilt screen.
 *
 * <p>Vanilla never draws a cursor in a container screen: the pointer on screen
 * is the hardware one the operating system paints at the viewer's real
 * position, and what shows where the recorded player was pointing is only the
 * stack riding on their cursor. With an empty hand there is nothing at all, so
 * a recording of someone moving the mouse around an open chest looks like
 * nothing is happening.
 *
 * <p>So one gets drawn here. It is deliberately <em>not</em> a separate overlay
 * painted at screen coordinates:
 *
 * <ul>
 *   <li>It is drawn through the very {@code GuiGraphics} the screen is being
 *       rendered with, so whatever transform that graphics carries is applied
 *       to it as well. A screen that is scaled, rotated or - with Spatial GUI -
 *       mapped onto a 3D panel in the world takes the pointer with it, because
 *       the icon is just another draw call in the same batch.</li>
 *   <li>Nothing resets the pose or pushes a matrix of its own, which is what
 *       would break that inheritance.</li>
 *   <li>It is injected at the tail of {@code render}, after the screen has
 *       drawn its own hover, highlight and tooltip, so it lands on top of them
 *       the way a real pointer does - not buried under a tooltip.</li>
 * </ul>
 *
 * <p>The icon is a plain arrow assembled from rectangles, which keeps it free
 * of any texture: no resource to load, nothing that can be missing after a
 * resource reload, and nothing that has to be shipped alongside the mod.
 *
 * <p>All of the decision-making lives in {@link #drawIfNeeded} rather than in
 * the mixin that calls it, so it can be exercised without a mixin engine.
 */
public final class RecordedCursorIcon {

    /** White fill with a black outline, the usual pointer silhouette. */
    private static final int FILL = 0xFFFFFFFF;
    private static final int OUTLINE = 0xFF000000;

    /**
     * Width of each row of the arrow, top to bottom.
     *
     * <p>Widens from the tip down to the bar across the bottom of the head,
     * then narrows to the tail. Fifteen rows by ten pixels, close to the size
     * of the pointer the system draws.
     */
    private static final int[] ROWS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 6, 4, 2, 2, 2};

    private RecordedCursorIcon() {
    }

    /**
     * Draws the recorded pointer, if there is one to draw.
     *
     * @param guiGraphics the graphics the screen is being drawn with
     * @param ourScreen   whether this screen was rebuilt by us - a container
     *                    the viewer opened for themselves keeps their own
     *                    pointer and gets no second icon
     */
    public static void drawIfNeeded(GuiGraphics guiGraphics, boolean ourScreen) {
        if (!ReplayUIConfig.showCursorIcon) {
            return;
        }
        if (!ourScreen) {
            return;
        }
        // Read from the recorded coordinates rather than from the mouseX/mouseY
        // handed to render. Those two are already replaced by a separate
        // injection; going back to the source keeps the icon in the same place
        // even if that injection does not apply on this version - in which case
        // the stack follows the viewer but the pointer still shows the truth.
        if (!VanillaCursor.isActive()) {
            return;
        }
        draw(guiGraphics, VanillaCursor.recordedX(), VanillaCursor.recordedY());
    }

    /**
     * Draws the arrow with its tip at {@code (x, y)}.
     *
     * <p>Two passes: the outline is drawn first, one pixel larger on every
     * side, then the fill on top of it. Doing it in that order is what leaves
     * an even border - drawing row by row instead would let the next row's
     * outline eat the previous row's fill.
     */
    public static void draw(GuiGraphics guiGraphics, int x, int y) {
        if (guiGraphics == null) {
            return;
        }
        for (int row = 0; row < ROWS.length; row++) {
            int width = ROWS[row];
            guiGraphics.fill(x - 1, y + row - 1, x - 1 + width + 2, y + row + 1, OUTLINE);
        }
        for (int row = 0; row < ROWS.length; row++) {
            int width = ROWS[row];
            guiGraphics.fill(x, y + row, x + width, y + row + 1, FILL);
        }
    }

    /** How many rectangles the arrow is made of, both passes included. */
    public static int drawCallCount() {
        return ROWS.length * 2;
    }

    /** Height of the arrow in pixels, outline included. */
    public static int height() {
        return ROWS.length + 2;
    }

    /** Width of the arrow in pixels, outline included. */
    public static int width() {
        int widest = 0;
        for (int width : ROWS) {
            widest = Math.max(widest, width);
        }
        return widest + 2;
    }
}
