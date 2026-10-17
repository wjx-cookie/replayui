package com.replayui.mixin;

import com.replayui.ReplayUIIds;
import com.replayui.playback.PlaybackTicker;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes our rebuilt screens read-only.
 *
 * <p>Without this, a click would call {@code handleInventoryMouseClick} and ESC
 * would call {@code closeContainer()} - both sending packets tagged with our
 * synthetic container id to a replay server that has no such menu.
 *
 * <p>Recognition is by container id, so it covers screens belonging to any mod,
 * not just vanilla ones.
 *
 * <p>{@code require = 0} keeps a signature change between versions from turning
 * into a crash: the injection is simply skipped and the rest still works.
 */
@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreen {

    /** GLFW key codes, kept as literals so this mixin needs no extra imports. */
    private static final int GLFW_KEY_ESCAPE = 256;
    private static final int GLFW_KEY_E = 69;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void replayui$blockMouseClicked(double mouseX, double mouseY, int button,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (replayui$isOurs()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true, require = 0)
    private void replayui$blockMouseReleased(double mouseX, double mouseY, int button,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (replayui$isOurs()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true, require = 0)
    private void replayui$blockMouseDragged(double mouseX, double mouseY, int button,
                                            double dragX, double dragY,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (replayui$isOurs()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void replayui$blockKeyPressed(int keyCode, int scanCode, int modifiers,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!replayui$isOurs()) {
            return;
        }
        // Escape is allowed through, but handled by us rather than by vanilla.
        // Letting the default path run would call closeContainer() on a menu
        // the replay server has never heard of; swallowing the key entirely
        // would leave the viewer stuck with no way to dismiss the screen.
        if (keyCode == GLFW_KEY_ESCAPE || keyCode == GLFW_KEY_E) {
            PlaybackTicker.dismissByUser();
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(true);
    }

    /**
     * The screen being closed also sends a container-close packet, and that
     * path is not covered by the input hooks above - it fires whenever
     * setScreen() swaps the screen away, including our own cleanup.
     *
     * <p>Both names are hooked because which one owns the close differs by
     * version; require = 0 means whichever does not exist is simply skipped.
     */
    @Inject(method = "removed", at = @At("HEAD"), cancellable = true, require = 0)
    private void replayui$blockRemoved(CallbackInfo ci) {
        if (replayui$isOurs()) {
            ci.cancel();
        }
    }

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true, require = 0)
    private void replayui$blockOnClose(CallbackInfo ci) {
        if (replayui$isOurs()) {
            ci.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean replayui$isOurs() {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        var menu = self.getMenu();
        return menu != null && menu.containerId == ReplayUIIds.CONTAINER_ID_BASE;
    }
}
