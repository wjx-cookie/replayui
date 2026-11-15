package com.replayui.record;

import com.replayui.codec.ScreenExtras;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.mixin.MenuTypeAccessor;
import com.replayui.mixin.MouseHandlerAccessor;
import com.replayui.util.ScreenReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recognises the three screens that are not plain MenuType containers and
 * snapshots the extra state they carry.
 *
 * <p>Class names are stable across mappings; field names are not, so every read
 * goes through {@link ScreenReflect} with a list of candidates. A candidate that
 * does not exist leaves the default. Nothing here throws out into
 * {@code UiSampler}, which matters: a failure there would disable UI sampling
 * for the whole recording session.
 */
public final class SpecialScreenReader {

    public static final int NONE = 0;
    public static final int CREATIVE = 1;
    public static final int HORSE = 2;
    public static final int LECTERN = 3;

    private static final String[] CREATIVE_NAMES = {
            "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen"
    };
    private static final String[] HORSE_NAMES = {
            "net.minecraft.client.gui.screens.inventory.HorseInventoryScreen"
    };
    private static final String[] LECTERN_NAMES = {
            "net.minecraft.client.gui.screens.inventory.LecternScreen",
            "net.minecraft.client.gui.screens.inventory.BookViewScreen"
    };

    private static boolean reported = false;

    private SpecialScreenReader() {
    }

    public static int kindOf(Screen screen) {
        if (screen == null) { return NONE; }
        if (ScreenReflect.isInstance(ScreenReflect.find(HORSE_NAMES), screen)) { return HORSE; }
        if (ScreenReflect.isInstance(ScreenReflect.find(CREATIVE_NAMES), screen)) { return CREATIVE; }
        if (ScreenReflect.isInstance(ScreenReflect.find(LECTERN_NAMES), screen)) { return LECTERN; }
        return NONE;
    }

    /**
     * Builds a full sample for a special screen. The horse screen is still an
     * AbstractContainerScreen, so it keeps its real MenuType id and full slot
     * list - the standard container path on playback handles it, and only the
     * entity reference is patched afterwards.
     */
    public static UiStateData capture(Minecraft client, Screen screen,
                                      int kind, UUID ownerUuid) {
        List<ItemStack> items = new ArrayList<ItemStack>();
        ItemStack carried = ItemStack.EMPTY;
        int[] dataSlots = new int[0];
        int menuTypeId;
        int containerId = 0;

        if (kind == HORSE && screen instanceof AbstractContainerScreen) {
            AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) screen;
            AbstractContainerMenu menu = cs.getMenu();
            if (menu != null) {
                containerId = menu.containerId;
                for (Slot slot : menu.slots) {
                    items.add(slot.getItem().copy());
                }
                carried = menu.getCarried().copy();
                try {
                    MenuType<?> mt = ((MenuTypeAccessor) menu).replayui$getMenuType();
                    menuTypeId = mt != null
                            ? BuiltInRegistries.MENU.getId(mt)
                            : UiStateData.NO_MENU_TYPE;
                } catch (Throwable t) {
                    menuTypeId = UiStateData.NO_MENU_TYPE;
                }
                RecordedDataSlots.bind(containerId);
                dataSlots = RecordedDataSlots.snapshot();
            } else {
                menuTypeId = UiStateData.NO_MENU_TYPE;
                RecordedDataSlots.bind(-1);
            }
        } else {
            menuTypeId = kind == CREATIVE
                    ? UiStateData.CREATIVE_INVENTORY
                    : UiStateData.LECTERN_SCREEN;
            RecordedDataSlots.bind(-1);
        }

        double rawX = 0.0;
        double rawY = 0.0;
        try {
            MouseHandlerAccessor mouse = (MouseHandlerAccessor) client.mouseHandler;
            rawX = mouse.replayui$getXpos();
            rawY = mouse.replayui$getYpos();
        } catch (Throwable ignored) {
            // Cosmetic loss only.
        }

        int winW = 0;
        int winH = 0;
        try {
            com.mojang.blaze3d.platform.Window window = client.getWindow();
            if (window != null) {
                winW = window.getWidth();
                winH = window.getHeight();
            }
        } catch (Throwable ignored) {
        }

        if (!reported) {
            reported = true;
            DebugLog.verbose("special screen sampled: kind=" + kind
                    + " menuType=" + menuTypeId);
        }

        return new UiStateData(ownerUuid, true, menuTypeId, screen.getTitle(),
                items, carried, containerId, rawX, rawY, null, dataSlots,
                winW, winH, read(screen, kind));
    }

    /** Reflective read of the state that has no equivalent in menu.slots. */
    public static ScreenExtras read(Screen screen, int kind) {
        if (kind == CREATIVE) {
            Object tab = ScreenReflect.getStatic(
                    ScreenReflect.find(CREATIVE_NAMES), "selectedTab");
            int tabIndex = -1;
            if (tab != null) {
                Object idx = ScreenReflect.call(tab, "getId", "id", "index");
                if (idx instanceof Number) {
                    tabIndex = ((Number) idx).intValue();
                }
            }
            Object scroll = ScreenReflect.get(screen, "scrollOffs", "scrollOffset");
            float scrollValue = scroll instanceof Number
                    ? ((Number) scroll).floatValue() : 0.0f;
            Object search = ScreenReflect.get(screen, "searchBox", "search");
            String text = "";
            if (search != null) {
                Object v = ScreenReflect.call(search, "getValue", "getText");
                if (v instanceof String) { text = (String) v; }
            }
            Object page = ScreenReflect.get(screen, "currentPage", "page");
            int pageValue = page instanceof Number ? ((Number) page).intValue() : 0;
            return new ScreenExtras(-1, 0, tabIndex, scrollValue, text,
                    pageValue, null, 0);
        }

        if (kind == HORSE) {
            Object horse = ScreenReflect.get(screen, "horse", "entity", "animal");
            int id = -1;
            if (horse != null) {
                Object v = ScreenReflect.call(horse, "getId", "id");
                if (v instanceof Number) { id = ((Number) v).intValue(); }
            }
            Object cols = ScreenReflect.get(screen, "inventoryColumns", "columns");
            int columns = cols instanceof Number ? ((Number) cols).intValue() : 0;
            return new ScreenExtras(id, columns, -1, 0.0f, "", 0, null, 0);
        }

        if (kind == LECTERN) {
            List<String> pages = new ArrayList<String>();
            Object access = ScreenReflect.get(screen, "bookAccess", "access", "book");
            if (access != null) {
                Object raw = ScreenReflect.get(access, "pages", "pageList");
                if (raw instanceof List<?>) {
                    for (Object p : (List<?>) raw) {
                        String text = pageText(p);
                        if (text != null) { pages.add(text); }
                    }
                }
            }
            Object page = ScreenReflect.get(screen, "currentPage", "page");
            int pageValue = page instanceof Number ? ((Number) page).intValue() : 0;
            return new ScreenExtras(-1, 0, -1, 0.0f, "", 0, pages, pageValue);
        }

        return ScreenExtras.EMPTY;
    }

    /** Plain-text fallback: a rich-text page survives as readable text. */
    private static String pageText(Object page) {
        if (page == null) { return null; }
        if (page instanceof String) { return (String) page; }
        Object s = ScreenReflect.call(page, "getString", "getText", "toString");
        return s instanceof String ? (String) s : null;
    }
}
