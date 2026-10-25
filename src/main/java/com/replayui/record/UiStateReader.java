package com.replayui.record;

import com.replayui.ReplayUIConfig;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.mixin.MenuTypeAccessor;
import com.replayui.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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

    /** Reported once, so a broken cursor probe cannot warn every tick. */
    private static boolean mouseFailed = false;

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

        // The player's own inventory needs to be recognised here rather than by
        // its MenuType: vanilla never opens it through MenuScreens, so there is
        // no screen factory for whatever type it reports, and the container path
        // on playback would have nothing to build. Identity is the reliable test
        // - it is the menu the player always owns - with the screen class as a
        // fallback in case a version wires it differently.
        boolean isPlayerInventory = client.player != null && menu == client.player.inventoryMenu;
        if (!isPlayerInventory) {
            isPlayerInventory = screen instanceof InventoryScreen;
        }

        int menuTypeId;
        if (isPlayerInventory) {
            menuTypeId = UiStateData.PLAYER_INVENTORY;
        } else {
            menuTypeId = menuType != null
                    ? BuiltInRegistries.MENU.getId(menuType)
                    : UiStateData.NO_MENU_TYPE;
        }

        // Walk menu.slots rather than getItems(): that keeps the recording side
        // and ContainerScreenBuilder reading exactly the same source, so the
        // sizes always line up regardless of how a given menu implements
        // getItems(). Copy because the writer runs on another thread.
        List<ItemStack> items = new ArrayList<>(menu.slots.size());
        for (Slot slot : menu.slots) {
            items.add(slot.getItem().copy());
        }

        // Not part of menu.slots - it is the stack floating under the cursor,
        // held in its own field on the menu and drawn there every frame.
        ItemStack carried = menu.getCarried().copy();

        // Kept apart from the rest: losing the cursor is a cosmetic downgrade,
        // losing the whole sample is not. A cast failure here (the accessor
        // mixin not applying) or a null handler would otherwise be caught by
        // UiSampler's catch-all and disable sampling for the entire session -
        // no UI recorded at all, because two doubles could not be read.
        double rawX = 0.0;
        double rawY = 0.0;
        try {
            MouseHandlerAccessor mouse = (MouseHandlerAccessor) client.mouseHandler;
            rawX = mouse.replayui$getXpos();
            rawY = mouse.replayui$getYpos();
        } catch (Throwable t) {
            if (!mouseFailed) {
                mouseFailed = true;
                // Turn the flag off rather than only saying so.
                //
                // The warning below used to claim "mouse playback disabled" and
                // nothing actually disabled it. The probe failing means rawX /
                // rawY stay 0.0, so every sample recorded from here on carries
                // the corner of the window - and on playback PlaybackTicker
                // feeds those into SpatialGuiCompat every tick with
                // captureMouse still true, pinning the recorded cursor to
                // (0,0) for the whole replay. Clearing the flag makes the
                // message true: PlaybackTicker then skips injection entirely
                // and the screen still replays, just without a cursor.
                ReplayUIConfig.captureMouse = false;
                DebugLog.warn("could not read cursor position, "
                        + "mouse playback disabled for this session", t);
            }
        }

        // blockPos left null: positioning is solved by aligning the viewer's
        // yaw with the recorded player, so the block is not needed.
        return new UiStateData(ownerUuid, true, menuTypeId, containerScreen.getTitle(),
                items, carried, menu.containerId, rawX, rawY, null);
    }
}
