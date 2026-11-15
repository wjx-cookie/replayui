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
 *       would break that inheritance. For the same reason the PNG is decoded
 *       into pixels and drawn with {@code fill} rather than bound as a texture:
 *       a blit would flush the batch.</li>
 *   <li>It is injected at the tail of {@code render}, after the screen has
 *       drawn its own hover, highlight and tooltip, so it lands on top of them
 *       the way a real pointer does - not buried under a tooltip.</li>
 * </ul>
 *
 * <p>The picture is a replaceable PNG - see {@link CursorTexture} for where to
 * put one. If no PNG can be loaded at all, the built-in arrow bitmap below is
 * used, so the pointer never silently disappears.
 *
 * <p>All of the decision-making lives in {@link #drawIfNeeded} rather than in
 * the mixin that calls it, so it can be exercised without a mixin engine.
 */
public final class RecordedCursorIcon {

    /** White fill with a black outline, the usual pointer silhouette. */
    private static final int FILL = 0xFFFFFFFF;
    private static final int OUTLINE = 0xFF000000;
    private static final int TRANSPARENT = 0;

    /**
     * Fallback arrow, 12 columns by 19 rows, tip at {@code (0, 0)}.
     * '#' is filled; '.' is transparent. The diagonal shoulder and the
     * constant-width tail are what make it read as a pointer rather than a
     * triangle. This bitmap is only the fallback: the shipped PNG is a
     * separately supplied arrow (background removed, downscaled to 12x19),
     * so the two no longer match pixel for pixel.
     */
    private static final String[] SHAPE = {
            "#...........", //  0  tip
            "##..........", //  1
            "###.........", //  2
            "####........", //  3
            "#####.......", //  4
            "######......", //  5
            "#######.....", //  6
            "########....", //  7
            "#########...", //  8
            "##########..", //  9
            "###########.", // 10
            "############", // 11  widest row
            ".#########..", // 12  diagonal shoulder
            "..########..", // 13
            ".....#####..", // 14
            "......####..", // 15  tail
            "......####..", // 16
            "......####..", // 17
            "......####..", // 18
    };

    private static final int SHAPE_WIDTH = SHAPE[0].length();

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

    /** Draws the pointer with its hotspot at {@code (x, y)}. */
    public static void draw(GuiGraphics guiGraphics, int x, int y) {
        if (guiGraphics == null) {
            return;
        }
        CursorTexture.Sprite sprite = CursorTexture.get();
        if (sprite == null) {
            drawFallback(guiGraphics, x, y);
            return;
        }
        drawSprite(guiGraphics, sprite, x - sprite.offsetX, y - sprite.offsetY);
    }

    /**
     * Two passes: black outline first, then every other colour. Consecutive
     * pixels of the same colour are collapsed into one horizontal run, so a
     * custom multicolour PNG still costs only a handful of {@code fill} calls
     * per row. All drawing goes through the screen's own {@code GuiGraphics}.
     */
    private static void drawSprite(GuiGraphics g, CursorTexture.Sprite s, int baseX, int baseY) {
        drawPass(g, s, baseX, baseY, true);
        drawPass(g, s, baseX, baseY, false);
    }

    private static void drawPass(GuiGraphics g, CursorTexture.Sprite s,
                                 int baseX, int baseY, boolean outlinePass) {
        for (int row = 0; row < s.height; row++) {
            int col = 0;
            while (col < s.width) {
                int color = s.argb[row * s.width + col];
                if (!matches(color, outlinePass)) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < s.width
                        && s.argb[row * s.width + col] == color
                        && matches(color, outlinePass)) {
                    col++;
                }
                g.fill(baseX + start, baseY + row, baseX + col, baseY + row + 1, color);
            }
        }
    }

    private static boolean matches(int color, boolean outlinePass) {
        if (color == TRANSPARENT) {
            return false;
        }
        return outlinePass ? color == OUTLINE : color != OUTLINE;
    }

    /** The original bitmap renderer, used only when no PNG could be loaded. */
    static void drawFallback(GuiGraphics g, int x, int y) {
        for (int row = 0; row < SHAPE.length; row++) {
            String line = SHAPE[row];
            int col = 0;
            while (col < line.length()) {
                if (line.charAt(col) != '#') {
                    col++;
                    continue;
                }
                int start = col;
                while (col < line.length() && line.charAt(col) == '#') {
                    col++;
                }
                g.fill(x + start - 1, y + row - 1,
                        x + col + 1, y + row + 2, OUTLINE);
            }
        }
        for (int row = 0; row < SHAPE.length; row++) {
            String line = SHAPE[row];
            int col = 0;
            while (col < line.length()) {
                if (line.charAt(col) != '#') {
                    col++;
                    continue;
                }
                int start = col;
                while (col < line.length() && line.charAt(col) == '#') {
                    col++;
                }
                g.fill(x + start, y + row, x + col, y + row + 1, FILL);
            }
        }
    }

    /** Number of {@code fill} calls the current picture needs. */
    public static int drawCallCount() {
        CursorTexture.Sprite s = CursorTexture.get();
        if (s == null) {
            int runs = 0;
            for (String line : SHAPE) {
                int col = 0;
                while (col < line.length()) {
                    if (line.charAt(col) != '#') {
                        col++;
                        continue;
                    }
                    runs++;
                    while (col < line.length() && line.charAt(col) == '#') {
                        col++;
                    }
                }
            }
            return runs * 2;
        }
        int calls = 0;
        for (int row = 0; row < s.height; row++) {
            int col = 0;
            while (col < s.width) {
                int color = s.argb[row * s.width + col];
                if (color == TRANSPARENT) {
                    col++;
                    continue;
                }
                calls++;
                while (col < s.width && s.argb[row * s.width + col] == color) {
                    col++;
                }
            }
        }
        return calls;
    }

    /** Height of the drawn pointer in pixels, outline included. */
    public static int height() {
        CursorTexture.Sprite s = CursorTexture.get();
        return s == null ? SHAPE.length + 2 : s.height;
    }

    /** Width of the drawn pointer in pixels, outline included. */
    public static int width() {
        CursorTexture.Sprite s = CursorTexture.get();
        return s == null ? SHAPE_WIDTH + 2 : s.width;
    }

    /** The fallback bitmap, exposed so tests can compare the shipped PNG to it. */
    public static String[] fallbackShape() {
        return SHAPE.clone();
    }
}
