package com.replayui.playback;

import com.moulberry.flashback.Flashback;
import com.replayui.ReplayUIConfig;
import com.replayui.codec.UiStateData;
import com.replayui.compat.ExportCompat;
import com.replayui.compat.SpatialGuiCompat;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Drives opening and closing from the client tick.
 *
 * <p><b>The screen is never opened inside the payload receiver.</b> Flashback
 * saves and restores {@code Minecraft.screen} around every custom payload
 * (MixinClientPacketListener), so a screen opened there is closed again before
 * it is ever drawn. Doing it here, after all packet handling for the tick, is
 * the only place it survives.
 */
public final class PlaybackTicker {

    private static Screen ourScreen = null;
    private static int ourMenuTypeId = UiStateData.NO_MENU_TYPE;
    private static boolean wasInReplay = false;

    /** Sample last written into the open screen, to spot later changes. */
    private static UiStateData lastApplied = null;

    /**
     * The menu we filled, so the input hooks can recognise it.
     *
     * <p>The container route is recognisable by its synthetic container id, but
     * the player-inventory route reuses the viewer's own {@code inventoryMenu},
     * whose id is 0 - the vanilla one. Identity is the only thing that works for
     * both.
     */
    private static AbstractContainerMenu rebuiltMenu = null;

    /** Menu type we already failed to build, so we stop retrying every tick. */
    private static int failedMenuTypeId = UiStateData.NO_MENU_TYPE;

    /** Viewer's real inventory, taken before a rebuilt screen overwrites it. */
    private static InventorySnapshot savedInventory = null;

    /** Set when the viewer dismissed the screen by hand; cleared on next change. */
    private static boolean userDismissed = false;
    private static int dismissedMenuTypeId = UiStateData.NO_MENU_TYPE;

    private PlaybackTicker() {
    }

    /** Consecutive failures; at the limit we stop trying, see {@link #tick}. */
    private static int failures = 0;
    private static final int FAILURE_LIMIT = 5;

    /** Failed refreshes in a row; see the guard in {@link #tick}. */
    private static int refreshFailures = 0;
    private static final int REFRESH_FAILURE_LIMIT = 5;

    public static void onClientTick(Minecraft client) {
        if (failures >= FAILURE_LIMIT) {
            // Tripped breaker. The message logged when it tripped promises
            // "reopen the replay to try again", so leaving a replay has to
            // actually re-arm it - and it would not: tick() is what notices the
            // replay ended, and skipping tick() means the counter never resets,
            // so one unlucky exception would disable UI playback for the rest
            // of the session. isInReplay is cheap and safe to call here.
            if (Flashback.isInReplay()) {
                return;
            }
            failures = 0;
        }
        try {
            tick(client);
            failures = 0;
        } catch (Throwable t) {
            // tick() runs every frame while a replay is open. An exception here
            // would otherwise repeat 20 times a second, so: report the first
            // one, and stop after a handful rather than spamming for the whole
            // session.
            failures++;
            if (failures == 1) {
                DebugLog.warn("error while updating rebuilt screen", t);
            }
            if (failures == FAILURE_LIMIT) {
                DebugLog.warn("repeated failures rebuilding UI screens, giving up. "
                        + "Rejoin or reopen the replay to try again.");
                closeQuietly(client);
            }
        }
    }

    private static void tick(Minecraft client) {
        boolean inReplay = Flashback.isInReplay();

        if (!inReplay) {
            if (wasInReplay) {
                // Leaving a replay: drop everything, the state belongs to the
                // file that was open.
                closeQuietly(client);
                PlaybackState.clear();
                // Must go too, otherwise a dismiss from the previous replay
                // would suppress the first screen of the next one.
                userDismissed = false;
                dismissedMenuTypeId = UiStateData.NO_MENU_TYPE;
                wasInReplay = false;
            }
            return;
        }
        wasInReplay = true;

        // A stereo 3D export is running: the addon renders the world twice and
        // suppresses GUI rendering itself, so anything we put on screen now is
        // at best invisible and at worst baked into a frame that must not have
        // it. Take down whatever is open and stay out of the way until it ends.
        if (ExportCompat.isExporting()) {
            if (ourScreen != null) {
                closeQuietly(client);
            }
            return;
        }

        UiStateData data = PlaybackState.current();
        boolean show = data != null && data.open() && PlaybackState.isTargetMatch(client);

        if (!show) {
            closeQuietly(client);
            // The recorded player has no screen open any more, so a "the viewer
            // closed it by hand" memory is stale and must go.
            //
            // Without this it latches: the viewer dismisses the inventory once,
            // dismissedMenuTypeId becomes PLAYER_INVENTORY (-2), the recording
            // later closes and reopens it - and because the player inventory
            // reports -2 every single time, the guard below matches forever and
            // the screen never comes back. Chests latch the same way whenever
            // two of them are the same menu type.
            userDismissed = false;
            dismissedMenuTypeId = UiStateData.NO_MENU_TYPE;
            return;
        }

        // A screen dismissed by hand stays closed until the recorded state
        // changes, otherwise it would pop straight back on the next tick.
        if (userDismissed && data.menuTypeId() == dismissedMenuTypeId) {
            return;
        }
        userDismissed = false;
        dismissedMenuTypeId = UiStateData.NO_MENU_TYPE;

        // Clear the failure memory only once the recorded menu type actually
        // moved on to something else.
        //
        // Do NOT compare against ourMenuTypeId here: that is only assigned on a
        // successful build, so after a failure it stays NO_MENU_TYPE forever and
        // would reset the flag every tick - retrying the build and re-logging
        // the warning 20 times a second.
        if (failedMenuTypeId != UiStateData.NO_MENU_TYPE && failedMenuTypeId != data.menuTypeId()) {
            failedMenuTypeId = UiStateData.NO_MENU_TYPE;
        }

        // Rebuild when we have no screen or the recorded container changed type
        // (chest -> crafting table).
        //
        // Note the actual behaviour when the viewer has a screen of their own
        // open: weHaveNone is true and this DOES replace it. That is a
        // deliberate trade, not an oversight - skipping the build whenever
        // client.screen is non-null would stop the UI ever appearing if some
        // other mod leaves a screen open for the whole replay, and a silent
        // "never shows up" is far harder to diagnose than a screen that can be
        // dismissed. Escape is intercepted by MixinAbstractContainerScreen, so
        // the viewer can always get their screen back, and userDismissed keeps
        // us from re-popping it until the recorded state changes.
        boolean weHaveNone = ourScreen == null;
        boolean somethingClosedIt = !weHaveNone && client.screen == null;
        boolean typeChanged = data.menuTypeId() != ourMenuTypeId;
        boolean stillOurs = !weHaveNone && client.screen == ourScreen;

        boolean needsRebuild = weHaveNone
                || somethingClosedIt
                || (stillOurs && typeChanged);

        if (needsRebuild) {
            // A missing menu type would otherwise be retried every single tick.
            if (failedMenuTypeId == data.menuTypeId()) {
                return;
            }
            // Snapshot before the first fill: initializeContents writes through
            // to the viewer's real inventory, and we need the original back
            // once this screen goes away.
            LocalPlayer viewer = client.player;
            if (savedInventory == null && viewer != null) {
                savedInventory = InventorySnapshot.of(viewer);
            }

            Screen built = ContainerScreenBuilder.build(client, data);
            if (built == null) {
                // Full teardown, not just the inventory.
                //
                // MenuScreens.create assigns the menu it built to
                // player.containerMenu BEFORE the screen is constructed, so
                // every later failure - slot count mismatch, no screen
                // registered, fillMenu throwing - leaves the viewer's
                // containerMenu pointing at an orphaned synthetic menu with our
                // id (200) that no longer belongs to any screen. Restoring the
                // inventory alone does not undo that, and it lingers until
                // something else happens to overwrite it: the viewer's next
                // container click, or a close packet, would go to a menu the
                // replay server has never heard of.
                //
                // closeQuietly also puts back the items that some of those
                // failures had already written into the viewer's inventory.
                closeQuietly(client);

                // Set after, not before: closeQuietly clears this field, and it
                // has to stay set or the build is retried - and logged - every
                // single tick.
                failedMenuTypeId = data.menuTypeId();
                return;
            }
            client.setScreen(built);
            ourScreen = built;
            rebuiltMenu = built instanceof AbstractContainerScreen<?> builtScreen
                    ? builtScreen.getMenu()
                    : null;
            ourMenuTypeId = data.menuTypeId();
            lastApplied = data;
            failedMenuTypeId = UiStateData.NO_MENU_TYPE;
            DebugLog.verbose("opened container screen type=" + data.menuTypeId());
        } else if (stillOurs && !typeChanged) {
            // Screen already up and still the same container type: the replay
            // moved on but nothing about the screen itself has to change. Only
            // its contents may have - items moving in and out, being crafted,
            // being picked up. Without this the screen would stay frozen at
            // whatever was recorded when it first opened.
            if (contentsChanged(lastApplied, data)) {
                if (ContainerScreenBuilder.refresh(ourScreen, data)) {
                    lastApplied = data;
                    refreshFailures = 0;
                    DebugLog.verbose("refreshed container contents, "
                            + data.items().size() + " slots");
                } else {
                    // A refresh that keeps failing has no equivalent of the
                    // failedMenuTypeId guard the build path has, so it would be
                    // retried every tick - and fillMenu logs with a stack trace
                    // on failure, which is 20 traces a second. Take the screen
                    // down after a few and let the next rebuild start clean.
                    refreshFailures++;
                    if (refreshFailures == 1) {
                        DebugLog.warn("failed to refresh container contents");
                    }
                    if (refreshFailures >= REFRESH_FAILURE_LIMIT) {
                        DebugLog.warn("repeated refresh failures, closing the screen");
                        closeQuietly(client);
                    }
                }
            }
        }

        // Push the cursor after the screen exists: Spatial GUI resets to screen
        // centre whenever a screen opens, so this has to be refreshed every tick.
        // Skipped when mouse capture is off - injecting the recorded 0,0 would
        // park the cursor in the corner rather than leaving it alone.
        if (ReplayUIConfig.captureMouse) {
            SpatialGuiCompat.injectMouse(data.mouseX(), data.mouseY());
        }
    }

    /**
     * Whether anything visible about the open screen moved on.
     *
     * <p>Compares slot by slot. {@code ItemStack.matches} alone is not enough:
     * it ignores the stack size, so picking half a stack out of a chest - the
     * single most common thing to happen in a container - would go unnoticed.
     *
     * <p>The cursor-held stack is checked separately because it is not one of
     * the slots. It has to be, or grabbing a stack out of a chest and holding
     * it would not repaint and the item would appear to vanish.
     */
    private static boolean contentsChanged(UiStateData applied, UiStateData recorded) {
        if (applied == null) {
            return true;
        }

        ItemStack a = applied.carried();
        ItemStack b = recorded.carried();
        // carried() is only ever set from a recorded stack, but a null here
        // would NPE inside the tick, which is caught - and five of those and
        // the whole screen stops updating. Cheap to be sure.
        if (a == null || b == null) {
            return a != b;
        }
        if (a.getCount() != b.getCount() || !ItemStack.matches(a, b)) {
            return true;
        }

        List<ItemStack> appliedItems = applied.items();
        List<ItemStack> recordedItems = recorded.items();
        if (appliedItems == null || recordedItems == null) {
            return true;
        }
        if (appliedItems.size() != recordedItems.size()) {
            return true;
        }
        for (int i = 0; i < recordedItems.size(); i++) {
            ItemStack x = appliedItems.get(i);
            ItemStack y = recordedItems.get(i);
            if (x.getCount() != y.getCount() || !ItemStack.matches(x, y)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The viewer closed the screen by hand (Escape, or E which is the usual
     * inventory key). Closed through our own path rather than vanilla's, so no
     * packet is sent to a replay server that has no such container.
     */
    public static void dismissByUser() {
        try {
            userDismissed = true;
            dismissedMenuTypeId = ourMenuTypeId;
            closeQuietly(Minecraft.getInstance());
        } catch (Throwable t) {
            // Called straight from a key press inside the screen, so an
            // exception would surface in the middle of vanilla input handling.
            DebugLog.warn("error while closing rebuilt screen", t);
        }
    }

    private static void closeQuietly(Minecraft client) {
        // Order matters, and so does not doing this at all when it is not our
        // turn.
        //
        // tick() calls this every single tick that nothing should be shown,
        // which is most of a replay: the recorded player has no screen open the
        // overwhelming majority of the time. Touching the player unconditionally
        // here would therefore run our cleanup on the viewer's OWN inventory
        // menu 20 times a second - wiping the stack they just picked up in
        // their own screen, which makes items ungrabbable in a replay even
        // though nothing of ours is on screen.
        //
        // So: only ever clean up after ourselves. When we never opened anything
        // there is nothing of ours to undo and the viewer is left alone.
        boolean hadOurs = ourScreen != null || rebuiltMenu != null || savedInventory != null;

        LocalPlayer player = client.player;
        if (hadOurs && player != null) {
            // Empty the recorded cursor stack BEFORE anything is swapped.
            //
            // It was set on whichever menu we filled: the rebuilt container menu
            // on the container route, or the viewer's own inventoryMenu on the
            // player-inventory route. Switching containerMenu first would drop
            // our only reference to the container route's menu and leave the
            // recorded stack sitting on an orphaned object - and if the screen
            // then closes through vanilla paths, drop the item. So both are
            // cleared while both are still reachable.
            AbstractContainerMenu openMenu = player.containerMenu;
            if (openMenu != null && !openMenu.getCarried().isEmpty()) {
                openMenu.setCarried(ItemStack.EMPTY);
            }
            if (player.inventoryMenu != null
                    && player.inventoryMenu != openMenu
                    && !player.inventoryMenu.getCarried().isEmpty()) {
                player.inventoryMenu.setCarried(ItemStack.EMPTY);
            }

            // Restore the player's container BEFORE swapping the screen away.
            // Closing a screen goes through removed()/onClose(), which end up
            // sending a container-close packet tagged with whatever
            // player.containerMenu currently is. Restoring first means that packet
            // carries the player's own id (0) instead of our synthetic 200.
            //
            // MixinAbstractContainerScreen also blocks that path outright; this
            // ordering is the belt-and-braces half, in case the mixin does not
            // apply on some version.
            if (player.containerMenu != player.inventoryMenu) {
                player.containerMenu = player.inventoryMenu;
            }

            if (savedInventory != null) {
                savedInventory.restore(player);
                savedInventory = null;
            }
        }

        if (ourScreen != null && client.screen == ourScreen) {
            client.setScreen(null);
        }
        ourScreen = null;
        // Cleared only after setScreen(null): closing calls removed() on the
        // screen, which is where the input hooks ask whether it is ours. Nulling
        // this first would make the answer "no" and let vanilla's close path
        // through.
        rebuiltMenu = null;
        ourMenuTypeId = UiStateData.NO_MENU_TYPE;
        failedMenuTypeId = UiStateData.NO_MENU_TYPE;
        lastApplied = null;
        refreshFailures = 0;
    }

    /**
     * Whether a menu is one we filled, for the input hooks in
     * {@code MixinAbstractContainerScreen}.
     */
    public static boolean isRebuiltMenu(AbstractContainerMenu menu) {
        return menu != null && menu == rebuiltMenu;
    }
}
