package com.replayui.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the private {@code menuType} field.
 *
 * <p>Accessor rather than {@code getType()} because that getter's presence
 * differs between versions, while the field is stable. Knowing the menu type is
 * what lets the replay rebuild a crafting table as a crafting table instead of
 * falling back to a generic chest.
 */
@Mixin(AbstractContainerMenu.class)
public interface MenuTypeAccessor {

    @Accessor("menuType")
    MenuType<?> replayui$getMenuType();
}
