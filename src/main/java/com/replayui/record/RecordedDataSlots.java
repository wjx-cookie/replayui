package com.replayui.record;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Remembers the integer data slots of the container currently on screen.
 *
 * <p>Furnace flame/arrow, brewing bubbles, enchantment costs, anvil cost and
 * beacon level are all just integers in {@code AbstractContainerMenu}'s data
 * slots. Vanilla's screens read them from the menu, so recording the integers
 * and putting them back on the rebuilt menu is enough - no progress bar is
 * drawn by us, and modded menus using the same mechanism work for free.
 *
 * <p>The client only receives a {@code setData} call when a value changes, not
 * every tick, so a plain "last packet" would lose everything else. Values are
 * held in a small array indexed by slot id.
 *
 * <p>Packets arrive before the client tick that samples the screen, and a new
 * container may be opened between two ticks. Incoming values therefore go into
 * a pending list and are applied by {@link #bind(int)}: if the container id
 * changed, the old array is dropped first, then the pending values are applied.
 * Whichever order the two happen in, the snapshot belongs to the right menu.
 */
public final class RecordedDataSlots {

    private static final int MAX_SLOTS = 64;

    private static int boundContainerId = Integer.MIN_VALUE;
    private static int[] values = new int[0];
    private static final List<int[]> pending = new ArrayList<>();

    private RecordedDataSlots() {
    }

    /** Called from the mixin on {@code AbstractContainerMenu#setData}. */
    public static void record(int id, int value) {
        if (id < 0 || id >= MAX_SLOTS) {
            return;
        }
        pending.add(new int[]{id, value});
        // A screen that is never captured must not let this grow forever.
        if (pending.size() > 256) {
            pending.remove(0);
        }
    }

    /** Called once per tick from {@code UiStateReader}, for every path. */
    public static void bind(int containerId) {
        if (containerId != boundContainerId) {
            boundContainerId = containerId;
            values = new int[0];
        }
        for (int[] entry : pending) {
            int id = entry[0];
            if (id >= values.length) {
                values = Arrays.copyOf(values, id + 1);
            }
            values[id] = entry[1];
        }
        pending.clear();
    }

    public static int[] snapshot() {
        return values.clone();
    }

    public static void reset() {
        boundContainerId = Integer.MIN_VALUE;
        values = new int[0];
        pending.clear();
    }
}
