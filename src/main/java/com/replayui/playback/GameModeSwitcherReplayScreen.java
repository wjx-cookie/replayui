package com.replayui.playback;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only reconstruction of the F3+F4 game-mode switcher.
 *
 * <p>Deliberately NOT the vanilla {@code GameModeSwitcherScreen}: that one
 * applies the selected game mode when F3 is released - in 1.21.1 by sending a
 * chat command - and closes itself from inside {@code render()}. Putting it on
 * screen during a replay would let the viewer change the replay world. This
 * class draws the four slots and the selection frame itself and swallows every
 * input callback, so nothing reaches the replay server and nothing is executed.
 *
 * <p>Layout is a faithful-enough approximation: four square slots in a row,
 * the selected one outlined, with the recorded captions underneath. The
 * captions and the selected index come from the recording, not from this
 * client's game mode, so the order matches whatever the recorder showed.
 */
public final class GameModeSwitcherReplayScreen extends Screen {

    private static final int SLOT = 26;
    private static final int GAP = 5;
    private static final int CAPTION_COLOR = 0xFFFFFF;
    private static final int HINT_COLOR = 0xAAAAAA;
    private static final int SLOT_BG = 0xC0202020;
    private static final int SLOT_SELECTED = 0xC08B7500;
    private static final int FRAME = 0xFFFFEE55;

    private static final int GLFW_KEY_ESCAPE = 256;

    private int selected = 0;
    private List<Component> captions = new ArrayList<Component>();

    public GameModeSwitcherReplayScreen() {
        super(Component.literal(""));
    }

    /** Called on both the initial build and every refresh. */
    public void setState(int selected, List<Component> captions) {
        this.selected = Math.max(0, selected);
        this.captions = captions == null
                ? new ArrayList<Component>()
                : new ArrayList<Component>(captions);
    }

    /** Vanilla draws no dark backdrop for this overlay; keep the world visible. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int n = captions.isEmpty() ? 4 : Math.min(4, captions.size());
        int total = n * SLOT + (n - 1) * GAP;
        int x0 = (this.width - total) / 2;
        int y0 = Math.max(8, this.height / 4);
        for (int i = 0; i < n; i++) {
            int x = x0 + i * (SLOT + GAP);
            g.fill(x, y0, x + SLOT, y0 + SLOT,
                    i == selected ? SLOT_SELECTED : SLOT_BG);
            if (i == selected) {
                g.fill(x, y0, x + SLOT, y0 + 1, FRAME);
                g.fill(x, y0 + SLOT - 1, x + SLOT, y0 + SLOT, FRAME);
                g.fill(x, y0, x + 1, y0 + SLOT, FRAME);
                g.fill(x + SLOT - 1, y0, x + SLOT, y0 + SLOT, FRAME);
            }
            if (i < captions.size()) {
                g.drawCenteredString(this.font, captions.get(i),
                        x + SLOT / 2, y0 + SLOT + 4, CAPTION_COLOR);
            }
        }
        g.drawCenteredString(this.font, Component.literal("F3 + F4"),
                this.width / 2, y0 + SLOT + 18, HINT_COLOR);
    }

    // ---- read-only ---------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW_KEY_ESCAPE) {
            PlaybackTicker.dismissByUser();
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
