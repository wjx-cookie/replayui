package com.replayui.playback;

import com.moulberry.flashback.Flashback;
import com.replayui.ReplayUIConfig;
import com.replayui.codec.UiStateData;
import com.replayui.compat.SpatialGuiCompat;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

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

    public static void onClientTick(Minecraft client) {
        if (failures >= FAILURE_LIMIT) {
            return;
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

        UiStateData data = PlaybackState.current();
        boolean show = data != null && data.open() && PlaybackState.isTargetMatch(client);

        if (!show) {
            closeQuietly(client);
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
        // Deliberately NOT rebuilding when client.screen is some other screen:
        // that means the viewer opened something of their own (Flashback's
        // editor, a mod menu). Forcing our screen back would fight the user.
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
                failedMenuTypeId = data.menuTypeId();
                // Some failures happen after the menu was already filled, which
                // leaves the viewer holding the recorded player's items. Put the
                // real ones back now instead of waiting for the screen to close -
                // that may not happen for a long time.
                if (viewer != null && savedInventory != null) {
                    savedInventory.restore(viewer);
                    savedInventory = null;
                }
                return;
            }
            client.setScreen(built);
            ourScreen = built;
            ourMenuTypeId = data.menuTypeId();
            failedMenuTypeId = UiStateData.NO_MENU_TYPE;
            DebugLog.verbose("opened container screen type=" + data.menuTypeId());
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
        // Restore the player's container BEFORE swapping the screen away.
        // Closing a screen goes through removed()/onClose(), which end up
        // sending a container-close packet tagged with whatever
        // player.containerMenu currently is. Restoring first means that packet
        // carries the player's own id (0) instead of our synthetic 200.
        //
        // MixinAbstractContainerScreen also blocks that path outright; this
        // ordering is the belt-and-braces half, in case the mixin does not
        // apply on some version.
        LocalPlayer player = client.player;
        if (player != null) {
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
        ourMenuTypeId = UiStateData.NO_MENU_TYPE;
        failedMenuTypeId = UiStateData.NO_MENU_TYPE;
    }
}
