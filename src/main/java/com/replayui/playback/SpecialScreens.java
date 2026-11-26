package com.replayui.playback;

import com.replayui.ReplayUIConfig;
import com.replayui.codec.ScreenExtras;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.util.ScreenReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

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

    /** The creative tab is static; leave the viewer's own choice alone. */
    private static Class<?> hijackedClass;
    private static Object originalTab;
    private static boolean tabHijacked;

    private SpecialScreens() {
    }

    /**
     * Put the viewer's own creative tab back. Called from
     * {@code PlaybackTicker.closeQuietly} on every exit path, and is a no-op
     * when nothing was borrowed, so the per-tick call costs nothing.
     */
    public static void release() {
        if (!tabHijacked) { return; }
        tabHijacked = false;
        try {
            if (hijackedClass != null) {
                ScreenReflect.setStatic(hijackedClass, originalTab, "selectedTab");
            }
        } catch (Throwable ignored) {
            // A mapping change must never leak into the ticker.
        }
        hijackedClass = null;
        originalTab = null;
        ExtendedSupport.release();
        DebugLog.verbose("creative tab restored to the viewer's own selection");
    }

    public static Screen build(Minecraft client, UiStateData data) {
        if (!ReplayUIConfig.showSpecialScreens) {
            return null;
        }
        Screen result = ExtendedSupport.buildNonContainer(client, data);
        if (result == null && data.menuTypeId() == UiStateData.CREATIVE_INVENTORY) {
            result = buildCreative(client, data);
        }
        if (result == null && data.menuTypeId() == UiStateData.LECTERN_SCREEN) {
            result = buildLectern(client, data.extras());
        }
        // Beacon effects and recipe-book state live here too, not only on the
        // ordinary MenuScreens path.
        if (result != null) {
            AbstractContainerMenu menu = result instanceof AbstractContainerScreen<?> cs
                    ? cs.getMenu() : null;
            ExtendedSupport.applyMenuState(client, result, menu, data);
        }
        return result;
    }

    /** Fills the state that changed since the screen was opened. */
    public static boolean refresh(Screen screen, UiStateData data) {
        if (screen == null) { return false; }
        if (!ReplayUIConfig.showSpecialScreens) { return false; }
        ScreenExtras ex = data.extras() == null ? ScreenExtras.EMPTY : data.extras();
        if (ExtendedSupport.refreshNonContainer(screen, data)) { return true; }
        // Trailing-block state (beacon, hotbar presets, recipe book, recorded
        // creative item list) changes with the sample too, not only on open.
        ExtendedSupport.applyMenuState(Minecraft.getInstance(), screen, null, data);
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

    private static Screen buildCreative(Minecraft client, UiStateData data) {
        Class<?> cls = ScreenReflect.find(CREATIVE_NAMES);
        if (cls == null || client == null || client.player == null) {
            DebugLog.verbose("creative screen class not found - skipping");
            return null;
        }
        ScreenExtras ex = data.extras() == null ? ScreenExtras.EMPTY : data.extras();
        Object player = client.player;

        // In a production launch every mapped field / method name is
        // intermediary, so name-based lookup misses. Resolve by TYPE first.
        Class<?> listenerClass = ScreenReflect.find(
                "net.minecraft.client.multiplayer.ClientPacketListener");
        Object connection = listenerClass == null ? null
                : ScreenReflect.getByType(player, listenerClass);
        if (connection == null) {
            connection = ScreenReflect.get(player, "connection", "clientPacketListener");
        }
        Class<?> flagsClass = ScreenReflect.find(
                "net.minecraft.world.flag.FeatureFlagSet");
        Object flags = flagsClass == null ? null
                : ScreenReflect.callReturning(connection, flagsClass);
        if (flags == null) {
            flags = ScreenReflect.call(connection, "enabledFeatures", "getEnabledFeatures");
        }
        Class<?> inventoryClass = ScreenReflect.find(
                "net.minecraft.world.entity.player.Inventory");
        Object inventory = ScreenReflect.call(player, "getInventory", "getInventoryMenu");
        if (inventory == null && inventoryClass != null) {
            inventory = ScreenReflect.getByType(player, inventoryClass);
        }

        // construct() walks every declared constructor and fills each parameter
        // by type from this pool, so extra harmless values only widen coverage.
        Object screen = ScreenReflect.construct(cls, player, flags,
                Boolean.TRUE, Boolean.FALSE, inventory, Component.empty(),
                client.level, Integer.valueOf(0));
        if (!(screen instanceof Screen)) {
            DebugLog.verbose("creative screen could not be constructed - skipping");
            return null;
        }
        // The vanilla constructor builds its menu from the VIEWER's inventory.
        // Load the recorded slots so the panel shows the recorded player's
        // items instead of whoever is watching the replay.
        if (screen instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            if (menu != null && !data.items().isEmpty()
                    && menu.slots.size() == data.items().size()) {
                ContainerScreenBuilder.fillMenu(menu, data.items(), data.carried(),
                        data.dataSlots());
            }
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
                Class<?> cls = ScreenReflect.find(CREATIVE_NAMES);
                if (!tabHijacked && cls != null) {
                    // Save once, before the first write. The field is static,
                    // so without this the viewer's own tab would stay changed
                    // after the replay ends.
                    originalTab = ScreenReflect.getStatic(cls, "selectedTab");
                    hijackedClass = cls;
                    tabHijacked = true;
                }
                ScreenReflect.setStatic(cls, tab, "selectedTab");
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
        java.util.List<String> rich = ex.extended() == null
                ? java.util.Collections.<String>emptyList()
                : ex.extended().richPages();
        if (!rich.isEmpty()) {
            for (String json : rich) {
                Object c = parseComponent(json);
                components.add(c == null ? Component.literal("") : c);
            }
        } else {
            for (String page : ex.bookPages()) {
                components.add(Component.literal(page));
            }
        }
        Object access = null;
        if (accessClass != null) {
            access = ScreenReflect.callStatic(accessClass,
                    new Class<?>[]{List.class}, new Object[]{components},
                    "fromPages", "of", "create");
        }
        Object player = client.player;
        Object screen = ScreenReflect.construct(cls, player, access,
                Component.empty(), Boolean.TRUE, Boolean.FALSE,
                client.level, ItemStack.EMPTY, Integer.valueOf(0));
        if (!(screen instanceof Screen)) {
            DebugLog.verbose("lectern screen could not be constructed - skipping");
            return null;
        }
        if (access != null) {
            ScreenReflect.set(screen, access, "bookAccess", "access", "book");
        }
        if (!ex.bookTitle().isEmpty()) {
            ScreenReflect.set(screen, ex.bookTitle(), "bookTitle", "title");
        }
        if (!ex.bookAuthor().isEmpty()) {
            ScreenReflect.set(screen, ex.bookAuthor(), "bookAuthor", "author");
        }
        applyBookPage((Screen) screen, ex);
        return (Screen) screen;
    }

    /** Rich-text pages are stored as Component JSON; fall back to literal. */
    private static Object parseComponent(String json) {
        return com.replayui.util.ComponentJson.fromJson(json);
    }

    private static void applyBookPage(Screen screen, ScreenExtras ex) {
        ScreenReflect.set(screen, Integer.valueOf(ex.bookPage()),
                "currentPage", "page", "pageIndex");
        ScreenReflect.callWith(screen, new Class<?>[]{int.class},
                new Object[]{Integer.valueOf(ex.bookPage())},
                "setPage", "jumpToPage");
    }
}
