package com.replayui.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Raw (physical window) cursor position.
 *
 * <p>We store raw rather than scaled coords because that is what MouseHandler
 * holds natively, and what Spatial GUI's {@code MouseHandlerUtil} expects on
 * the way back in.
 *
 * <p>The setter is what makes the cursor visible at all on the plain 2D path.
 * Vanilla derives the scaled {@code mouseX / mouseY} it hands to
 * {@code Screen#render} from these two fields, so without writing back here the
 * renderer keeps using the viewer's own pointer: the stack held by the recorded
 * player is drawn wherever the viewer happens to be pointing, and snaps onto
 * the viewer's cursor the moment that cursor enters the window.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Accessor("xpos")
    double replayui$getXpos();

    @Accessor("ypos")
    double replayui$getYpos();

    @Accessor("xpos")
    void replayui$setXpos(double value);

    @Accessor("ypos")
    void replayui$setYpos(double value);
}
