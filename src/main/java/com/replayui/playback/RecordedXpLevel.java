package com.replayui.playback;

import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.util.ScreenReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Makes {@code EnchantmentScreen} decide its greyed-out level buttons from the
 * recorded player's experience level instead of the viewer's.
 *
 * <p>Vanilla reads {@code minecraft.player.experienceLevel} while drawing the
 * three level buttons (and the "not enough levels" text). In a replay that
 * player is the viewer, so a level-30 spectator sees every option enabled even
 * when the recorded player was level 2. The value is already carried in the
 * sample ({@code ScreenExtras#experienceLevel()}); it was simply never used.
 *
 * <p>Rather than redirecting a particular FIELD instruction - which would need
 * the exact mapped owner and would silently miss whichever of the several reads
 * is the real one - the level is <em>borrowed</em> for exactly one
 * {@code AbstractContainerScreen#render} call: written at HEAD, restored at
 * TAIL. Every read inside that window sees the recorded value, no matter where
 * in the method it happens. The player entity is never left modified and no
 * gameplay logic runs inside the window.
 *
 * <p>Reflection keeps the field name a candidate list, so a mapping change
 * degrades to the old behaviour instead of a crash. A render that throws
 * before TAIL is covered by {@link #restoreIfStale()} on the next client tick.
 *
 * <p>Scoped to screens we rebuilt and to enchantment screens only. A container
 * the viewer opened themselves, and every other replay screen, are untouched.
 */
public final class RecordedXpLevel {

    /** Same candidates as the recording-side reader, so write matches read. */
    private static final String[] LEVEL_FIELD = {"experienceLevel", "f_36046_"};
    private static final String ENCHANT_HINT = "Enchant";

    private static boolean borrowed = false;
    private static Object borrowedFrom = null;
    private static int originalLevel = 0;
    private static int lastLogged = Integer.MIN_VALUE;
    private static boolean reportedMissing = false;

    private RecordedXpLevel() {
    }

    /**
     * Called at the HEAD of {@code render}. {@code ours} is the same check the
     * read-only input hooks use, so no second notion of "our screen" exists.
     */
    public static void borrow(AbstractContainerScreen<?> screen, boolean ours) {
        // Re-entrancy guard: a Spatial GUI panel or a nested render must not
        // borrow twice, or the inner TAIL would hand the level back too early.
        if (borrowed) {
            return;
        }
        if (!ours || screen == null || !isEnchantment(screen)) {
            return;
        }

        UiStateData data = PlaybackState.current();
        if (data == null || !data.open() || data.extras() == null) {
            return;
        }
        int recorded = data.extras().experienceLevel();
        if (recorded < 0) {
            // -1 means "not captured" (older sample or reflection miss).
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Object player = client == null ? null : client.player;
        if (player == null) {
            return;
        }

        Object before = ScreenReflect.get(player, LEVEL_FIELD);
        if (!(before instanceof Number)) {
            if (!reportedMissing) {
                reportedMissing = true;
                DebugLog.verbose("enchant xp: level field not found - viewer level stays");
            }
            return;
        }
        int original = ((Number) before).intValue();
        if (!ScreenReflect.set(player, Integer.valueOf(recorded), LEVEL_FIELD)) {
            return;
        }

        borrowed = true;
        borrowedFrom = player;
        originalLevel = original;
        if (lastLogged != recorded) {
            lastLogged = recorded;
            DebugLog.verbose("enchant xp borrowed: viewer=" + original
                    + " recorded=" + recorded + " (restored at render TAIL)");
        }
    }

    /** Called at the TAIL of {@code render}. Idempotent. */
    public static void restore() {
        if (!borrowed) {
            return;
        }
        boolean ok = ScreenReflect.set(borrowedFrom,
                Integer.valueOf(originalLevel), LEVEL_FIELD);
        borrowed = false;
        borrowedFrom = null;
        if (!ok) {
            DebugLog.warn("replayui: could not restore the viewer's experience level");
        }
    }

    /**
     * Tick-level safety net. If {@code render} throws before its TAIL (a crash
     * inside a modded screen, for instance), the borrowed value must not stay
     * on the real player for the rest of the replay.
     */
    public static void restoreIfStale() {
        restore();
    }

    /** Best-effort class-name match; covers modded enchantment screens too. */
    private static boolean isEnchantment(AbstractContainerScreen<?> screen) {
        for (Class<?> k = screen.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getSimpleName().contains(ENCHANT_HINT)) {
                return true;
            }
        }
        Object menu = screen.getMenu();
        if (menu != null) {
            for (Class<?> k = menu.getClass(); k != null; k = k.getSuperclass()) {
                if (k.getSimpleName().contains(ENCHANT_HINT)) {
                    return true;
                }
            }
        }
        return false;
    }
}
