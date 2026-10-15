package com.replayui.playback;

import com.replayui.ReplayUIIds;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

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

        AbstractContainerMenu menu;
        try {
            menu = menuType.create(ReplayUIIds.CONTAINER_ID_BASE, player.getInventory());
        } catch (Exception e) {
            DebugLog.warn("failed to create menu for type " + data.menuTypeId(), e);
            return null;
        }

        // Slot count must line up with what we recorded, otherwise the contents
        // would land in the wrong places.
        if (menu.slots.size() != data.items().size()) {
            DebugLog.warn("slot count mismatch: recorded " + data.items().size()
                    + " but this menu has " + menu.slots.size()
                    + " (menu type " + data.menuTypeId() + ") - skipping");
            return null;
        }

        // Fill before the screen exists so the very first frame is already correct.
        //
        // initializeContents is what the vanilla client calls when a
        // container-content packet arrives, so it is the most faithful way to
        // load our items. It delegates to the menu's ContainerSynchronizer, and
        // that field is @Nullable - a menu built by MenuType.create has none, so
        // the call can throw. Hence the fallback.
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
                return null;
            }
        }

        // MenuScreens.create does this internally; we build the screen by hand,
        // so it has to be done here or closing and slot sync would misbehave.
        player.containerMenu = menu;

        Optional<MenuScreens.ScreenConstructor> factory = MenuScreens.getScreenFactory(menuType);
        if (factory.isEmpty()) {
            DebugLog.warn("no screen factory registered for menu type " + data.menuTypeId()
                    + " - skipping");
            return null;
        }

        try {
            return factory.get().create(menu, player.getInventory(), data.title());
        } catch (Exception e) {
            DebugLog.warn("failed to build screen", e);
            return null;
        }
    }
}
