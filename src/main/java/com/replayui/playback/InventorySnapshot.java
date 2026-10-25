package com.replayui.playback;

import com.replayui.log.DebugLog;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Saves and restores the viewer's real inventory around a rebuilt screen.
 *
 * <p>Why this exists: {@code AbstractContainerMenu#initializeContents} is the
 * vanilla entry point the client uses when a container-content packet arrives,
 * and it writes through to whatever {@code Container} backs each slot. The
 * player-inventory half of a menu is backed by the viewer's actual
 * {@link Inventory}, so filling a rebuilt menu overwrites the viewer's own
 * items with the recorded player's.
 *
 * <p>That overwrite is what makes the screen show the recorded contents, so it
 * has to happen - but it must not outlive the screen. This saves the real
 * contents first and puts them back when the screen closes.
 */
public final class InventorySnapshot {

    private final List<ItemStack> items;
    private final List<ItemStack> armor;
    private final List<ItemStack> offhand;

    /**
     * Every slot of the viewer's {@code inventoryMenu}, kept separately because
     * it is not the same set as the three groups above.
     *
     * <p>The inventory menu owns slots that belong to no {@link Inventory}: the
     * 2x2 crafting grid and its result. {@code fillMenu} writes into them just
     * like any other slot, but they are not in items / armor / offhand, so
     * restoring only those three left the recorded player's crafting leftovers
     * sitting in the viewer's real crafting grid after the screen closed.
     *
     * <p>Taken as a whole menu rather than by index on purpose: the layout is
     * not something to hardcode when copying all of it is this cheap.
     */
    private final List<ItemStack> menuSlots;

    /**
     * What the viewer was holding on the cursor, which is not part of any of
     * the groups above.
     *
     * <p>It lives on {@code player.containerMenu} as its own field, and
     * {@code closeQuietly} deliberately blanks it on the way out - that is how
     * the recorded stack the menu was carrying gets removed. Without saving it
     * here, whatever the viewer happened to be holding before the screen opened
     * was cleared along with it and never came back: a silent loss of an item,
     * with nothing in the log because every other group restored fine.
     */
    private final ItemStack carried;

    private InventorySnapshot(List<ItemStack> items, List<ItemStack> armor,
                              List<ItemStack> offhand, List<ItemStack> menuSlots,
                              ItemStack carried) {
        this.items = items;
        this.armor = armor;
        this.offhand = offhand;
        this.menuSlots = menuSlots;
        this.carried = carried;
    }

    public static InventorySnapshot of(LocalPlayer player) {
        Inventory inv = player.getInventory();
        return new InventorySnapshot(copy(inv.items), copy(inv.armor), copy(inv.offhand),
                copyMenuSlots(player), copyCarried(player));
    }

    private static ItemStack copyCarried(LocalPlayer player) {
        if (player.containerMenu == null) {
            return ItemStack.EMPTY;
        }
        ItemStack held = player.containerMenu.getCarried();
        return held == null ? ItemStack.EMPTY : held.copy();
    }

    /**
     * Copies the viewer's inventory-menu slots, all of them.
     *
     * <p>No registry or index knowledge needed - this is the menu the player
     * always has, and the snapshot is only ever put back into that same menu in
     * the same session, so a plain positional copy is enough.
     */
    private static List<ItemStack> copyMenuSlots(LocalPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        if (player.inventoryMenu == null) {
            return out;
        }
        for (Slot slot : player.inventoryMenu.slots) {
            out.add(slot.getItem().copy());
        }
        return out;
    }

    /**
     * Puts the menu slots back. Counted separately from the three groups so the
     * log can say which part did not make it.
     */
    private boolean restoreMenuSlots(LocalPlayer player) {
        if (player.inventoryMenu == null) {
            return menuSlots.isEmpty();
        }
        if (player.inventoryMenu.slots.size() != menuSlots.size()) {
            return false;
        }
        for (int i = 0; i < menuSlots.size(); i++) {
            player.inventoryMenu.getSlot(i).set(menuSlots.get(i).copy());
        }
        return true;
    }

    /**
     * Puts the viewer's own items back.
     *
     * <p>Returns whether every group was restored, so the caller can say so out
     * loud. That matters more than it looks: the player-inventory path writes
     * into the viewer's real {@link Inventory}, and the only way to be sure it
     * was put back - as opposed to assuming it was - is to check. A silent
     * mismatch here would leave the viewer holding the recorded player's items
     * with nothing in the log to explain it.
     *
     * <p>verbose rather than warn on success: this runs on every close, and a
     * normal session should stay quiet.
     */
    public boolean restore(LocalPlayer player) {
        try {
            Inventory inv = player.getInventory();
            boolean itemsOk = false;
            boolean armorOk = false;
            boolean offhandOk = false;
            boolean menuOk = false;

            if (inv.items.size() == items.size()) {
                write(inv.items, items);
                itemsOk = true;
            }
            if (inv.armor.size() == armor.size()) {
                write(inv.armor, armor);
                armorOk = true;
            }
            if (inv.offhand.size() == offhand.size()) {
                write(inv.offhand, offhand);
                offhandOk = true;
            }
            menuOk = restoreMenuSlots(player);

            // Must be restored too: closeQuietly empties the carried stack
            // before swapping the menu back, and until this was saved the
            // viewer's own held item went with it.
            if (player.containerMenu != null) {
                player.containerMenu.setCarried(carried.copy());
            }

            boolean all = itemsOk && armorOk && offhandOk && menuOk;
            if (all) {
                DebugLog.verbose("viewer inventory restored"
                        + " (items=" + items.size()
                        + " armor=" + armor.size()
                        + " offhand=" + offhand.size()
                        + " menuSlots=" + menuSlots.size() + ")");
            } else {
                // Not verbose: this is the case where the viewer is left holding
                // someone else's items, which needs to be findable in the log.
                DebugLog.warn("viewer inventory only partially restored"
                        + " - items=" + (itemsOk ? "ok" : "SKIPPED")
                        + " armor=" + (armorOk ? "ok" : "SKIPPED")
                        + " offhand=" + (offhandOk ? "ok" : "SKIPPED")
                        + " menuSlots=" + (menuOk ? "ok" : "SKIPPED")
                        + ". The viewer may still be holding the recorded player's items.");
            }
            return all;
        } catch (Exception e) {
            DebugLog.warn("failed to restore viewer inventory", e);
            return false;
        }
    }

    private static List<ItemStack> copy(NonNullList<ItemStack> source) {
        List<ItemStack> out = new ArrayList<>(source.size());
        for (ItemStack stack : source) {
            out.add(stack.copy());
        }
        return out;
    }

    private static void write(NonNullList<ItemStack> target, List<ItemStack> source) {
        for (int i = 0; i < source.size(); i++) {
            target.set(i, source.get(i).copy());
        }
    }
}
