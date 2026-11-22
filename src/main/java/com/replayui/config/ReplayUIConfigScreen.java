package com.replayui.config;

import com.replayui.render.CursorTexture;
import com.replayui.render.RecordedCursorIcon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The config screen Mod Menu opens.
 *
 * <p>Only genuine preferences are exposed here. Everything the mod exists to
 * do - rebuilding container screens, drawing the recorded player and pointer,
 * putting the UI into exported video, cursor smoothing, click pinning and
 * item flight - is always on and deliberately has no switch. A player who
 * installed ReplayUI wants those; an off switch would only let the feature
 * be broken by accident, with no way back from a stale config file.
 *
 * <p>Deliberately built from vanilla widgets only: no Cloth Config, no YACL,
 * no extra runtime mod. The Mod Menu API is compile-only, so this class is
 * never loaded when Mod Menu is absent.
 *
 * <p>Edits go into a draft snapshot. Nothing is written until Done; Cancel
 * leaves both the switches and the file untouched. The cursor panel is a
 * static preview: it draws the very sprite {@link RecordedCursorIcon} would
 * draw at the draft's size, 1:1 in GUI pixels, and never follows the mouse.
 */
public final class ReplayUIConfigScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int PREVIEW_W = 210;

    private final Screen parent;
    private final ConfigStore.Values draft = ConfigStore.read();
    private final List<Toggle> toggles = new ArrayList<>();
    private EditBox scaleBox;
    private EditBox maxHeightBox;

    /** Memo for the preview; recomputed only when a draft value changes. */
    private String previewKey;
    private CursorTexture.Sprite previewSprite;
    private int previewPercent = 100;
    private boolean previewStyleActive;
    private boolean previewUserSprite;
    private boolean previewSystemUnavailable;

    private static final class Toggle {
        final String key;
        final BooleanSupplier get;
        final Consumer<Boolean> set;

        Toggle(String key, BooleanSupplier get, Consumer<Boolean> set) {
            this.key = key;
            this.get = get;
            this.set = set;
        }
    }

    public ReplayUIConfigScreen(Screen parent) {
        super(Component.translatable("replayui.config.title"));
        this.parent = parent;
        toggles.add(new Toggle("followSystemCursorStyle",
                () -> draft.followSystemCursorStyle, v -> draft.followSystemCursorStyle = v));
        toggles.add(new Toggle("followSystemCursorSize",
                () -> draft.followSystemCursorSize, v -> draft.followSystemCursorSize = v));
        toggles.add(new Toggle("showRecordedTooltips",
                () -> draft.showRecordedTooltips, v -> draft.showRecordedTooltips = v));
        toggles.add(new Toggle("showSpecialScreens",
                () -> draft.showSpecialScreens, v -> draft.showSpecialScreens = v));
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int cx = this.width / 2;
        int top = 34;

        for (int i = 0; i < toggles.size(); i++) {
            Toggle t = toggles.get(i);
            int y = top + i * ROW_H;
            String state = Component.translatable(t.get.getAsBoolean()
                    ? "replayui.config.on" : "replayui.config.off").getString();
            String label = Component.translatable("replayui.config." + t.key).getString();
            Button b = Button.builder(Component.literal(label + ": " + state), button -> {
                t.set.accept(!t.get.getAsBoolean());
                rebuild();
            }).bounds(cx - BTN_W / 2, y, BTN_W, BTN_H).build();
            addRenderableWidget(b);
        }

        int y = top + toggles.size() * ROW_H;
        scaleBox = new EditBox(this.font, cx - BTN_W / 2, y, 96, BTN_H,
                Component.translatable("replayui.config.cursorScale"));
        scaleBox.setValue(String.valueOf(draft.scale));
        addRenderableWidget(scaleBox);
        maxHeightBox = new EditBox(this.font, cx - BTN_W / 2 + 104, y, 96, BTN_H,
                Component.translatable("replayui.config.cursorMaxHeight"));
        maxHeightBox.setValue(String.valueOf(draft.maxHeight));
        addRenderableWidget(maxHeightBox);

        int bottom = this.height - 26;
        addRenderableWidget(Button.builder(
                Component.translatable("replayui.config.cancel"),
                button -> onClose()).bounds(cx - BTN_W / 2 + 52, bottom, 70, BTN_H).build());
        addRenderableWidget(Button.builder(
                Component.translatable("replayui.config.done"),
                button -> {
                    syncNumbers();
                    ConfigStore.save(draft);
                    onClose();
                }).bounds(cx - BTN_W / 2 + 126, bottom, 70, BTN_H).build());
    }

    /** Reads the two edit boxes without touching the draft (used by the preview). */
    private double[] readNumbers() {
        double scale = draft.scale;
        int maxHeight = draft.maxHeight;
        try {
            scale = Double.parseDouble(scaleBox.getValue().trim());
        } catch (NumberFormatException ignored) {
            // Keep the last good value.
        }
        try {
            maxHeight = Integer.parseInt(maxHeightBox.getValue().trim());
        } catch (NumberFormatException ignored) {
            // Keep the last good value.
        }
        return new double[]{scale, maxHeight};
    }

    private void syncNumbers() {
        double[] n = readNumbers();
        draft.scale = n[0];
        draft.maxHeight = (int) Math.round(n[1]);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("replayui.config.hint").getString(),
                this.width / 2, 26, 0xA0A0A0);

        int cx = this.width / 2;
        int panelTop = 34 + toggles.size() * ROW_H + BTN_H + 6;
        int room = this.height - 34 - panelTop;
        // The preview never disappears. A short GUI loses its captions, not
        // the arrow: drawPreview() adapts to whatever vertical room is left.
        int y = Math.min(panelTop, Math.max(0, this.height - 8));
        int h = Math.max(8, Math.min(room, this.height - y));
        drawPreview(graphics, cx - PREVIEW_W / 2, y, PREVIEW_W, h);
    }

    /**
     * Static, 1:1 preview. No mouse coordinates reach this method, so the
     * picture does not move or animate; it only changes when a draft value
     * changes. Drawing goes through the same {@code drawSprite} the replay
     * uses, so the panel and the exported frame cannot drift apart.
     */
    private void drawPreview(GuiGraphics g, int x, int y, int w, int h) {
        double[] nums = readNumbers();
        // Height is part of the key: resizing the GUI must recompute the
        // fit-to-panel scaling instead of reusing a stale sprite.
        String key = CursorTexture.sourceTag() + "|" + CursorTexture.source() + "|"
                + nums[0] + "|" + (int) Math.round(nums[1]) + "|"
                + draft.followSystemCursorStyle + "|" + draft.followSystemCursorSize
                + "|" + h;
        boolean captions = h >= 44;
        boolean sourceLine = h >= 32;
        int arrowTop = captions ? y + 14 : y + 1;
        int arrowH = captions ? h - 34 : Math.max(1, h - 2);
        if (!key.equals(previewKey)) {
            CursorTexture.Resolved r = CursorTexture.preview(
                    nums[0], (int) Math.round(nums[1]),
                    draft.followSystemCursorStyle, draft.followSystemCursorSize);
            CursorTexture.Sprite sprite = r.sprite;
            int percent = 100;
            if (sprite != null && arrowH > 0 && sprite.height > arrowH) {
                double factor = arrowH / (double) sprite.height;
                sprite = CursorTexture.scale(sprite, factor);
                percent = (int) Math.round(factor * 100);
            }
            previewSprite = sprite;
            previewPercent = percent;
            previewStyleActive = r.styleFollowActive;
            previewUserSprite = r.userSpritePresent;
            // Style following requested and active, yet no desktop picture
            // could be read: the arrow still shows the degraded source, and
            // the panel says so instead of looking like the switch did nothing.
            previewSystemUnavailable = draft.followSystemCursorStyle
                    && r.styleFollowActive && !"system".equals(r.tag);
            previewKey = key;
        }

        // Panel: dark board with a 1px frame.
        g.fill(x, y, x + w, y + h, 0x90101010);
        g.fill(x, y, x + w, y + 1, 0xFF6A6A6A);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF6A6A6A);
        g.fill(x, y, x + 1, y + h, 0xFF6A6A6A);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF6A6A6A);

        CursorTexture.Sprite sprite = previewSprite;
        if (captions) {
            String title = Component.translatable("replayui.config.preview").getString();
            if (sprite != null) {
                title = title + "  ·  " + sprite.width + " x " + sprite.height + " px";
                if (previewPercent < 100) {
                    title = title + "  ·  " + Component.translatable(
                            "replayui.config.previewScaled", previewPercent).getString();
                }
            }
            g.drawCenteredString(this.font, title, x + w / 2, y + 4, 0xDDDDDD);
        }

        // Always painted. Even a fully degraded source falls back to the
        // built-in arrow bitmap, so the panel is never empty.
        if (sprite == null) {
            RecordedCursorIcon.drawFallback(g, x + w / 2 - 6,
                    arrowTop + (arrowH - 19) / 2);
        } else {
            int bx = x + (w - sprite.width) / 2;
            int by = arrowTop + (arrowH - sprite.height) / 2;
            RecordedCursorIcon.drawSprite(g, sprite, bx, by);
        }

        if (sourceLine) {
            if (previewSystemUnavailable) {
                g.drawCenteredString(this.font, Component.translatable(
                        "replayui.config.systemUnavailable").getString(),
                        x + w / 2, y + h - 20, 0xCCCC66);
            } else if (previewUserSprite && !previewStyleActive) {
                g.drawCenteredString(this.font,
                        Component.translatable("replayui.config.override").getString(),
                        x + w / 2, y + h - 20, 0xCCCC66);
            }
            g.drawCenteredString(this.font,
                    Component.translatable("replayui.config.source",
                            CursorTexture.source()).getString(),
                    x + w / 2, y + h - 10, 0x909090);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
