package com.replayui.playback;

import com.replayui.ReplayUIIds;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds a real container screen from recorded state.
 *
 * <p>This intentionally reproduces what vanilla does when the server opens a
 * container, just with our items pre-loaded:
 * <pre>
 *   MenuType.create(id, inventory)   -> menu
 *   menu.initializeContents(...)     -> slots filled before first draw
 *   player.containerMenu = menu      -> the side effect MenuScreens.create does
 *   ScreenConstructor.create(...)    -> the screen
 * </pre>
 *
 * <p>Because the result is a genuine {@code AbstractContainerScreen}, Spatial GUI
 * classifies it under "containers" and renders it in 3D without any help from
 * us - and any mod overlay drawn on the GUI layer comes along for the ride.
 */
public final class ContainerScreenBuilder {

    private ContainerScreenBuilder() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Screen build(Minecraft client, UiStateData data) {
        if (!data.open()) {
            return null;
        }

        LocalPlayer player = client.player;
        if (player == null) {
            return null;
        }

        // Reached when a container screen was open but no MenuType could be
        // read from it - the creative inventory is the usual case, and it is
        // out of scope rather than broken.
        //
        // verbose, so it is invisible without -Dreplayui.debug=true. That is
        // deliberate: this fires for a screen type the mod does not support at
        // all, and warning about it would scold anyone who simply uses creative
        // mode. PlaybackTicker stops retrying a failed menu type, so it cannot
        // repeat either. Turn verbose on when a screen should have appeared and
        // did not.
        if (!data.hasMenuType()) {
            DebugLog.verbose("screen was open but has no menu type - skipping. "
                    + "Creative inventory and other synthetic screens are not supported.");
            return null;
        }

        // Must come before the registry lookup: PLAYER_INVENTORY is a marker,
        // not a real id, so byId() would reject it.
        if (data.isPlayerInventory()) {
            return buildPlayerInventory(player, data);
        }

        MenuType menuType = BuiltInRegistries.MENU.byId(data.menuTypeId());
        if (menuType == null) {
            // Typical cause: the replay was made with a mod container that is
            // not installed here. Skip rather than guess.
            //
            // warn, not verbose: this is the answer to "why is nothing showing"
            // and PlaybackTicker only reports it once, so it cannot spam.
            DebugLog.warn("menu type id " + data.menuTypeId()
                    + " is not registered in this game - skipping this screen. "
                    + "Usually means the replay uses a mod container you do not have installed.");
            return null;
        }

        // MenuScreens.create runs the whole vanilla path: builds the menu,
        // assigns player.containerMenu, builds the screen and opens it. Then we
        // overwrite the contents with what was recorded.
        //
        // Deliberately NOT using MenuScreens.getScreenFactory: its single-arg
        // form is a NeoForge addition and does not exist in vanilla, and the
        // 4-arg form varies between versions. create(MenuType, Minecraft, int,
        // Component) has been identical from 1.17 all the way to current, so it
        // is the one call here that cannot break on a version bump.
        // Remembered because create() hands back nothing: the only way to see
        // what it opened is to read client.screen afterwards, and when it opens
        // nothing that read returns whatever the viewer already had open.
        //
        // Without this comparison the failure paths below would call
        // setScreen(null) on the viewer's own screen - closing their pause menu
        // or chat, something we never opened and must not be closing.
        Screen before = client.screen;

        Screen screen;
        try {
            MenuScreens.create(menuType, client, ReplayUIIds.CONTAINER_ID_BASE, data.title());
            screen = client.screen;
        } catch (Exception e) {
            DebugLog.warn("failed to create screen for menu type " + data.menuTypeId(), e);
            return null;
        }

        if (screen != null && screen == before) {
            // Nothing was opened: no screen factory for this menu type in this
            // version, most likely. The viewer's own screen is left alone.
            DebugLog.warn("no screen opened for menu type " + data.menuTypeId()
                    + " - skipping");
            return null;
        }

        if (!(screen instanceof AbstractContainerScreen)) {
            // create() opened nothing, or something that is not a container -
            // most likely this menu type has no screen registered for it.
            if (screen != null) {
                // `before`, not null: whatever create() managed to open is ours
                // to undo, but the screen the viewer had up is not. Restoring
                // null here would close their pause menu or chat along with it.
                client.setScreen(before);
            }
            DebugLog.warn("no container screen registered for menu type " + data.menuTypeId()
                    + " - skipping");
            return null;
        }

        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) screen).getMenu();
        if (menu == null) {
            client.setScreen(before);
            DebugLog.warn("screen for menu type " + data.menuTypeId() + " has no menu");
            return null;
        }

        // Slot count must line up with what we recorded, otherwise the contents
        // would land in the wrong places.
        if (menu.slots.size() != data.items().size()) {
            client.setScreen(before);
            DebugLog.warn("slot count mismatch: recorded " + data.items().size()
                    + " but this menu has " + menu.slots.size()
                    + " (menu type " + data.menuTypeId() + ") - skipping");
            return null;
        }

        if (!fillMenu(menu, data.items(), data.carried())) {
            client.setScreen(before);
            return null;
        }

        // create() already did this, but restate it: if a modded screen replaced
        // it during construction the replay would misbehave on close.
        player.containerMenu = menu;

        return screen;
    }

    /**
     * The player's own inventory, opened with E.
     *
     * <p>It cannot go down the container route: vanilla builds it with
     * {@code new InventoryScreen(player)} and there is no MenuScreens factory
     * for it, so {@code MenuScreens.create} would silently do nothing. Instead
     * the recorded contents are written into the viewer's real inventory - which
     * is exactly the data source that screen reads - and the screen is then
     * constructed the vanilla way.
     *
     * <p>Writing into the viewer's inventory is the same thing the container
     * path already does for the player-inventory rows of a chest menu, so
     * {@code InventorySnapshot} in PlaybackTicker already covers restoring it.
     */
    private static Screen buildPlayerInventory(LocalPlayer player, UiStateData data) {
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        if (inventoryMenu == null) {
            DebugLog.warn("viewer has no inventory menu - skipping player inventory");
            return null;
        }

        if (inventoryMenu.slots.size() != data.items().size()) {
            DebugLog.warn("slot count mismatch: recorded " + data.items().size()
                    + " but this inventory menu has " + inventoryMenu.slots.size()
                    + " - skipping");
            return null;
        }

        if (!fillMenu(inventoryMenu, data.items(), data.carried())) {
            return null;
        }

        player.containerMenu = inventoryMenu;
        DebugLog.verbose("opened player inventory screen");
        return new InventoryScreen(player);
    }

    /**
     * Updates an already-open screen with newer recorded contents.
     *
     * <p>The screen is kept as-is and only its slots are reloaded. Rebuilding
     * instead would replace the screen every tick the contents differ: it would
     * flicker, reset scroll position, reopen on top of whatever the viewer had
     * focused, and re-run construction for no reason.
     *
     * <p>Works for both routes - a container screen opened through
     * {@code MenuScreens.create} and the player inventory, which is also an
     * {@link AbstractContainerScreen} whose menu is the viewer's own
     * {@code inventoryMenu}.
     *
     * @return true if the contents were applied
     */
    public static boolean refresh(Screen screen, UiStateData data) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }

        AbstractContainerMenu menu = containerScreen.getMenu();
        if (menu == null) {
            return false;
        }

        // Same guard as on first build: a mismatch would drop items into slots
        // that mean something else.
        if (menu.slots.size() != data.items().size()) {
            DebugLog.verbose("refresh skipped, slot count changed: screen has "
                    + menu.slots.size() + " but recording has " + data.items().size());
            return false;
        }

        return fillMenu(menu, data.items(), data.carried());
    }

    /**
     * Loads recorded items into a menu before its first frame is drawn.
     *
     * <p>initializeContents is what the vanilla client calls when a
     * container-content packet arrives, so it is the most faithful way to load
     * them. It delegates to the menu's ContainerSynchronizer, and that field is
     * {@code @Nullable} - a menu we built ourselves has none, so the call can
     * throw. Hence the fallback.
     *
     * <p>Writing slots directly is the fallback, not the preference: a Slot
     * writes through to its backing Container, which for the player-inventory
     * half is the viewer's real Inventory. InventorySnapshot in PlaybackTicker
     * is what makes that safe to do.
     */
    private static boolean fillMenu(AbstractContainerMenu menu, List<ItemStack> items, ItemStack carried) {
        // Try the vanilla entry point first, then CHECK that it actually wrote.
        //
        // A menu we built ourselves has no ContainerSynchronizer - that is only
        // set by the client packet listener for menus a server opened - and what
        // initializeContents does in that case is not something to gamble on.
        // If it dereferences the synchronizer it throws and the fallback runs;
        // if it quietly does nothing, it returns normally and the fallback would
        // NEVER run, leaving a perfectly good looking screen with nothing in it
        // and not one line in the log to explain why.
        //
        // So the result is verified instead of trusted. Slot-by-slot comparison
        // of a few dozen stacks, once per refresh at most - cheap next to a
        // feature that silently shows empty containers.
        // Copy everything before anything touches the menu.
        //
        // `items` is the list UiStateData still holds, and PlaybackTicker keeps
        // that same sample as `lastApplied` to decide later whether anything
        // changed. Copying only in the fallback below was not enough: if
        // initializeContents keeps the list it was handed rather than copying
        // into the slots, the menu ends up holding the recorded objects on the
        // path that reports success - and then a vanilla split or shrink on
        // that menu edits PlaybackState's cached sample as a side effect.
        // contentsChanged compares against that cache, so it would start
        // reporting "unchanged" for slots that did move and the screen would
        // freeze while the replay carried on.
        //
        // For the player-inventory rows the menu is backed by the viewer's real
        // Inventory, so the same aliasing would also leave foreign objects
        // sitting in their inventory until closeQuietly restores it.
        List<ItemStack> source = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            source.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        ItemStack held = carried == null ? ItemStack.EMPTY : carried.copy();

        boolean viaVanilla = false;
        try {
            // Carried is initializeContents' third argument, so passing EMPTY
            // here deliberately blanks it; the recorded stack is only put back
            // by the setCarried below. Doing it in two steps rather than
            // passing carried directly keeps the fallback path below
            // symmetrical, and covers the case where initializeContents throws
            // partway through and never reaches the carried assignment.
            menu.initializeContents(0, source, ItemStack.EMPTY);
            menu.setCarried(held.copy());
            viaVanilla = slotsMatch(menu, source);
        } catch (Throwable t) {
            DebugLog.verbose("initializeContents unavailable, writing slots directly: " + t);
        }

        if (viaVanilla) {
            return true;
        }

        try {
            menu.suppressRemoteUpdates();
            try {
                // No copy needed here - `source` already is one. Copying again
                // would be a second allocation per slot per refresh for nothing.
                for (int i = 0; i < source.size(); i++) {
                    menu.getSlot(i).set(source.get(i));
                }
            } finally {
                // finally, not straight-line: a throw partway through the
                // loop used to skip this, leaving the menu suppressed for
                // good. On the player-inventory route the menu IS the
                // viewer's own inventoryMenu, so it stayed suppressed after
                // the screen was gone - every later slot change silently
                // stopped being broadcast, and the viewer's own container
                // interactions went nowhere for the rest of the session.
                menu.resumeRemoteUpdates();
            }
            menu.setCarried(held.copy());
            return true;
        } catch (Throwable t2) {
            DebugLog.warn("failed to fill menu slots", t2);
            return false;
        }
    }

    /** Whether every slot already holds what was recorded. */
    private static boolean slotsMatch(AbstractContainerMenu menu, List<ItemStack> items) {
        if (menu.slots.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            ItemStack want = items.get(i);
            ItemStack got = menu.getSlot(i).getItem();
            if (want == null || got == null) {
                return false;
            }
            if (want.getCount() != got.getCount() || !ItemStack.matches(want, got)) {
                return false;
            }
        }
        return true;
    }
}
