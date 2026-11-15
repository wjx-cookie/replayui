package com.replayui.mixin;

import com.moulberry.flashback.Flashback;
import com.replayui.record.RecordedDataSlots;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the integer data slots that vanilla container screens use for
 * progress indicators.
 *
 * <p>{@code AbstractContainerMenu#setData(int,int)} is public on 1.21.1 and is
 * the single place the client packet listener funnels every
 * {@code ClientboundContainerSetDataPacket} into. Hooking it avoids depending
 * on packet field names and also picks up menus that a mod fills locally.
 *
 * <p>Playback is excluded: the rebuilt menu calls the same method to restore
 * those values, and writing them back into the recording cache would be a
 * no-op at best and cross-contaminate the next recording at worst.
 *
 * <p>{@code require = 0}: a renamed method on another version silently skips
 * this and progress indicators simply stay at their defaults.
 */
@Mixin(AbstractContainerMenu.class)
public class MixinAbstractContainerMenu {

    @Inject(method = "setData", at = @At("HEAD"), require = 0)
    private void replayui$recordDataSlot(int id, int value, CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            return;
        }
        RecordedDataSlots.record(id, value);
    }
}
