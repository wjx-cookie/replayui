package com.replayui.playback;

import com.moulberry.flashback.Flashback;
import com.replayui.ReplayUIConfig;
import com.replayui.codec.UiStateData;
import com.replayui.compat.ExportCompat;
import com.replayui.compat.SpatialGuiCompat;
import com.replayui.compat.VanillaCursor;
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

    /**
     * Screen mounted only while one exported frame is being rendered.
     *
     * <p>Deliberately separate from {@link #ourScreen}: that one is a screen
     * that stays open across ticks and is driven by the normal rebuild path.
     * This one lives for a single GameRenderer#render call and is gone before
     * ExportJob's "wait until screen == null" loop can see it. Render hooks
     * must still recognise it as ours, or the recorded cursor, its icon and
     * the recorded player figure would all drop out of the exported frame.
     */
    private static Screen exportScreen = null;

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

    /**
     * Set by the detach key, cleared only by pressing it again or by leaving
     * the replay.
     *
     * <p>Deliberately different from {@link #userDismissed}. That one is a
     * momentary "not now" - the screen returns as soon as the recording shows
     * something else. This one is the editor's decision and nothing in the
     * recording overrides it: the whole point of detaching is to fly the camera
     * without a container popping back up, so the recording opening another one
     * is the exact thing being opted out of.
     */
    private static boolean detached = false;

    /**
     * Set when another screen took the display away from ours, so we wait for
     * the display to come free instead of grabbing it straight back.
     */
    private static boolean waitingForDisplay = false;

    private PlaybackTicker() {
    }

    /** Consecutive failures; at the limit we stop trying, see {@link #tick}. */
    private static int failures = 0;
    private static final int FAILURE_LIMIT = 5;

    /** Failed refreshes in a row; see the guard in {@link #tick}. */
    private static int refreshFailures = 0;
    private static final int REFRESH_FAILURE_LIMIT = 5;

    public static void onClientTick(Minecraft client) {
        // Safety net for the one-frame experience-level borrow in
        // RecordedXpLevel: if a screen's render() throws before its TAIL, the
        // next tick must still hand the viewer's own level back. No-op when
        // nothing was borrowed, so this costs nothing on ordinary ticks.
        RecordedXpLevel.restoreIfStale();

        if (!ReplayUIConfig.enabled) {
            // Hotkey switched the mod off. Take down whatever is currently
            // rebuilt - including restoring the viewer's inventory, which is
            // the part that matters, since a rebuilt player-inventory screen
            // has the recorded player's items sitting in it right now.
            //
            // Guarded on wasInReplay so this is not doing work outside a
            // replay; closeQuietly is safe to repeat anyway, it no-ops once
            // there is nothing of ours left.
            if (wasInReplay) {
                closeQuietly(client);
                userDismissed = false;
                dismissedMenuTypeId = UiStateData.NO_MENU_TYPE;
                failures = 0;
                refreshFailures = 0;

                // This branch returns before tick() runs, so tick()'s own
                // "left the replay" cleanup can never fire from here - it has
                // to be done here as well. Getting this wrong strands
                // PlaybackState and wasInReplay, and the next replay then
                // starts carrying the previous one's data.
                if (!Flashback.isInReplay()) {
                    PlaybackState.clear();
                    wasInReplay = false;
                }
                // Still inside a replay: PlaybackState deliberately survives.
                //
                // It holds the newest sample from the file, and the recording
                // only writes when the state actually changes - a player
                // standing still with a chest open emits nothing. Clearing it
                // here would leave `current` null until the recorded player
                // happens to move, which is indistinguishable from
                // "re-enabling does not work".
                //
                // userDismissed is dropped either way: toggling back on is a
                // clear "I want to see it", and honouring a stale dismiss
                // would make the toggle look broken.
            } else if (!Flashback.isInReplay()) {
                // No rebuild ever happened, so wasInReplay was never set - but
                // the payload receiver is not gated on `enabled`, so
                // PlaybackState has been filling up the whole time. Nobody else
                // is going to drop it: tick() is what normally notices a replay
                // ended, and tick() does not run while the switch is off.
                //
                // Left behind, the last sample of the replay we just quit
                // survives into the next one. isTargetMatch adopts the first
                // owner it hears from, and that sample's owner is exactly the
                // one it adopted last time - so the next replay can open by
                // showing the PREVIOUS replay's screen, and keep it up until
                // something in the new recording happens to emit a sample.
                PlaybackState.clear();
            }
            return;
        }

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

    /**
     * Whether a rebuilt screen should be on screen right now.
     *
     * <p>Two ways to say no, and they are different things:
     *
     * <ul>
     *   <li>The dedicated hotkey, for hiding the screen and flying the camera
     *       around. It stays off until it is switched back on - unlike
     *       Escape/E, which only dismisses the screen until the recording shows
     *       the next one.</li>
     *   <li>F1, Minecraft's own "hide the interface". Flashback's editor obeys
     *       the same flag, so hiding the GUI while lining up a shot now hides
     *       both of them together instead of leaving a container floating over
     *       an otherwise clean frame.</li>
     * </ul>
     *
     * <p>Checking it here rather than at the top of the tick means the rest of
     * the "nothing to show" path still runs: the screen that was up is taken
     * down through {@code closeQuietly}, which is what hands the viewer back
     * their inventory and their cursor. A screen hidden this way is not merely
     * left invisible - it is properly closed.
     */
    private static boolean showContainerUi(Minecraft client) {
        if (!ReplayUIConfig.showContainerUi || detached) {
            return false;
        }
        return client.options == null || !client.options.hideGui;
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
                detached = false;
                wasInReplay = false;
            }
            return;
        }
        wasInReplay = true;

        // Flashback is exporting. Its ExportJob waits while screen != null and
        // force-clears it after five seconds, so a rebuilt screen must NOT be
        // left on client.screen between frames - that would stall every export.
        //
        // Instead the screen exists only for the duration of one captured
        // frame: MixinGameRenderer mounts it at the head of GameRenderer#render
        // and takes it down at the tail, which is exactly where Flashback calls
        // finishFrame on the framebuffer. The guard here is the cleanup path:
        // it undoes anything a frame left behind and restores the viewer's
        // inventory before the next one is built.
        if (ExportCompat.isExporting()) {
            endExportFrame(client);
            closeQuietly(client);

            // closeQuietly() clears the recorded cursor, and that cursor still
            // has to be live while the exported frame's screen is rendering.
            // Refresh it here, after the cleanup, so the stack riding on the
            // recorded pointer and the pointer icon both land in the video.
            UiStateData exportData = PlaybackState.current();
            if (exportData != null && ReplayUIConfig.captureMouse) {
                VanillaCursor.update(exportData.mouseX(), exportData.mouseY(),
                    exportData.windowWidth(), exportData.windowHeight(),
                    isEventSample(exportData));
            }
            return;
        }

        UiStateData data = PlaybackState.current();

        // Nothing here clears {@link #detached}. Both ways it used to were
        // wrong: a fresh container opening is what the editor detached to stop
        // happening, and re-attaching through Flashback's own controls is their
        // call to make, not ours to reinterpret on their behalf. Only the key
        // again, or leaving the replay, ends it.
        boolean recordedOpen = data != null && data.open();

        boolean show = recordedOpen && PlaybackState.isTargetMatch(client)
                && showContainerUi(client);

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
        // Something else took the display while our screen was supposed to be up
        // - chat, or another mod's screen.
        //
        // This was the one combination the rebuild test did not cover: with
        // ourScreen set, and client.screen neither null nor ours, neither the
        // rebuild branch nor the refresh branch below could ever run. The tick
        // became a no-op that never ended on its own - no content updates, and
        // the viewer's real inventory still holding the recorded player's items,
        // because closeQuietly is what puts it back and nothing called it.
        //
        // Yielding, not fighting. The first fix here rebuilt immediately, which
        // did get the screen back - and also meant the viewer's chat closed
        // again on the very next tick, so chat and the pause menu were simply
        // unusable for as long as a container was on screen. Taking the display
        // back instantly is not a fix, it is a fight the viewer loses.
        //
        // So: tear ours down right away - that is what puts the viewer's
        // inventory back - and then wait until the display is free again. Only
        // the displacement case yields; a screen that is already open the first
        // time we want to build is still replaced, so a mod that parks a screen
        // open for a whole replay cannot stop the UI from ever appearing.
        boolean displacedByOther = !weHaveNone && client.screen != null && client.screen != ourScreen;
        if (displacedByOther) {
            closeQuietly(client);
            waitingForDisplay = true;
            return;
        }
        if (waitingForDisplay) {
            if (client.screen != null) {
                return;
            }
            waitingForDisplay = false;
        }

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
            // Only when it is not already the current one.
            //
            // ContainerScreenBuilder goes through MenuScreens.create, which
            // installs the screen itself - by the time build() returns, built IS
            // client.screen. Calling setScreen with that same instance would
            // run removed() on it and then init() again: the screen is torn down
            // and rebuilt every time we rebuild, resetting scroll and focus, and
            // every other mod that listens for removed() gets a close event for
            // a screen that never actually closed.
            //
            // The player-inventory route is different: it hands back a bare
            // new InventoryScreen(player) that nothing has installed yet, so
            // that one does need the call.
            if (client.screen != built) {
                client.setScreen(built);
            }
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

                        // Without this the screen comes straight back.
                        //
                        // closeQuietly clears ourScreen, so the next tick sees
                        // "we have none" and rebuilds the same menu type from
                        // scratch - which fills fine and then fails to refresh
                        // again. Five ticks later it closes and rebuilds once
                        // more: a loop that never ends, logging a warning every
                        // cycle for as long as the replay is open.
                        //
                        // The build path has failedMenuTypeId for exactly this
                        // and it is the only thing that stops the retry, so the
                        // type has to be recorded here too. Like the build
                        // path, it is released once the recorded menu type
                        // moves on to something else.
                        failedMenuTypeId = data.menuTypeId();
                    }
                }
            }
        }

        // Refreshed every tick: Spatial GUI resets to screen centre whenever a
        // screen opens, and the recorded cursor moves on its own anyway.
        // Skipped when mouse capture is off - a recorded 0,0 would park the
        // cursor in the corner rather than leaving the screen alone.
        if (ReplayUIConfig.captureMouse) {
            SpatialGuiCompat.injectMouse(data.mouseX(), data.mouseY());
            VanillaCursor.update(data.mouseX(), data.mouseY(),
                    data.windowWidth(), data.windowHeight(),
                    isEventSample(data));
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

        // Creative scroll / search / page changes and lectern page turns do
        // not touch any slot, so without this the screen would never refresh.
        if (!java.util.Objects.equals(applied.extras(), recorded.extras())) {
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

    /**
     * The detach key: take the screen down, hand the camera back, and keep the
     * screen down until the editor says otherwise.
     *
     * <p>This is the way out of a screen the editor cannot otherwise drive.
     * While one is up the viewer's clicks and keys are swallowed, so without a
     * key of its own the only move would be Escape - and Escape only hides the
     * current screen, which comes straight back.
     *
     * <p>Pressing it a second time is the only way back. Nothing the recording
     * does re-enables it, because flying the camera with a container opening
     * itself every few seconds is exactly what detaching is for.
     *
     * @return {@code true} if this press detached, {@code false} if it put the
     *         screen replay back on.
     */
    public static boolean detach() {
        try {
            Minecraft client = Minecraft.getInstance();

            if (detached) {
                // Second press: the editor wants the screens back.
                //
                // The camera is deliberately left wherever they flew it. Free
                // flight is not a mistake to undo, and following someone again
                // is Flashback's own control to reach for.
                detached = false;
                DebugLog.info("re-attached: screen replay on again");
                return false;
            }

            detached = true;
            closeQuietly(client);

            // Hand the camera back too, which is the whole point of the key.
            //
            // Flashback decides "the editor is spectating someone" by looking at
            // the camera entity: getSpectatingPlayer() returns that player only
            // while the camera entity is somebody other than the viewer
            // (Flashback.java:1065). So pointing the camera at the viewer's own
            // entity is what ends spectating and restores the free camera - and
            // it is the very call Flashback makes when it moves the camera
            // itself (Flashback.java:518), just with our own entity instead.
            //
            // Doing it here rather than leaving it to the editor matters because
            // a rebuilt screen swallows clicks and drags: while one is up, the
            // editor cannot fly the camera, because Flashback's camera drag
            // works by dragging in the world. Hiding the screen is not enough,
            // it has to be the camera that comes back.
            if (client.player != null) {
                client.setCameraEntity(client.player);
            }

            DebugLog.info("detached: screen closed, camera returned to free flight");
            return true;
        } catch (Throwable t) {
            DebugLog.warn("error while detaching", t);
            return detached;
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

        // Stop drawing the screen from the recorded cursor. There is no
        // position to hand back - the viewer's own pointer was never moved -
        // but the recorded one belongs to a screen that is going away, and
        // every exit path comes through here.
        if (hadOurs) {
            VanillaCursor.clear();
            // The creative tab is a static field on the vanilla screen class.
            // Give the viewer's own selection back on every exit path.
            SpecialScreens.release();
        }

        LocalPlayer player = client.player;

        // Nobody to hand the inventory back to: the world went away first,
        // which is what happens when a replay is closed abruptly. The snapshot
        // has to be dropped rather than kept - it is one of the three things
        // that make hadOurs true, so keeping it means a later tick, by then
        // running with a different player in a different world or replay,
        // would restore a stranger's items over that player's own.
        if (hadOurs && player == null) {
            savedInventory = null;
        }

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
        waitingForDisplay = false;
    }

    /**
     * Whether a menu is one we filled, for the input hooks in
     * {@code MixinAbstractContainerScreen}.
     */
    public static boolean isRebuiltMenu(AbstractContainerMenu menu) {
        return menu != null && menu == rebuiltMenu;
    }

    /**
     * Mount the rebuilt container for the frame Flashback is about to capture.
     *
     * <p>Called from MixinGameRenderer at the head of
     * {@code GameRenderer#render}. Flashback's export loop clears every screen
     * before rendering a frame, precisely because GameRenderer draws
     * {@code Minecraft.screen} into the framebuffer. Putting ours back for just
     * this one call is what makes the container appear in the exported video;
     * taking it down at the tail keeps the loop from ever waiting on it.
     *
     * <p>All the existing render hooks apply automatically: the screen is an
     * ordinary AbstractContainerScreen, so MixinContainerCursor swaps its
     * coordinates, RecordedCursorIcon draws the second pointer, and
     * MixinInventoryScreen draws the recorded player. Nothing here re-implements
     * any of that.
     */
    public static void beginExportFrame(Minecraft client) {
        exportScreen = null;
        if (client == null || !ReplayUIConfig.enabled
                || !ReplayUIConfig.includeUiInExport
                || !ExportCompat.isExporting()
                || ExportCompat.exportHidesGui()) {
            return;
        }
        // The same two "no UI" decisions the normal path honours. F1 in
        // particular must win: it is the editor's way of getting a clean frame.
        if (!ReplayUIConfig.showContainerUi || detached
                || (client.options != null && client.options.hideGui)) {
            return;
        }

        UiStateData data = PlaybackState.current();
        if (data == null || !data.open() || !PlaybackState.isTargetMatch(client)) {
            return;
        }

        LocalPlayer viewer = client.player;
        if (viewer == null) {
            return;
        }
        // build() writes the recorded contents into the viewer's real
        // inventory (the player-inventory route always does). Snapshot it so
        // the export guard can put it back before the next frame.
        if (savedInventory == null) {
            savedInventory = InventorySnapshot.of(viewer);
        }

        if (ReplayUIConfig.captureMouse) {
            VanillaCursor.update(data.mouseX(), data.mouseY(),
                    data.windowWidth(), data.windowHeight(),
                    isEventSample(data));
        }

        Screen built = ContainerScreenBuilder.build(client, data);
        if (built == null) {
            return;
        }
        exportScreen = built;
        // MenuScreens.create installs the screen itself on the container route;
        // the player-inventory route hands back a bare screen. Only call
        // setScreen when needed, so a rebuilt screen is not torn down and
        // re-initialised twice in one frame.
        if (client.screen != built) {
            client.setScreen(built);
        }
    }

    /**
     * Take the exported frame's screen down again, before anything outside
     * GameRenderer#render can observe a non-null screen.
     *
     * <p>Safe to call repeatedly, including from the export guard on the next
     * tick: if Flashback cancelled the GUI half of render with "No GUI", the
     * tail hook never runs and this is what cleans up the leftover.
     */
    public static void endExportFrame(Minecraft client) {
        Screen mounted = exportScreen;
        exportScreen = null;
        if (mounted == null || client == null) {
            return;
        }
        if (client.screen == mounted) {
            client.setScreen(null);
        }
    }

    /**
     * Whether a screen is the one we rebuilt, so render hooks can tell it apart
     * from an inventory the viewer opened themselves.
     */
    public static boolean isOurScreen(Screen screen) {
        return screen != null && (screen == ourScreen || screen == exportScreen);
    }

    /** Event samples snap the cursor; ordinary ones are eased. */
    private static boolean isEventSample(UiStateData data) {
        return ReplayUIConfig.pinOnInteraction
                && data.eventKind() != UiStateData.EVENT_NONE;
    }
}
