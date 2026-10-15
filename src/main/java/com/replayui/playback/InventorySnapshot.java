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

    public void restore(LocalPlayer player) {
        try {
            Inventory inv = player.getInventory();
            if (inv.items.size() == items.size()) {
                write(inv.items, items);
            }
            if (inv.armor.size() == armor.size()) {
                write(inv.armor, armor);
            }
            if (inv.offhand.size() == offhand.size()) {
                write(inv.offhand, offhand);
            }
        } catch (Exception e) {
            DebugLog.warn("failed to restore viewer inventory", e);
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
