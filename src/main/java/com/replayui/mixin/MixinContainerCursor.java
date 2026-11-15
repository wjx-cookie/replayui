package com.replayui.mixin;

import com.replayui.compat.VanillaCursor;
import com.replayui.playback.PlaybackTicker;
import com.replayui.render.ItemFlightAnimator;
import com.replayui.render.RecordedCursorIcon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a rebuilt screen from the recorded cursor instead of the viewer's own.
 *
 * <p>Only the two coordinates handed to {@code render} are swapped, so what
 * moves is what the screen draws: the stack floating under the cursor, the
 * hovered slot highlight and the tooltip. Everything that reads the real
 * pointer - camera movement, Flashback's editor - is left alone.
 *
 * <p>Restricted to screens we built, so a container the viewer opened for
 * themselves is still drawn from their own cursor.
 *
 * <p>{@code require = 0}: if this signature does not match, the injection is
 * skipped and screens fall back to the viewer's cursor - the same thing that
 * happened before this existed, not a crash.
 */
@Mixin(AbstractContainerScreen.class)
public class MixinContainerCursor {

    @ModifyVariable(method = "render", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
    private int replayui$cursorX(int mouseX) {
        return replayui$ours() ? VanillaCursor.replaceX(mouseX) : mouseX;
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 0)
    private int replayui$cursorY(int mouseY) {
        return replayui$ours() ? VanillaCursor.replaceY(mouseY) : mouseY;
    }

    private boolean replayui$ours() {
        return PlaybackTicker.isOurScreen((AbstractContainerScreen<?>) (Object) this);
    }

    /**
     * The recorded player's pointer, drawn at the end of the frame.
     *
     * <p>Painted through the same {@code GuiGraphics} the screen was drawn with
     * rather than over the top of the framebuffer, so whatever transform the
     * screen carries - including Spatial GUI's 3D panel - applies to the arrow
     * too. Nothing here resets the pose.
     *
     * <p>At TAIL so the arrow ends up above the hover highlight and the tooltip
     * the screen has already drawn, the way a real pointer sits above them.
     */
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void replayui$drawRecordedCursor(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                             float partialTick, CallbackInfo ci) {
        RecordedCursorIcon.drawIfNeeded(guiGraphics, replayui$ours());
        ItemFlightAnimator.draw(guiGraphics,
                (AbstractContainerScreen<?>) (Object) this, replayui$ours());
    }
}
