package com.replayui.playback;

import com.replayui.log.DebugLog;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
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

    private InventorySnapshot(List<ItemStack> items, List<ItemStack> armor, List<ItemStack> offhand) {
        this.items = items;
        this.armor = armor;
        this.offhand = offhand;
    }

    public static InventorySnapshot of(LocalPlayer player) {
        Inventory inv = player.getInventory();
        return new InventorySnapshot(copy(inv.items), copy(inv.armor), copy(inv.offhand));
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

            boolean all = itemsOk && armorOk && offhandOk;
            if (all) {
                DebugLog.verbose("viewer inventory restored"
                        + " (items=" + items.size()
                        + " armor=" + armor.size()
                        + " offhand=" + offhand.size() + ")");
            } else {
                // Not verbose: this is the case where the viewer is left holding
                // someone else's items, which needs to be findable in the log.
                DebugLog.warn("viewer inventory only partially restored"
                        + " - items=" + (itemsOk ? "ok" : "SKIPPED")
                        + " armor=" + (armorOk ? "ok" : "SKIPPED")
                        + " offhand=" + (offhandOk ? "ok" : "SKIPPED")
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
