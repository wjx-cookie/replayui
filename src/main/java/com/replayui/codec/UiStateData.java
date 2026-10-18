package com.replayui.codec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * One UI sample.
 *
 * <p>This is a <em>state</em>, not an event: applying the newest sample always
 * yields the correct picture, which makes seeking and fast-forwarding safe.
 *
 * @param ownerUuid   player this sample belongs to (mirrors how Flashback keys
 *                    its own first-person data)
 * @param open        whether a screen was open at all
 * @param menuTypeId  id in {@code BuiltInRegistries.MENU}, or -1 when closed.
 *                    Needed to rebuild the right screen (a crafting table is
 *                    not a chest).
 * @param title       screen title
 * @param items       every slot of the menu, in {@code menu.slots} order
 *                    (container slots first, then the player's). Recording and
 *                    replay both read this same source so the sizes match.
 * @param containerId original id, kept for reference only
 * @param mouseX      raw window cursor position
 * @param mouseY      raw window cursor position
 * @param blockPos    block the container is attached to; currently unused,
 *                    kept so the format can grow without a version bump
 */
public record UiStateData(
        UUID ownerUuid,
        boolean open,
        int menuTypeId,
        Component title,
        List<ItemStack> items,
        int containerId,
        double mouseX,
        double mouseY,
        BlockPos blockPos
) {

    public static final int NO_MENU_TYPE = -1;

    /**
     * Marker for the player's own inventory (the screen you get by pressing E).
     *
     * <p>It has no usable MenuType of its own: vanilla opens it with
     * {@code new InventoryScreen(player)} and never goes through
     * {@code MenuScreens}, so there is no screen factory registered for it and
     * the normal container path cannot rebuild it. Recording it as a marker
     * instead of a registry id lets the playback side take that other route.
     */
    public static final int PLAYER_INVENTORY = -2;

    public static UiStateData closed(UUID ownerUuid) {
        return new UiStateData(ownerUuid, false, NO_MENU_TYPE, Component.empty(),
                List.of(), 0, 0.0, 0.0, null);
    }

    public boolean hasMenuType() {
        return menuTypeId != NO_MENU_TYPE;
    }

    public boolean isPlayerInventory() {
        return menuTypeId == PLAYER_INVENTORY;
    }
}
