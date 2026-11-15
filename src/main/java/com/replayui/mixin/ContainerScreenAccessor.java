package com.replayui.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The GUI origin of a container screen. Needed to place a flying item at the
 * real position of a slot; {@code AbstractContainerScreen#renderSlot} uses
 * exactly these two fields plus {@code Slot.x / Slot.y}.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {

    @Accessor("leftPos")
    int replayui$leftPos();

    @Accessor("topPos")
    int replayui$topPos();
}
