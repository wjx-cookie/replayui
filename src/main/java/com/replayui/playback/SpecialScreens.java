package com.replayui.playback;

import com.replayui.ReplayUIConfig;
import com.replayui.codec.ScreenExtras;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.util.ScreenReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the two screens that have no MenuType factory (creative inventory,
 * lectern book) and patches the horse entity reference onto the third.
 *
 * <p>All construction is reflective. That is deliberate: the mapped names of
 * the fields involved are not stable, and a compile-time dependency on them
 * would turn a mapping change into a startup crash. Here it becomes one screen
 * that does not appear, with a verbose log line, while every other screen keeps
 * working.
 */
public final class SpecialScreens {

    private static final String[] CREATIVE_NAMES = {
            "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen"
    };
    private static final String[] LECTERN_NAMES = {
            "net.minecraft.client.gui.screens.inventory.LecternScreen",
            "net.minecraft.client.gui.screens.inventory.BookViewScreen"
    };
    private static final String[] BOOK_ACCESS_NAMES = {
            "net.minecraft.client.gui.screens.inventory.BookViewScreen$BookAccess"
    };

    private SpecialScreens() {
    }

    public static Screen build(Minecraft client, UiStateData data) {
        if (!ReplayUIConfig.showSpecialScreens) {
            return null;
        }
        if (data.menuTypeId() == UiStateData.CREATIVE_INVENTORY) {
            return buildCreative(client, data.extras());
        }
        if (data.menuTypeId() == UiStateData.LECTERN_SCREEN) {
            return buildLectern(client, data.extras());
        }
        return null;
    }

    /** Fills the state that changed since the screen was opened. */
    public static boolean refresh(Screen screen, UiStateData data) {
        if (screen == null) { return false; }
        if (!ReplayUIConfig.showSpecialScreens) { return false; }
        ScreenExtras ex = data.extras() == null ? ScreenExtras.EMPTY : data.extras();
        if (data.menuTypeId() == UiStateData.CREATIVE_INVENTORY) {
            applyCreativeState(screen, ex);
            return true;
        }
        if (data.menuTypeId() == UiStateData.LECTERN_SCREEN) {
            applyBookPage(screen, ex);
            return true;
        }
        return false;
    }

    /**
     * Horse / llama inventories already go through the normal MenuScreens path,
     * because they are genuine AbstractContainerScreens with a real MenuType.
     * What vanilla additionally needs is the entity the menu belongs to; put it
     * back on the screen afterwards. Failing that leaves a usable inventory
     * panel without the horse model, which is still better than nothing.
     */
    public static void attachHorse(Minecraft client, Screen screen, UiStateData data) {
        ScreenExtras ex = data.extras();
        if (ex == null || !ex.hasEntity() || screen == null) { return; }
        Object level = client.level;
        Object horse = ScreenReflect.callWith(level,
                new Class<?>[]{int.class}, new Object[]{Integer.valueOf(ex.entityId())},
                "getEntity", "getEntityById");
        if (horse != null) {
            ScreenReflect.set(screen, horse, "horse", "entity", "animal");
        }
        ScreenReflect.set(screen, Integer.valueOf(ex.columns()),
                "inventoryColumns", "columns");
        DebugLog.verbose("horse inventory attached entity=" + ex.entityId()
                + " columns=" + ex.columns());
    }

    private static Screen buildCreative(Minecraft client, ScreenExtras ex) {
        Class<?> cls = ScreenReflect.find(CREATIVE_NAMES);
        if (cls == null) {
            DebugLog.verbose("creative screen class not found - skipping");
            return null;
        }
        Object player = client.player;
        Object inventory = ScreenReflect.call(player, "getInventory", "getInventoryMenu");
        Object connection = ScreenReflect.get(player, "connection");
        Object flags = ScreenReflect.call(connection, "enabledFeatures", "getEnabledFeatures");
        Object screen = ScreenReflect.construct(cls, player, flags,
                Boolean.TRUE, inventory, Component.empty());
        if (!(screen instanceof Screen)) {
            DebugLog.verbose("creative screen could not be constructed - skipping");
            return null;
        }
        applyCreativeState((Screen) screen, ex);
        return (Screen) screen;
    }

    private static void applyCreativeState(Screen screen, ScreenExtras ex) {
        ScreenReflect.set(screen, Float.valueOf(ex.scrollOffs()),
                "scrollOffs", "scrollOffset");
        ScreenReflect.set(screen, Integer.valueOf(ex.currentPage()),
                "currentPage", "page");
        Object search = ScreenReflect.get(screen, "searchBox", "search");
        if (search != null) {
            ScreenReflect.callWith(search, new Class<?>[]{String.class},
                    new Object[]{ex.searchText()}, "setValue", "setText");
        }
        // selectedTab is a CreativeModeTab, not an int: resolve the registry
        // object by the recorded index before trying to set the static field.
        if (ex.selectedTab() >= 0) {
            Class<?> registries = ScreenReflect.find(
                    "net.minecraft.core.registries.BuiltInRegistries");
            Object registry = ScreenReflect.getStatic(registries,
                    "CREATIVE_MODE_TAB", "CREATIVE_TAB");
            Object tab = ScreenReflect.callWith(registry,
                    new Class<?>[]{int.class},
                    new Object[]{Integer.valueOf(ex.selectedTab())},
                    "byId", "byIdOrThrow", "get");
            if (tab != null) {
                ScreenReflect.setStatic(ScreenReflect.find(CREATIVE_NAMES),
                        tab, "selectedTab");
            }
        }
    }

    private static Screen buildLectern(Minecraft client, ScreenExtras ex) {
        Class<?> cls = ScreenReflect.find(LECTERN_NAMES);
        if (cls == null) {
            DebugLog.verbose("lectern/book screen class not found - skipping");
            return null;
        }
        Class<?> accessClass = ScreenReflect.find(BOOK_ACCESS_NAMES);
        List<Object> components = new ArrayList<Object>();
        for (String page : ex.bookPages()) {
            components.add(Component.literal(page));
        }
        Object access = null;
        if (accessClass != null) {
            access = ScreenReflect.callStatic(accessClass,
                    new Class<?>[]{List.class}, new Object[]{components},
                    "fromPages", "of", "create");
        }
        Object player = client.player;
        Object screen = ScreenReflect.construct(cls, player, access,
                Component.empty(), Boolean.TRUE);
        if (!(screen instanceof Screen)) {
            DebugLog.verbose("lectern screen could not be constructed - skipping");
            return null;
        }
        if (access != null) {
            ScreenReflect.set(screen, access, "bookAccess", "access", "book");
        }
        applyBookPage((Screen) screen, ex);
        return (Screen) screen;
    }

    private static void applyBookPage(Screen screen, ScreenExtras ex) {
        ScreenReflect.set(screen, Integer.valueOf(ex.bookPage()),
                "currentPage", "page", "pageIndex");
        ScreenReflect.callWith(screen, new Class<?>[]{int.class},
                new Object[]{Integer.valueOf(ex.bookPage())},
                "setPage", "jumpToPage");
    }
}
