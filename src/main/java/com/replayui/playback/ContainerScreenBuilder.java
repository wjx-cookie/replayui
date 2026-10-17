package com.replayui.playback;

import com.replayui.ReplayUIIds;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

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
        if (!data.open() || !data.hasMenuType()) {
            return null;
        }

        LocalPlayer player = client.player;
        if (player == null) {
            return null;
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
        Screen screen;
        try {
            MenuScreens.create(menuType, client, ReplayUIIds.CONTAINER_ID_BASE, data.title());
            screen = client.screen;
        } catch (Exception e) {
            DebugLog.warn("failed to create screen for menu type " + data.menuTypeId(), e);
            return null;
        }

        if (!(screen instanceof AbstractContainerScreen)) {
            // create() opened nothing, or something that is not a container -
            // most likely this menu type has no screen registered for it.
            if (screen != null) {
                client.setScreen(null);
            }
            DebugLog.warn("no container screen registered for menu type " + data.menuTypeId()
                    + " - skipping");
            return null;
        }

        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) screen).getMenu();
        if (menu == null) {
            client.setScreen(null);
            DebugLog.warn("screen for menu type " + data.menuTypeId() + " has no menu");
            return null;
        }

        // Slot count must line up with what we recorded, otherwise the contents
        // would land in the wrong places.
        if (menu.slots.size() != data.items().size()) {
            client.setScreen(null);
            DebugLog.warn("slot count mismatch: recorded " + data.items().size()
                    + " but this menu has " + menu.slots.size()
                    + " (menu type " + data.menuTypeId() + ") - skipping");
            return null;
        }

        // Fill before the first frame is drawn.
        //
        // initializeContents is what the vanilla client calls when a
        // container-content packet arrives, so it is the most faithful way to
        // load our items. It delegates to the menu's ContainerSynchronizer, and
        // that field is @Nullable - a menu built this way has none, so the call
        // can throw. Hence the fallback.
        //
        // Writing slots directly is the fallback, not the preference: a Slot
        // writes through to its backing Container, which for the player-inventory
        // half is the viewer's real Inventory. InventorySnapshot in
        // PlaybackTicker is what makes that safe to do.
        try {
            menu.initializeContents(0, data.items(), ItemStack.EMPTY);
        } catch (Throwable t) {
            DebugLog.verbose("initializeContents unavailable, writing slots directly: " + t);
            try {
                menu.suppressRemoteUpdates();
                for (int i = 0; i < data.items().size(); i++) {
                    menu.getSlot(i).set(data.items().get(i));
                }
                menu.resumeRemoteUpdates();
            } catch (Throwable t2) {
                DebugLog.warn("failed to fill menu slots", t2);
                client.setScreen(null);
                return null;
            }
        }

        // create() already did this, but restate it: if a modded screen replaced
        // it during construction the replay would misbehave on close.
        player.containerMenu = menu;

        return screen;
    }
}
