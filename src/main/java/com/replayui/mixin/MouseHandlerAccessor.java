package com.replayui.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Raw (physical window) cursor position.
 *
 * <p>We store raw rather than scaled coords because that is what Spatial GUI's
 * {@code MouseHandlerUtil#captureMove} expects on the way back in.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Accessor("xpos")
    double replayui$getXpos();

    @Accessor("ypos")
    double replayui$getYpos();
}
