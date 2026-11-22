package com.replayui.record;

import com.replayui.ReplayUIConfig;
import com.replayui.codec.ExtendedState;
import com.replayui.codec.ScreenExtras;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.util.ScreenReflect;
import com.replayui.util.ComponentJson;
import com.replayui.util.NonContainerDetail;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Records everything the original reader left out.
 *
 * <p>Two jobs:
 * <ol>
 *   <li>Recognise screens that are not {@code AbstractContainerScreen} at all
 *       (book &amp; quill, written book, sign edit, command block, structure
 *       block) and emit a marker sample for them.</li>
 *   <li>Attach the trailing {@link ExtendedState} block to ordinary samples:
 *       the recorded skin textures property, beacon effects, creative hotbar
 *       presets and rich-text book pages.</li>
 * </ol>
 *
 * <p>All class and field names are candidate lists. A mapping change leaves a
 * default and one verbose line; it never propagates into {@code UiSampler} and
 * never disables recording for the session.
 */
public final class ExtendedReader {

    // text slots
    public static final int T_PAGES = 0;       // reserved: pages live in richPages
    public static final int T_SIGN_0 = 1;      // four sign lines, Component JSON
    public static final int T_SIGN_1 = 2;
    public static final int T_SIGN_2 = 3;
    public static final int T_SIGN_3 = 4;
    public static final int T_COMMAND = 5;
    public static final int T_STRUCT_NAME = 6;
    public static final int T_BEACON_PRIMARY = 7;   // registry key, or ""
    public static final int T_BEACON_SECONDARY = 8;
    // TEXT_COUNT / NUMBER_COUNT live after the number slots, and include the
    // trailing detail slots owned by NonContainerDetail.

    // number slots
    public static final int N_PAGE = 0;
    public static final int N_SIGN_FRONT = 1;
    public static final int N_SIGN_COLOR = 2;
    public static final int N_SIGN_GLOW = 3;
    public static final int N_CMD_MODE = 4;
    public static final int N_CMD_CONDITIONAL = 5;
    public static final int N_CMD_AUTO = 6;
    public static final int N_CMD_TRACK = 7;
    public static final int N_STRUCT_MODE = 8;
    public static final int N_STRUCT_X = 9;
    public static final int N_STRUCT_Y = 10;
    public static final int N_STRUCT_Z = 11;
    public static final int N_STRUCT_SX = 12;
    public static final int N_STRUCT_SY = 13;
    public static final int N_STRUCT_SZ = 14;
    public static final int N_STRUCT_INTEGRITY = 15;   // * 100
    public static final int N_STRUCT_SEED = 16;
    public static final int N_STRUCT_AIR = 17;
    public static final int N_STRUCT_ENTITIES = 18;
    public static final int N_STRUCT_BOX = 19;
    public static final int N_BOOK_SIGNING = 20;
    /** Slots 9.. and 21.. are the four focus/selection/mirror/history details
     *  in NonContainerDetail; keeping the totals there avoids two sources of
     *  truth for the array lengths. */
    public static final int TEXT_COUNT = NonContainerDetail.TEXT_SLOTS;
    public static final int NUMBER_COUNT = NonContainerDetail.NUMBER_SLOTS;

    private static final String[] BOOK_EDIT = {
            "net.minecraft.client.gui.screens.inventory.BookEditScreen"};
    private static final String[] BOOK_VIEW = {
            "net.minecraft.client.gui.screens.inventory.BookViewScreen"};
    private static final String[] SIGN_EDIT = {
            "net.minecraft.client.gui.screens.inventory.SignEditScreen",
            "net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen",
            "net.minecraft.client.gui.screens.inventory.HangingSignEditScreen"};
    private static final String[] COMMAND_BLOCK = {
            "net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen"};
    private static final String[] STRUCTURE_BLOCK = {
            "net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen"};

    private static boolean reported = false;

    private ExtendedReader() {
    }

    /** Null when this screen is not one of the non-container kinds. */
    public static UiStateData captureNonContainer(Minecraft client, Screen screen,
                                                   UUID ownerUuid) {
        if (screen == null || !ReplayUIConfig.showSpecialScreens) { return null; }
        int kind = nonContainerKind(screen);
        if (kind == 0) { return null; }

        List<String> text = blankStrings(TEXT_COUNT);
        int[] numbers = new int[NUMBER_COUNT];
        List<String> pages = new ArrayList<String>();

        readBook(screen, kind, text, numbers, pages);
        readSign(screen, kind, text, numbers);
        readCommandBlock(screen, kind, text, numbers);
        readStructureBlock(screen, kind, text, numbers);
        readBlockPos(screen, text);
        // Caret / selection, sign focus, mirror+rotation, command suggestions
        // and history. Fills the trailing text/number slots only.
        NonContainerDetail.read(screen, kind, text, numbers);
        // Signed book / waxed sign / command output / include-entities. Also
        // fills only trailing slots, so an older reader simply skips them.
        com.replayui.util.ScreenDetail.readNonContainer(screen, kind, text, numbers);

        double rawX = 0.0, rawY = 0.0;
        try {
            com.replayui.mixin.MouseHandlerAccessor mouse =
                    (com.replayui.mixin.MouseHandlerAccessor) client.mouseHandler;
            rawX = mouse.replayui$getXpos();
            rawY = mouse.replayui$getYpos();
        } catch (Throwable ignored) {
        }
        int winW = 0, winH = 0;
        try {
            com.mojang.blaze3d.platform.Window w = client.getWindow();
            if (w != null) { winW = w.getWidth(); winH = w.getHeight(); }
        } catch (Throwable ignored) {
        }

        if (!reported) {
            reported = true;
            DebugLog.verbose("non-container screen sampled kind=" + kind
                    + " class=" + screen.getClass().getName());
        }

        int[] beacon = readBeacon(client, null, null, text);
        ExtendedState ext = new ExtendedState(kind, text, numbers,
                skinValue(client), skinSignature(client), skinModel(client),
                beacon[0], beacon[1], readHotbarPresets(client), pages);
        ScreenExtras extras = withExtended(ScreenExtras.EMPTY, ext);
        return new UiStateData(ownerUuid, true, kindToMarker(kind),
                screen.getTitle(), Collections.<ItemStack>emptyList(),
                ItemStack.EMPTY, 0, rawX, rawY, null, new int[0],
                winW, winH, extras, UiStateData.EVENT_NONE);
    }

    private static int nonContainerKind(Screen screen) {
        if (ScreenReflect.isInstance(ScreenReflect.find(BOOK_EDIT), screen)) { return 1; }
        if (ScreenReflect.isInstance(ScreenReflect.find(BOOK_VIEW), screen)) { return 2; }
        if (ScreenReflect.isInstance(ScreenReflect.find(SIGN_EDIT), screen)) { return 3; }
        if (ScreenReflect.isInstance(ScreenReflect.find(COMMAND_BLOCK), screen)) { return 4; }
        if (ScreenReflect.isInstance(ScreenReflect.find(STRUCTURE_BLOCK), screen)) { return 5; }
        return 0;
    }

    public static int kindToMarker(int kind) {
        switch (kind) {
            case 1: return UiStateData.BOOK_EDIT_SCREEN;
            case 2: return UiStateData.BOOK_VIEW_SCREEN;
            case 3: return UiStateData.SIGN_EDIT_SCREEN;
            case 4: return UiStateData.COMMAND_BLOCK_SCREEN;
            case 5: return UiStateData.STRUCTURE_BLOCK_SCREEN;
            default: return 0;
        }
    }

    public static int markerToKind(int marker) {
        switch (marker) {
            case UiStateData.BOOK_EDIT_SCREEN: return 1;
            case UiStateData.BOOK_VIEW_SCREEN: return 2;
            case UiStateData.SIGN_EDIT_SCREEN: return 3;
            case UiStateData.COMMAND_BLOCK_SCREEN: return 4;
            case UiStateData.STRUCTURE_BLOCK_SCREEN: return 5;
            default: return 0;
        }
    }

    /**
     * Attach the trailing block to an already-built sample. Called for ordinary
     * container screens and for the three legacy special screens.
     */
    public static ScreenExtras augment(Minecraft client, Screen screen,
                                        AbstractContainerMenu menu,
                                        ScreenExtras base) {
        ScreenExtras safe = base == null ? ScreenExtras.EMPTY : base;
        if (client == null) { return withExtended(safe, ExtendedState.EMPTY); }
        List<String> text = blankStrings(NonContainerDetail.TEXT_SLOTS);
        int[] beacon = readBeacon(client, screen, menu, text);
        List<String> rich = new ArrayList<String>();
        if (screen != null) {
            rich = readRichPages(screen, safe);
        }
        int[] numbers = new int[NonContainerDetail.NUMBER_SLOTS];
        // Recipe book state and the creative tab's visible item ids are screen
        // fields, not menu fields; this is the only place we still have the
        // live Screen instance on the ordinary recording path.
        com.replayui.util.ScreenDetail.read(screen, text, numbers);
        ExtendedState ext = new ExtendedState(0, text, numbers,
                skinValue(client), skinSignature(client), skinModel(client),
                beacon[0], beacon[1], readHotbarPresets(client), rich);
        return withExtended(safe, ext);
    }

    /** Same, but returns a whole new sample (used on the special-screen path). */
    public static UiStateData augmentData(Minecraft client, Screen screen, UiStateData data) {
        if (data == null) { return null; }
        ScreenExtras base = data.extras() == null ? ScreenExtras.EMPTY : data.extras();
        ScreenExtras ex = augment(client, screen, null, base);
        return new UiStateData(data.ownerUuid(), data.open(), data.menuTypeId(),
                data.title(), data.items(), data.carried(), data.containerId(),
                data.mouseX(), data.mouseY(), data.blockPos(), data.dataSlots(),
                data.windowWidth(), data.windowHeight(), ex, data.eventKind());
    }

    private static ScreenExtras withExtended(ScreenExtras base, ExtendedState ext) {
        ScreenExtras b = base == null ? ScreenExtras.EMPTY : base;
        return new ScreenExtras(b.entityId(), b.columns(), b.selectedTab(),
                b.scrollOffs(), b.searchText(), b.currentPage(),
                b.bookPages(), b.bookPage(), b.merchantOffers(),
                b.renameText(), b.enchantClues(), b.enchantLevels(),
                b.experienceLevel(), b.bookTitle(), b.bookAuthor(),
                b.merchantScroll(), b.selectedOffer(), b.merchantLevel(),
                b.merchantXp(), b.recipeIndex(), b.anvilFocus(),
                b.anvilCursor(), ext == null ? ExtendedState.EMPTY : ext);
    }

    // ---- skin -------------------------------------------------------------

    /**
     * Skins are Flashback's job: it forwards player-info packets into the replay
     * client level and overrides AbstractClientPlayer#getSkin() per UUID, so the
     * recorded player entity already renders with the right skin / model / cape.
     * Recording the local profile and injecting it on playback would duplicate
     * (and may overwrite) that. Wire fields stay in ExtendedState for v6 layout
     * compatibility and are deliberately left empty from now on.
     */
    public static String skinValue(Minecraft client) { return ""; }
    public static String skinSignature(Minecraft client) { return ""; }
    public static String skinModel(Minecraft client) { return ""; }

    // ---- beacon -----------------------------------------------------------

    /**
     * Beacon primary / secondary effects. Recording BOTH the numeric id (which
     * fits BeaconMenu's ContainerData) and the reversible registry key (which
     * lets playback resolve the real MobEffect object) replaces the old
     * hashCode() scheme: a hash cannot be turned back into an effect.
     */
    private static int[] readBeacon(Minecraft client, Screen screen,
                                     AbstractContainerMenu menu, List<String> text) {
        int[] out = new int[]{-1, -1};
        if (menu == null || !classNameContains(menu, "BeaconMenu")) { return out; }
        Object primary = ScreenReflect.call(menu, "getPrimaryEffect", "getPrimaryPower");
        Object secondary = ScreenReflect.call(menu, "getSecondaryEffect", "getSecondaryPower");
        if (primary == null) { primary = ScreenReflect.get(menu, "primaryPower", "primaryEffect", "primary"); }
        if (secondary == null) { secondary = ScreenReflect.get(menu, "secondaryPower", "secondaryEffect", "secondary"); }
        out[0] = effectNumericId(primary);
        out[1] = effectNumericId(secondary);
        putText(text, T_BEACON_PRIMARY, effectKey(primary));
        putText(text, T_BEACON_SECONDARY, effectKey(secondary));
        return out;
    }

    /** Integer id for a MobEffect, or -1. Never hashes anything. */
    private static int effectNumericId(Object effect) {
        if (effect == null) { return -1; }
        if (effect instanceof Number) { return ((Number) effect).intValue(); }
        try {
            // Registry.getId(Object) matched by name + assignability, so no
            // compile-time dependency on the mapped MobEffect type.
            Class<?> registries = ScreenReflect.find("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = ScreenReflect.getStatic(registries, "MOB_EFFECT", "EFFECT");
            Object id = ScreenReflect.callAny(registry, effect, "getId", "getRawId");
            if (id instanceof Number) { return ((Number) id).intValue(); }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** Reversible registry key as text, e.g. "minecraft:speed". */
    private static String effectKey(Object effect) {
        if (effect == null) { return ""; }
        try {
            Class<?> registries = ScreenReflect.find("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = ScreenReflect.getStatic(registries, "MOB_EFFECT", "EFFECT");
            Object key = ScreenReflect.callAny(registry, effect, "getKey");
            if (key != null) { return String.valueOf(key); }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ---- creative saved hotbars ------------------------------------------

    /**
     * Vanilla stores saved hotbars as a list OF LISTS. Keeping the nesting is
     * mandatory: flattening it and then writing single ItemStacks into the outer
     * list corrupts the viewer's own inventory (generic erasure makes the write
     * succeed, and the next vanilla iteration throws ClassCastException).
     */
    private static List<List<ItemStack>> readHotbarPresets(Minecraft client) {
        List<List<ItemStack>> out = new ArrayList<List<ItemStack>>();
        try {
            Object player = client.player;
            if (player == null) { return out; }
            Object inv = ScreenReflect.call(player, "getInventory");
            if (inv == null) { return out; }
            Object presets = ScreenReflect.get(inv,
                    "hotbarPresets", "savedHotbars", "hotbarItems", "hotbarSwapItems");
            if (presets == null) {
                Class<?> creative = ScreenReflect.find(
                        "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen");
                presets = ScreenReflect.getStatic(creative,
                        "hotbarPresets", "savedHotbars", "hotbarSwapItems", "HOTBAR_SWAP_ITEMS");
            }
            if (presets instanceof Iterable) {
                for (Object group : (Iterable<?>) presets) {
                    if (out.size() >= ExtendedState.MAX_PRESET_GROUPS) { break; }
                    List<ItemStack> one = new ArrayList<ItemStack>();
                    if (group instanceof Iterable) {
                        for (Object s : (Iterable<?>) group) {
                            if (one.size() >= ExtendedState.MAX_PRESET_SLOTS) { break; }
                            if (s instanceof ItemStack) { one.add(((ItemStack) s).copy()); }
                        }
                    } else if (group instanceof ItemStack) {
                        one.add(((ItemStack) group).copy());
                    }
                    out.add(one);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Lectern pages were plain text; also keep the full Component JSON. */
    private static List<String> readRichPages(Screen screen, ScreenExtras base) {
        List<String> out = new ArrayList<String>();
        for (String page : base.bookPages()) {
            out.add(ComponentJson.toJson(Component.literal(page)));
        }
        // Prefer real components when the screen exposes them.
        try {
            Object access = ScreenReflect.get(screen, "bookAccess", "access", "book");
            if (access != null) {
                Object pages = ScreenReflect.call(access, "pages", "getPages");
                if (pages instanceof Iterable) {
                    List<String> real = new ArrayList<String>();
                    for (Object c : (Iterable<?>) pages) {
                        if (c instanceof Component) {
                            real.add(ComponentJson.toJson((Component) c));
                        }
                    }
                    if (!real.isEmpty()) { return real; }
                }
            }
            Object rawPages = ScreenReflect.get(screen, "pages", "pageList");
            if (rawPages instanceof Iterable) {
                List<String> real = new ArrayList<String>();
                for (Object c : (Iterable<?>) rawPages) {
                    if (c instanceof Component) {
                        real.add(ComponentJson.toJson((Component) c));
                    } else if (c instanceof String) {
                        real.add(ComponentJson.toJson(Component.literal((String) c)));
                    }
                }
                if (!real.isEmpty()) { return real; }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---- non-container field reads ---------------------------------------

    private static void readBook(Screen screen, int kind, List<String> text,
                                 int[] numbers, List<String> pages) {
        if (kind != 1 && kind != 2) { return; }
        numbers[N_PAGE] = intOf(ScreenReflect.get(screen, "currentPage", "page", "pageIndex"));
        numbers[N_BOOK_SIGNING] = boolInt(ScreenReflect.get(screen, "isSigning", "signing"));
        Object raw = ScreenReflect.get(screen, "pages", "pageList", "bookPages");
        if (raw instanceof Iterable) {
            for (Object o : (Iterable<?>) raw) {
                if (o instanceof Component) {
                    pages.add(ComponentJson.toJson((Component) o));
                } else if (o instanceof String) {
                    pages.add(ComponentJson.toJson(Component.literal((String) o)));
                }
            }
        }
        if (pages.isEmpty()) {
            Object access = ScreenReflect.get(screen, "bookAccess", "access", "book");
            Object list = ScreenReflect.call(access, "pages", "getPages");
            if (list instanceof Iterable) {
                for (Object o : (Iterable<?>) list) {
                    if (o instanceof Component) {
                        pages.add(ComponentJson.toJson((Component) o));
                    }
                }
            }
        }
        if (pages.size() > ExtendedState.MAX_RICH_PAGES) {
            List<String> cut = new ArrayList<String>(pages.subList(0, ExtendedState.MAX_RICH_PAGES));
            pages.clear();
            pages.addAll(cut);
        }
    }

    private static void readSign(Screen screen, int kind, List<String> text, int[] numbers) {
        if (kind != 3) { return; }
        numbers[N_SIGN_FRONT] = boolInt(ScreenReflect.get(screen, "isFront", "front"));
        Object color = ScreenReflect.get(screen, "color", "dyeColor", "textColor");
        numbers[N_SIGN_COLOR] = ordinalOf(color);
        numbers[N_SIGN_GLOW] = boolInt(ScreenReflect.get(screen, "isGlowingText", "glowing", "hasGlowingText"));
        Object msgs = ScreenReflect.get(screen, "messages", "signText", "lines");
        if (msgs == null) {
            Object be = ScreenReflect.get(screen, "sign", "blockEntity");
            msgs = ScreenReflect.get(be, "messages", "signText", "text");
        }
        int i = 0;
        if (msgs instanceof Object[]) {
            for (Object o : (Object[]) msgs) {
                if (i > 3) { break; }
                text.set(T_SIGN_0 + i, toJson(o));
                i++;
            }
        } else if (msgs instanceof Iterable) {
            for (Object o : (Iterable<?>) msgs) {
                if (i > 3) { break; }
                text.set(T_SIGN_0 + i, toJson(o));
                i++;
            }
        }
    }

    private static void readCommandBlock(Screen screen, int kind, List<String> text,
                                          int[] numbers) {
        if (kind != 4) { return; }
        Object edit = ScreenReflect.get(screen, "commandEdit", "command", "commandBox");
        Object value = edit == null ? null : ScreenReflect.call(edit, "getValue", "getText");
        text.set(T_COMMAND, value == null ? "" : String.valueOf(value));
        numbers[N_CMD_MODE] = ordinalOf(ScreenReflect.get(screen, "mode", "commandBlockMode"));
        numbers[N_CMD_CONDITIONAL] = boolInt(ScreenReflect.get(screen, "conditional", "isConditional"));
        numbers[N_CMD_AUTO] = boolInt(ScreenReflect.get(screen, "auto", "isAuto"));
        numbers[N_CMD_TRACK] = boolInt(ScreenReflect.get(screen, "trackOutput", "shouldTrackOutput"));
    }

    private static void readStructureBlock(Screen screen, int kind, List<String> text,
                                            int[] numbers) {
        if (kind != 5) { return; }
        Object name = ScreenReflect.get(screen, "structureName", "name", "structure");
        text.set(T_STRUCT_NAME, name == null ? "" : String.valueOf(name));
        numbers[N_STRUCT_MODE] = ordinalOf(ScreenReflect.get(screen, "mode", "structureMode"));
        Object be = ScreenReflect.get(screen, "structureBlock", "blockEntity", "structure");
        Object pos = ScreenReflect.get(be, "blockPos", "pos", "position");
        numbers[N_STRUCT_X] = intOf(ScreenReflect.call(pos, "getX"));
        numbers[N_STRUCT_Y] = intOf(ScreenReflect.call(pos, "getY"));
        numbers[N_STRUCT_Z] = intOf(ScreenReflect.call(pos, "getZ"));
        Object size = ScreenReflect.get(be, "structureSize", "size");
        numbers[N_STRUCT_SX] = intOf(ScreenReflect.call(size, "getX"));
        numbers[N_STRUCT_SY] = intOf(ScreenReflect.call(size, "getY"));
        numbers[N_STRUCT_SZ] = intOf(ScreenReflect.call(size, "getZ"));
        numbers[N_STRUCT_INTEGRITY] = (int) (floatOf(ScreenReflect.get(be, "integrity")) * 100.0f);
        Object seed = ScreenReflect.get(be, "seed", "integritySeed");
        numbers[N_STRUCT_SEED] = intOf(seed);                 // low 32 bits, fallback
        putText(text, NonContainerDetail.T_STRUCT_SEED, Long.toString(longOf(seed)));
        numbers[N_STRUCT_AIR] = boolInt(ScreenReflect.get(be, "showAir", "includeAir"));
        numbers[N_STRUCT_ENTITIES] = boolInt(ScreenReflect.get(be, "ignoreEntities"));
        numbers[N_STRUCT_BOX] = boolInt(ScreenReflect.get(be, "showBoundingBox"));
    }

    /** Real block entities already exist in the replay world; record the pos
     *  so playback can look them up instead of synthesising broken ones. */
    private static void readBlockPos(Screen screen, List<String> text) {
        Object be = ScreenReflect.get(screen, "sign", "blockEntity",
                "structureBlock", "commandBlock", "tileEntity");
        Object pos = ScreenReflect.get(be, "blockPos", "pos", "position");
        if (pos == null) { pos = ScreenReflect.call(be, "getBlockPos", "getPos"); }
        if (pos == null) { return; }
        Object x = ScreenReflect.call(pos, "getX");
        Object y = ScreenReflect.call(pos, "getY");
        Object z = ScreenReflect.call(pos, "getZ");
        if (x instanceof Number && y instanceof Number && z instanceof Number) {
            putText(text, NonContainerDetail.T_NC_POS,
                    intOf(x) + "," + intOf(y) + "," + intOf(z));
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static void putText(List<String> text, int slot, String value) {
        if (text == null || slot < 0 || slot >= text.size()) { return; }
        text.set(slot, value == null ? "" : value);
    }

    private static long longOf(Object o) {
        if (o instanceof Number) { return ((Number) o).longValue(); }
        if (o instanceof CharSequence) {
            try { return Long.parseLong(o.toString()); } catch (Throwable ignored) { }
        }
        return 0L;
    }

    private static List<String> blankStrings(int n) {
        List<String> l = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) { l.add(""); }
        return l;
    }

    private static boolean classNameContains(Object o, String needle) {
        return o != null && o.getClass().getName().contains(needle);
    }

    private static int intOf(Object o) {
        if (o instanceof Number) { return ((Number) o).intValue(); }
        return 0;
    }

    private static float floatOf(Object o) {
        if (o instanceof Number) { return ((Number) o).floatValue(); }
        return 0.0f;
    }

    private static int boolInt(Object o) {
        return Boolean.TRUE.equals(o) ? 1 : 0;
    }

    private static int ordinalOf(Object o) {
        if (o instanceof Number) { return ((Number) o).intValue(); }
        if (o instanceof Enum) { return ((Enum<?>) o).ordinal(); }
        if (o != null) { return String.valueOf(o).hashCode() & 0x7f; }
        return 0;
    }

    private static String toJson(Object o) {
        if (o == null) { return ""; }
        if (o instanceof Component) {
            return ComponentJson.toJson((Component) o);
        }
        return ComponentJson.toJson(Component.literal(String.valueOf(o)));
    }
}
