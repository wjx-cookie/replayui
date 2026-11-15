package com.replayui.mixin;

import com.replayui.playback.PlaybackTicker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes rebuilt containers part of Flashback's exported video.
 *
 * <p>Flashback's ExportJob removes every screen before it renders a captured
 * frame (and waits, then force-clears after five seconds, if one is still
 * there). It does that because {@code GameRenderer#render} draws
 * {@code Minecraft.screen} into the framebuffer - which is also why its "No
 * GUI" option cancels the GUI half of that very method.
 *
 * <p>So the screen is mounted at HEAD and removed at TAIL. For that one call it
 * is a completely ordinary AbstractContainerScreen, so every existing render
 * hook applies unchanged: recorded cursor coordinates, the second pointer, and
 * the recorded player in an inventory. Outside that call client.screen is
 * null again, and ExportJob's wait loop never sees it.
 *
 * <p>{@code require = 0}: if the signature differs on another version the
 * injection is skipped. The preview keeps working, exports just contain no
 * container - never a startup crash.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void replayui$mountExportScreen(DeltaTracker deltaTracker, boolean renderLevel,
                                            CallbackInfo ci) {
        PlaybackTicker.beginExportFrame(Minecraft.getInstance());
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void replayui$unmountExportScreen(DeltaTracker deltaTracker, boolean renderLevel,
                                              CallbackInfo ci) {
        PlaybackTicker.endExportFrame(Minecraft.getInstance());
    }
}
