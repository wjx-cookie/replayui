package com.replayui.render;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which items moved between two recorded frames.
 *
 * <p>Deliberately conservative. A move is only inferred when a stack vanishes
 * from one slot and an identical stack (same item, same count) appears in a
 * slot that was empty. Anything else - stacking on top of an existing stack,
 * splitting, crafting output, a stack that grew or shrank - is left alone
 * rather than animated wrongly. A guessed flight is worse than no flight.
 *
 * <p>If the cursor-held stack changed, no flights are produced at all: that is
 * the signature of a move that went through the player's hand, and the held
 * stack already shows it. This matches how the vanilla client presents
 * shift-clicks and hotkey swaps versus ordinary drag-and-drop.
 *
 * <p>No format change: both frames are the complete per-tick slot snapshots
 * already stored in the replay. Pure logic, no mixin engine needed to test it.
 */
public final class ItemFlightPlanner {

    /** Cap so a large container cannot turn one tick into hundreds of draws. */
    public static final int MAX_MOVES = 8;

    public static final class Move {
        public final int from;
        public final int to;
        public final ItemStack stack;

        public Move(int from, int to, ItemStack stack) {
            this.from = from;
            this.to = to;
            this.stack = stack;
        }
    }

    private ItemFlightPlanner() {
    }

    public static List<Move> plan(List<ItemStack> before, List<ItemStack> after,
                                  ItemStack carriedBefore, ItemStack carriedAfter) {
        List<Move> out = new ArrayList<>();
        if (before == null || after == null || before.size() != after.size()) {
            return out;
        }
        if (!same(carriedBefore, carriedAfter)) {
            return out;
        }

        List<Integer> vacated = new ArrayList<>();
        List<Integer> filled = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            boolean wasEmpty = isEmpty(before.get(i));
            boolean nowEmpty = isEmpty(after.get(i));
            if (!wasEmpty && nowEmpty) {
                vacated.add(i);
            } else if (wasEmpty && !nowEmpty) {
                filled.add(i);
            }
        }
        if (vacated.isEmpty() || filled.isEmpty()) {
            return out;
        }

        boolean[] claimed = new boolean[before.size()];
        for (int from : vacated) {
            ItemStack stack = before.get(from);
            for (int to : filled) {
                if (claimed[to]) {
                    continue;
                }
                if (same(stack, after.get(to))) {
                    claimed[to] = true;
                    out.add(new Move(from, to, stack));
                    break;
                }
            }
            if (out.size() >= MAX_MOVES) {
                break;
            }
        }
        return out;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    private static boolean same(ItemStack a, ItemStack b) {
        boolean aEmpty = isEmpty(a);
        boolean bEmpty = isEmpty(b);
        if (aEmpty || bEmpty) {
            return aEmpty && bEmpty;
        }
        return a.getCount() == b.getCount() && ItemStack.matches(a, b);
    }
}
