package com.replayui.render;

import com.replayui.ReplayUIConfig;
import com.replayui.codec.UiStateData;
import com.replayui.mixin.ContainerScreenAccessor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the short flight of an item between two slots.
 *
 * <p>The item is drawn with the same {@code GuiGraphics} the screen uses and
 * inside the screen's own render call, so any transform on it - Spatial GUI's
 * 3D panel above all - applies to the flying stack too. No extra layer, no
 * framebuffer work.
 *
 * <p>Slot positions come from the menu's real {@code Slot.x / Slot.y} plus the
 * screen's {@code leftPos / topPos}, read through an accessor rather than
 * guessed. Both fields are public / protected on the vanilla classes and are
 * what {@code AbstractContainerScreen#renderSlot} itself uses.
 */
public final class ItemFlightAnimator {

    /** Slightly longer than a tick, so the flight is visible at 20 TPS. */
    public static final long DURATION_NANOS = 200_000_000L;

    private static final List<Flight> active = new ArrayList<>();

    public static final class Flight {
        public final int from;
        public final int to;
        public final ItemStack stack;
        public final long start;

        Flight(int from, int to, ItemStack stack, long start) {
            this.from = from;
            this.to = to;
            this.stack = stack;
            this.start = start;
        }

        float progress(long now) {
            long elapsed = now - start;
            if (elapsed <= 0L) {
                return 0.0f;
            }
            if (elapsed >= DURATION_NANOS) {
                return 1.0f;
            }
            return (float) elapsed / (float) DURATION_NANOS;
        }
    }

    private ItemFlightAnimator() {
    }

    /** Called for every incoming sample, in preview and during export alike. */
    public static void onSamples(UiStateData previous, UiStateData next) {
        active.clear();
        if (!ReplayUIConfig.animateItemMoves || previous == null || next == null) {
            return;
        }
        if (!previous.open() || !next.open()
                || previous.menuTypeId() != next.menuTypeId()) {
            return;
        }
        List<ItemFlightPlanner.Move> moves = ItemFlightPlanner.plan(
                previous.items(), next.items(), previous.carried(), next.carried());
        long now = System.nanoTime();
        for (ItemFlightPlanner.Move move : moves) {
            active.add(new Flight(move.from, move.to, move.stack, now));
        }
    }

    public static int activeCount() {
        return active.size();
    }

    public static void draw(GuiGraphics graphics, AbstractContainerScreen<?> screen,
                            boolean ourScreen) {
        if (!ourScreen || !ReplayUIConfig.animateItemMoves
                || graphics == null || screen == null || active.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (int i = active.size() - 1; i >= 0; i--) {
            if (now - active.get(i).start >= DURATION_NANOS) {
                active.remove(i);
            }
        }
        if (active.isEmpty()) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        if (menu == null || menu.slots == null) {
            return;
        }
        int left;
        int top;
        try {
            ContainerScreenAccessor accessor = (ContainerScreenAccessor) screen;
            left = accessor.replayui$leftPos();
            top = accessor.replayui$topPos();
        } catch (Throwable t) {
            // Accessor not applied on this version: skip the animation rather
            // than draw stacks at (0,0) or crash the frame.
            return;
        }

        for (Flight flight : active) {
            if (flight.from < 0 || flight.to < 0
                    || flight.from >= menu.slots.size()
                    || flight.to >= menu.slots.size()) {
                continue;
            }
            Slot a = menu.slots.get(flight.from);
            Slot b = menu.slots.get(flight.to);
            float t = flight.progress(now);
            int x = Math.round(left + a.x + (b.x - a.x) * t);
            int y = Math.round(top + a.y + (b.y - a.y) * t);
            graphics.renderItem(flight.stack, x, y);
        }
    }

    public static void clear() {
        active.clear();
    }
}
