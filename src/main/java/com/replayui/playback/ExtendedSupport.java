package com.replayui.playback;

import com.replayui.codec.ExtendedState;
import com.replayui.codec.ScreenExtras;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.record.ExtendedReader;
import com.replayui.util.ScreenReflect;
import com.replayui.util.ComponentJson;
import com.replayui.util.NonContainerDetail;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Playback half of {@link ExtendedReader}: inject recorded skins, restore
 * beacon effects and creative hotbar presets, and rebuild the five non-container
 * screens.
 *
 * <p>Every step is a candidate list resolved by reflection. That is the only
 * honest way to add screens whose constructors need block entities and whose
 * mapped field names are not stable: a mismatch yields one screen that does not
 * appear (or an empty field), never a crash and never a compile-time dependency
 * on a guessed API. None of this has been exercised in a real game.
 */
public final class ExtendedSupport {

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
    private static final String[] BOOK_ACCESS = {
            "net.minecraft.client.gui.screens.inventory.BookViewScreen$BookAccess"};

    private static boolean beaconReported = false;
    private static boolean ncReported = false;

    private ExtendedSupport() {
    }

    /**
     * Skins are NOT handled here. Flashback forwards player-info packets (which
     * carry the signed "textures" property) into the replay client level and
     * overrides AbstractClientPlayer#getSkin() per UUID, so the recorded player
     * entity already has its skin, slim/wide model and cape.
     * MixinInventoryScreen points the inventory figure at that very entity;
     * recording and re-injecting textures here would duplicate and can overwrite
     * what Flashback restored. The three wire fields stay in ExtendedState for
     * v6 layout compatibility, but ExtendedReader now leaves them empty.
     */

    /** Never returns the viewer's LocalPlayer: that profile is the real one. */
    private static Object findPlayer(Minecraft client, UUID id) {
        try {
            Object level = client.level;
            if (level == null) { return null; }
            Object players = ScreenReflect.call(level, "players", "getPlayers");
            if (players instanceof Iterable) {
                for (Object e : (Iterable<?>) players) {
                    if (e == client.player) { continue; }
                    Object u = ScreenReflect.call(e, "getUUID", "getUuid");
                    if (id.equals(u)) { return e; }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Beacon effects + creative saved hotbars, applied to an ordinary screen. */
    public static void applyMenuState(Minecraft client, Screen screen,
                                      AbstractContainerMenu menu, UiStateData data) {
        ExtendedState ex = extended(data);
        if (ex == null) { return; }
        if (menu != null && classNameContains(menu, "BeaconMenu")) {
            applyBeacon(menu, ex);
        }
        // Saved-hotbar presets are deliberately NOT written back.
        //
        // Flashback already replays the recorded player's hotbar contents and
        // selected slot natively. Writing the recorded preset groups into a
        // List found on the VIEWER's own inventory overrides that native state
        // with the viewer's local data (and on some mappings lands on the
        // inventory item list itself), which is exactly the "hotbar used to be
        // correct, now it is not" regression from the first real-game test.
        // The wire fields stay in ExtendedState for v6 compatibility.
        if (!ex.hotbarGroups().isEmpty()) {
            DebugLog.verbose("hotbar presets: left to Flashback's native replay ("
                    + ex.hotbarGroups().size() + " groups recorded)");
        }
        // Recipe book state and the recorded creative item list live on the
        // Screen, not the Menu. Best effort, one verbose line at most.
        if (screen != null) {
            com.replayui.util.ScreenDetail.apply(screen, ex);
            // Shape-based widget text. Old samples report "" -> no-op.
            com.replayui.util.WidgetState.restore(screen,
                    ex.textAt(NonContainerDetail.T_WIDGET_STATE));
        }
    }

    private static void applyBeacon(AbstractContainerMenu menu, ExtendedState ex) {
        boolean ok = false;
        int p = ex.beaconPrimary();
        int s = ex.beaconSecondary();
        // 1.21.1 BeaconMenu carries the effect ids in ContainerData slots 1/2.
        // Restore them explicitly as a fallback even if dataSlots did not.
        if (p >= 0) { ok |= setData(menu, 1, p); }
        if (s >= 0) { ok |= setData(menu, 2, s); }
        // 2. Object-typed private fields, if this mapping uses them.
        Object pe = resolveEffect(p, ex.textAt(ExtendedReader.T_BEACON_PRIMARY));
        Object se = resolveEffect(s, ex.textAt(ExtendedReader.T_BEACON_SECONDARY));
        if (pe != null) {
            ok |= setEffectField(menu, pe, p, "primaryPower", "primaryEffect", "primary");
        }
        if (se != null) {
            ok |= setEffectField(menu, se, s, "secondaryPower", "secondaryEffect", "secondary");
        }
        if (!beaconReported) {
            beaconReported = true;
            DebugLog.verbose("beacon: effect state written=" + ok
                    + " (" + p + "/" + s + ")");
        }
    }

    private static boolean setData(AbstractContainerMenu menu, int id, int value) {
        try { menu.setData(id, value); return true; } catch (Throwable ignored) { return false; }
    }

    private static Object resolveEffect(int id, String key) {
        Class<?> registries = ScreenReflect.find(
                "net.minecraft.core.registries.BuiltInRegistries");
        Object registry = ScreenReflect.getStatic(registries, "MOB_EFFECT", "EFFECT");
        if (registry == null) { return null; }
        if (!key.isEmpty()) {
            Iterable<?> values = registryValues(registry);
            if (values != null) {
                for (Object e : values) {
                    Object k = ScreenReflect.callAny(registry, e, "getKey");
                    if (k != null && key.equals(String.valueOf(k))) { return e; }
                }
            }
        }
        if (id >= 0) {
            Object byId = ScreenReflect.callAny(registry, Integer.valueOf(id),
                    "byId", "get");
            if (byId != null) { return byId; }
            Object v = ScreenReflect.callStatic(
                    ScreenReflect.find("net.minecraft.world.effect.MobEffect"),
                    new Class<?>[]{int.class}, new Object[]{Integer.valueOf(id)},
                    "byId");
            if (v != null) { return v; }
        }
        return null;
    }

    private static Iterable<?> registryValues(Object registry) {
        if (registry instanceof Iterable) { return (Iterable<?>) registry; }
        Object v = ScreenReflect.call(registry, "values", "holders", "entrySet", "stream");
        return v instanceof Iterable ? (Iterable<?>) v : null;
    }

    /** Field may be a MobEffect, a Holder, or an Integer id - try all three. */
    private static boolean setEffectField(Object menu, Object effect, int id,
                                          String... names) {
        if (ScreenReflect.set(menu, effect, names)) { return true; }
        if (id >= 0 && ScreenReflect.set(menu, Integer.valueOf(id), names)) { return true; }
        Class<?> registries = ScreenReflect.find(
                "net.minecraft.core.registries.BuiltInRegistries");
        Object registry = ScreenReflect.getStatic(registries, "MOB_EFFECT", "EFFECT");
        Object holder = ScreenReflect.callAny(registry, effect,
                "wrapAsHolder", "getHolder", "holderOf");
        return holder != null && ScreenReflect.set(menu, holder, names);
    }



    private static List<List<ItemStack>> snapshotGroups(List<Object> list) {
        List<List<ItemStack>> out = new ArrayList<List<ItemStack>>();
        for (Object o : list) {
            List<ItemStack> one = new ArrayList<ItemStack>();
            if (o instanceof List) {
                for (Object s : (List<?>) o) {
                    one.add(s instanceof ItemStack
                            ? ((ItemStack) s).copy() : ItemStack.EMPTY);
                }
            }
            out.add(one);
        }
        return out;
    }

    // ---- non-container screens -------------------------------------------

    public static Screen buildNonContainer(Minecraft client, UiStateData data) {
        int kind = ExtendedReader.markerToKind(data.menuTypeId());
        if (kind == 0) { return null; }
        ExtendedState ex = extended(data);
        if (ex == null) { return null; }
        if (kind == 6) {
            GameModeSwitcherReplayScreen s = new GameModeSwitcherReplayScreen();
            applyGameModeSwitcher(s, data, ex);
            DebugLog.verbose("game mode switcher rebuilt");
            return s;
        }
        Class<?> cls = classFor(kind);
        if (cls == null) {
            if (!ncReported) {
                ncReported = true;
                DebugLog.verbose("non-container screen class not found kind=" + kind);
            }
            return null;
        }
        // The five constructors are completely different. Supply EVERY object
        // this kind can plausibly need; ScreenReflect.construct walks all
        // declared constructors and fills each parameter by type from the pool.
        Object[] pool = preferredFor(kind, client, ex);
        Object screen = ScreenReflect.construct(cls, pool);
        if (!(screen instanceof Screen)) {
            DebugLog.verbose("non-container screen could not be constructed kind=" + kind);
            return null;
        }
        applyNonContainer((Screen) screen, kind, ex);
        com.replayui.util.WidgetState.restore((Screen) screen,
                ex.textAt(NonContainerDetail.T_WIDGET_STATE));
        if (!ncReported) {
            ncReported = true;
            DebugLog.verbose("non-container screen rebuilt kind=" + kind);
        }
        return (Screen) screen;
    }

    public static boolean refreshNonContainer(Screen screen, UiStateData data) {
        int kind = ExtendedReader.markerToKind(data.menuTypeId());
        if (kind == 0 || screen == null) { return false; }
        if (kind == 6) {
            applyGameModeSwitcher(screen, data, extended(data));
            return true;
        }
        ExtendedState ex = extended(data);
        applyNonContainer(screen, kind, ex);
        if (ex != null) {
            com.replayui.util.WidgetState.restore(screen,
                    ex.textAt(NonContainerDetail.T_WIDGET_STATE));
        }
        return true;
    }

    /**
     * The selected index rides in dataSlots[0]; the four captions ride in the
     * trailing rich list (first entry "sel:N", mirrored there so a change of
     * selection is visible to PlaybackTicker.contentsChanged, which compares
     * extras rather than dataSlots).
     */
    private static void applyGameModeSwitcher(Screen screen, UiStateData data,
                                              ExtendedState ex) {
        if (!(screen instanceof GameModeSwitcherReplayScreen)) { return; }
        int selected = data != null && data.dataSlots().length > 0
                ? data.dataSlots()[0] : 0;
        List<Component> captions = new ArrayList<Component>();
        if (ex != null) {
            for (String raw : ex.richPages()) {
                if (raw == null) { continue; }
                if (raw.startsWith("sel:")) {
                    if (data == null || data.dataSlots().length == 0) {
                        try {
                            selected = Integer.parseInt(raw.substring(4));
                        } catch (Throwable ignored) {
                        }
                    }
                    continue;
                }
                if (!raw.isEmpty()) { captions.add(Component.literal(raw)); }
            }
        }
        ((GameModeSwitcherReplayScreen) screen).setState(selected, captions);
    }

    /** Per-kind argument pool. Everything is soft; a missing piece just means
     *  that constructor gets null for that parameter and is skipped on throw. */
    private static Object[] preferredFor(int kind, Minecraft client, ExtendedState ex) {
        List<Object> pool = new ArrayList<Object>();
        switch (kind) {
            case 1:
                Object book = writableBook();
                if (book != null) { pool.add(book); }
                Object hand = enumConstant("net.minecraft.world.InteractionHand",
                        "MAIN_HAND", 0);
                if (hand != null) { pool.add(hand); }
                break;
            case 2:
                Object access = bookAccess(ex);
                if (access != null) { pool.add(access); }
                break;
            case 3:
            case 4:
            case 5:
                Object be = blockEntityFor(kind, client, ex);
                if (be != null) { pool.add(be); }
                break;
            default:
                break;
        }
        if (client != null) {
            if (client.player != null) { pool.add(client.player); }
            if (client.level != null) { pool.add(client.level); }
        }
        pool.add(Component.empty());
        pool.add(Boolean.TRUE);
        pool.add(Boolean.FALSE);
        pool.add(Integer.valueOf(0));
        pool.add(Integer.valueOf(1));
        pool.add(ItemStack.EMPTY);
        pool.add("");
        return pool.toArray();
    }

    private static Object writableBook() {
        Class<?> items = ScreenReflect.find("net.minecraft.world.item.Items");
        Object item = ScreenReflect.getStatic(items,
                "WRITABLE_BOOK", "BOOK_AND_QUILL");
        if (item == null) { return ItemStack.EMPTY; }
        // Supply count 1 too: ItemStack has both (ItemLike) and (ItemLike,int)
        // constructors, and reflect order is unspecified. A count of 0 would be
        // an empty stack and BookEditScreen would have nothing to read.
        Object stack = ScreenReflect.construct(ItemStack.class, item,
                Integer.valueOf(1));
        return stack instanceof ItemStack ? stack : ItemStack.EMPTY;
    }

    private static Object bookAccess(ExtendedState ex) {
        List<Object> components = new ArrayList<Object>();
        for (String json : ex.richPages()) {
            Object c = fromJson(json);
            components.add(c == null ? Component.literal("") : c);
        }
        if (components.isEmpty()) { components.add(Component.literal("")); }
        Class<?> access = ScreenReflect.find(BOOK_ACCESS);
        return ScreenReflect.callStatic(access,
                new Class<?>[]{List.class}, new Object[]{components},
                "fromPages", "of", "create");
    }

    private static Object blockEntityFor(int kind, Minecraft client, ExtendedState ex) {
        Object pos = blockPos(ex.textAt(NonContainerDetail.T_NC_POS));
        // First choice: the real block entity in the replay world.
        if (client != null && client.level != null && pos != null) {
            Object be = ScreenReflect.callAny(client.level, pos,
                    "getBlockEntity", "getBlockEntityUnchecked");
            if (be != null && matchesKind(kind, be)) {
                DebugLog.verbose("non-container screen using real block entity kind=" + kind);
                return be;
            }
        }
        // Fallback: a synthetic block entity with a real BlockState and level.
        Class<?> blocks = ScreenReflect.find("net.minecraft.world.level.block.Blocks");
        Object state = null;
        for (String field : blockFields(kind)) {
            Object block = ScreenReflect.getStatic(blocks, field);
            if (block == null) { continue; }
            state = ScreenReflect.call(block, "defaultBlockState", "getDefaultState");
            if (state != null) { break; }
        }
        Class<?> beClass = ScreenReflect.find(beClassNames(kind));
        Object be = ScreenReflect.construct(beClass, pos, state,
                client == null ? null : client.level);
        if (be != null && client != null && client.level != null) {
            ScreenReflect.callAny(be, client.level, "setLevel");
        }
        if (be != null) {
            DebugLog.verbose("non-container screen using synthetic block entity kind=" + kind);
        }
        return be;
    }

    private static boolean matchesKind(int kind, Object be) {
        String n = be.getClass().getName();
        if (kind == 3) { return n.contains("Sign"); }
        if (kind == 4) { return n.contains("CommandBlock"); }
        if (kind == 5) { return n.contains("StructureBlock"); }
        return false;
    }

    private static String[] blockFields(int kind) {
        if (kind == 3) {
            return new String[]{"OAK_SIGN", "SPRUCE_SIGN", "OAK_HANGING_SIGN", "SIGN"};
        }
        if (kind == 4) { return new String[]{"COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK"}; }
        return new String[]{"STRUCTURE_BLOCK"};
    }

    private static String[] beClassNames(int kind) {
        if (kind == 3) {
            return new String[]{
                    "net.minecraft.world.level.block.entity.SignBlockEntity",
                    "net.minecraft.world.level.block.entity.HangingSignBlockEntity"};
        }
        if (kind == 4) {
            return new String[]{"net.minecraft.world.level.block.entity.CommandBlockEntity"};
        }
        return new String[]{"net.minecraft.world.level.block.entity.StructureBlockEntity"};
    }

    private static Object blockPos(String packed) {
        int x = 0, y = 0, z = 0;
        if (packed != null && !packed.isEmpty()) {
            String[] p = packed.split(",", -1);
            if (p.length == 3) {
                try {
                    x = Integer.parseInt(p[0]);
                    y = Integer.parseInt(p[1]);
                    z = Integer.parseInt(p[2]);
                } catch (Throwable ignored) {
                }
            }
        }
        return ScreenReflect.construct(
                ScreenReflect.find("net.minecraft.core.BlockPos"),
                Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
    }

    private static Object enumConstant(String className, String name, int ordinal) {
        Class<?> cls = ScreenReflect.find(className);
        if (cls == null || !cls.isEnum()) { return null; }
        Object[] all = cls.getEnumConstants();
        if (all == null) { return null; }
        for (Object c : all) {
            if (((Enum<?>) c).name().equalsIgnoreCase(name)) { return c; }
        }
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }

    private static Class<?> classFor(int kind) {
        switch (kind) {
            case 1: return ScreenReflect.find(BOOK_EDIT);
            case 2: return ScreenReflect.find(BOOK_VIEW);
            case 3: return ScreenReflect.find(SIGN_EDIT);
            case 4: return ScreenReflect.find(COMMAND_BLOCK);
            case 5: return ScreenReflect.find(STRUCTURE_BLOCK);
            default: return null;
        }
    }

    private static void applyNonContainer(Screen screen, int kind, ExtendedState ex) {
        if (ex == null) { return; }
        if (kind == 1 || kind == 2) {
            applyBook(screen, kind, ex);
        } else if (kind == 3) {
            applySign(screen, ex);
        } else if (kind == 4) {
            applyCommandBlock(screen, ex);
        } else if (kind == 5) {
            applyStructureBlock(screen, ex);
        }
        // Caret/selection, sign focus, mirror+rotation, suggestions, history.
        // Runs last so a mismatch here cannot undo the screen contents above.
        NonContainerDetail.apply(screen, kind, ex);
        // Signed / waxed / last-output / include-entities. Runs after the
        // legacy writes so its explicit values win over the inverted fallback.
        com.replayui.util.ScreenDetail.applyNonContainer(screen, kind, ex);
    }

    private static void applyBook(Screen screen, int kind, ExtendedState ex) {
        List<Object> components = new ArrayList<Object>();
        for (String json : ex.richPages()) {
            Object c = fromJson(json);
            components.add(c == null ? Component.literal("") : c);
        }
        Class<?> access = ScreenReflect.find(BOOK_ACCESS);
        if (access != null && !components.isEmpty()) {
            Object a = ScreenReflect.callStatic(access,
                    new Class<?>[]{List.class}, new Object[]{components},
                    "fromPages", "of", "create");
            if (a != null) {
                ScreenReflect.set(screen, a, "bookAccess", "access", "book");
            }
        }
        // BookEditScreen keeps List<String> pages; BookViewScreen keeps
        // BookAccess. Writing only BookAccess left the edit screen blank.
        if (kind == 1) {
            List<String> pages = new ArrayList<String>();
            for (Object c : components) {
                pages.add(c instanceof Component ? ((Component) c).getString() : "");
            }
            if (pages.isEmpty()) { pages.add(""); }
            NonContainerDetail.applyStringList(screen, pages,
                    "pages", "pageList", "bookPages");
        }
        ScreenReflect.set(screen, Integer.valueOf(ex.numberAt(ExtendedReader.N_PAGE)),
                "currentPage", "page", "pageIndex");
        ScreenReflect.callWith(screen, new Class<?>[]{int.class},
                new Object[]{Integer.valueOf(ex.numberAt(ExtendedReader.N_PAGE))},
                "setPage", "jumpToPage");
        ScreenReflect.set(screen, ex.numberAt(ExtendedReader.N_BOOK_SIGNING) != 0,
                "isSigning", "signing");
    }

    private static void applySign(Screen screen, ExtendedState ex) {
        Object[] lines = new Object[4];
        for (int i = 0; i < 4; i++) {
            Object c = fromJson(ex.textAt(ExtendedReader.T_SIGN_0 + i));
            lines[i] = c == null ? Component.empty() : c;
        }
        // ScreenReflect coerces Object[] into the field's real component array.
        ScreenReflect.set(screen, lines, "messages", "signText", "lines");
        ScreenReflect.set(screen, ex.numberAt(ExtendedReader.N_SIGN_FRONT) != 0,
                "isFront", "front");
        ScreenReflect.set(screen, ex.numberAt(ExtendedReader.N_SIGN_GLOW) != 0,
                "isGlowingText", "glowing", "hasGlowingText");
        // Integer -> DyeColor enum is now handled by ScreenReflect.coerce.
        ScreenReflect.set(screen, Integer.valueOf(ex.numberAt(ExtendedReader.N_SIGN_COLOR)),
                "color", "dyeColor", "textColor");
    }

    private static void applyCommandBlock(Screen screen, ExtendedState ex) {
        Object edit = ScreenReflect.get(screen, "commandEdit", "command", "commandBox");
        if (edit != null) {
            ScreenReflect.callWith(edit, new Class<?>[]{String.class},
                    new Object[]{ex.textAt(ExtendedReader.T_COMMAND)},
                    "setValue", "setText");
        }
        // Integer -> command-block Mode enum, via coercion.
        ScreenReflect.set(screen, Integer.valueOf(ex.numberAt(ExtendedReader.N_CMD_MODE)),
                "mode", "commandBlockMode");
        ScreenReflect.set(screen, ex.numberAt(ExtendedReader.N_CMD_CONDITIONAL) != 0,
                "conditional", "isConditional");
        ScreenReflect.set(screen, ex.numberAt(ExtendedReader.N_CMD_AUTO) != 0,
                "auto", "isAuto");
        ScreenReflect.set(screen, ex.numberAt(ExtendedReader.N_CMD_TRACK) != 0,
                "trackOutput", "shouldTrackOutput");
    }

    private static void applyStructureBlock(Screen screen, ExtendedState ex) {
        ScreenReflect.set(screen, ex.textAt(ExtendedReader.T_STRUCT_NAME),
                "structureName", "name", "structure");
        Object be = ScreenReflect.get(screen, "structureBlock", "blockEntity", "structure");
        ScreenReflect.set(be, Integer.valueOf(ex.numberAt(ExtendedReader.N_STRUCT_INTEGRITY)) / 100.0f,
                "integrity");
        // Full 64-bit seed from the text slot; the int slot is only a fallback.
        long seed = parseLong(ex.textAt(NonContainerDetail.T_STRUCT_SEED),
                ex.numberAt(ExtendedReader.N_STRUCT_SEED));
        ScreenReflect.set(be, Long.valueOf(seed), "seed", "integritySeed");
        ScreenReflect.set(be, ex.numberAt(ExtendedReader.N_STRUCT_AIR) != 0,
                "showAir", "includeAir");
        ScreenReflect.set(be, ex.numberAt(ExtendedReader.N_STRUCT_ENTITIES) != 0,
                "ignoreEntities");
        ScreenReflect.set(be, ex.numberAt(ExtendedReader.N_STRUCT_BOX) != 0,
                "showBoundingBox");
        NonContainerDetail.writeTriple(be,
                new String[]{"blockPos", "pos", "position"},
                ex.numberAt(ExtendedReader.N_STRUCT_X),
                ex.numberAt(ExtendedReader.N_STRUCT_Y),
                ex.numberAt(ExtendedReader.N_STRUCT_Z));
        NonContainerDetail.writeTriple(be,
                new String[]{"structureSize", "size"},
                ex.numberAt(ExtendedReader.N_STRUCT_SX),
                ex.numberAt(ExtendedReader.N_STRUCT_SY),
                ex.numberAt(ExtendedReader.N_STRUCT_SZ));
        NonContainerDetail.writeEnumOrdinal(be,
                ex.numberAt(ExtendedReader.N_STRUCT_MODE),
                "mode", "structureMode");
        // Integer -> enum coercion fixes the screen-side mode too.
        ScreenReflect.set(screen,
                Integer.valueOf(ex.numberAt(ExtendedReader.N_STRUCT_MODE)),
                "mode", "structureMode");
    }

    private static long parseLong(String text, int fallback) {
        if (text != null && !text.isEmpty()) {
            try { return Long.parseLong(text); } catch (Throwable ignored) { }
        }
        return fallback;
    }

    private static Object fromJson(String json) {
        return ComponentJson.fromJson(json);
    }

    private static ExtendedState extended(UiStateData data) {
        ScreenExtras base = data == null ? null : data.extras();
        return base == null ? ExtendedState.EMPTY : base.extended();
    }

    private static boolean classNameContains(Object o, String needle) {
        return o != null && o.getClass().getName().contains(needle);
    }

    /** Called by SpecialScreens.release() on every teardown. */
    public static void release() {
        // No skin state to restore: Flashback owns the replay-world profiles.
        com.replayui.util.ScreenDetail.releaseCreativeItems();
    }
}
