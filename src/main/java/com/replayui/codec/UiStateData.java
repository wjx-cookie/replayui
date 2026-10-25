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
 * @param carried     the stack riding on the cursor, which is deliberately NOT
 *                    one of {@code items}: vanilla keeps it in its own field on
 *                    the menu ({@code menu.getCarried()}) and draws it at the
 *                    cursor every frame. Leaving it out makes grabbing and
 *                    placing items look wrong - the stack would vanish from the
 *                    slot and reappear somewhere else without the floating part
 *                    in between. {@code ItemStack.EMPTY} when nothing is held.
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
        ItemStack carried,
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
                List.of(), ItemStack.EMPTY, 0, 0.0, 0.0, null);
    }

    public boolean hasMenuType() {
        return menuTypeId != NO_MENU_TYPE;
    }

    public boolean isPlayerInventory() {
        return menuTypeId == PLAYER_INVENTORY;
    }

    /**
     * Value comparison, safe to use for de-duplication.
     *
     * <p>Deliberately not {@code equals}. {@code ItemStack} does <em>not</em>
     * override it in 1.21.1 - the mapped method list has matches /
     * isSameItem / isSameItemSameComponents and no equals - so the equals this
     * record generates falls through to identity on every stack. And
     * {@code UiStateReader} copies each stack every tick, so the comparison
     * never matched: the de-dup in {@code UiSampler} silently never fired and a
     * full sample was written 20 times a second for as long as a screen was
     * open.
     */
    public boolean sameAs(UiStateData other) {
        if (other == null) {
            return false;
        }
        if (open != other.open || menuTypeId != other.menuTypeId) {
            return false;
        }
        if (mouseX != other.mouseX || mouseY != other.mouseY) {
            return false;
        }
        if (blockPos == null ? other.blockPos != null : !blockPos.equals(other.blockPos)) {
            return false;
        }
        if (ownerUuid == null ? other.ownerUuid != null : !ownerUuid.equals(other.ownerUuid)) {
            return false;
        }
        // Component has no dependable equals either; the rendered text is close
        // enough for "did anything visible change", and it catches a container
        // being renamed while it is open.
        String thisTitle = title == null ? null : title.getString();
        String otherTitle = other.title == null ? null : other.title.getString();
        if (thisTitle == null ? otherTitle != null : !thisTitle.equals(otherTitle)) {
            return false;
        }
        if (!sameStacks(items, other.items)) {
            return false;
        }
        return sameStack(carried, other.carried);
    }

    private static boolean sameStacks(List<ItemStack> a, List<ItemStack> b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!sameStack(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count is compared on its own rather than trusted to
     * {@link ItemStack#matches}: whether that one covers the count has changed
     * across versions and is not worth betting on. Taking half a stack out of a
     * chest is the most common thing that happens in a container, so a
     * comparison that could miss it is worse than a redundant check.
     */
    private static boolean sameStack(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getCount() == b.getCount() && ItemStack.matches(a, b);
    }
}
