package com.replayui.record;

import com.replayui.codec.UiStateData;
import com.replayui.mixin.MenuTypeAccessor;
import com.replayui.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Snapshots whatever container screen is currently open.
 *
 * <p>Deliberately reads through the vanilla {@link AbstractContainerScreen} /
 * {@link AbstractContainerMenu} types instead of anything mod specific: every
 * mod container that plays by the normal rules becomes recordable for free.
 */
public final class UiStateReader {

    private UiStateReader() {
    }

    public static UiStateData capture(Minecraft client, UUID ownerUuid) {
        Screen screen = client.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return UiStateData.closed(ownerUuid);
        }

        AbstractContainerMenu menu = containerScreen.getMenu();
        if (menu == null) {
            return UiStateData.closed(ownerUuid);
        }

        MenuType<?> menuType = ((MenuTypeAccessor) menu).replayui$getMenuType();
        int menuTypeId = menuType != null
                ? BuiltInRegistries.MENU.getId(menuType)
                : UiStateData.NO_MENU_TYPE;

        // Walk menu.slots rather than getItems(): that keeps the recording side
        // and ContainerScreenBuilder reading exactly the same source, so the
        // sizes always line up regardless of how a given menu implements
        // getItems(). Copy because the writer runs on another thread.
        List<ItemStack> items = new ArrayList<>(menu.slots.size());
        for (Slot slot : menu.slots) {
            items.add(slot.getItem().copy());
        }

        MouseHandlerAccessor mouse = (MouseHandlerAccessor) client.mouseHandler;
        double rawX = mouse.replayui$getXpos();
        double rawY = mouse.replayui$getYpos();

        // blockPos left null: positioning is solved by aligning the viewer's
        // yaw with the recorded player, so the block is not needed.
        return new UiStateData(ownerUuid, true, menuTypeId, containerScreen.getTitle(),
                items, menu.containerId, rawX, rawY, null);
    }
}
