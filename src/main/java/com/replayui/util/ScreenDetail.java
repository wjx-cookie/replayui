package com.replayui.util;

import com.replayui.codec.ExtendedState;
import com.replayui.log.DebugLog;
import com.replayui.record.ExtendedReader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The screen-side state the container/menu readers never see.
 *
 * <p>Eight things that used to be reproduced wrong (or not at all):
 * <ul>
 *   <li>Recipe book open/closed, selected tab, book type, search text and the
 *       only-craftable filter on inventory / crafting / furnace screens.</li>
 *   <li>Command block last-output text and success count.</li>
 *   <li>Written book "signed" flag and waxed sign flag.</li>
 *   <li>Structure block "include entities" (recorded explicitly rather than
 *       relying on the inverted {@code ignoreEntities} field alone).</li>
 *   <li>Creative inventory's visible item ids, so the list is not regenerated
 *       from the viewer's own registry / feature flags.</li>
 * </ul>
 *
 * <p>Everything rides in the trailing {@link ExtendedState} text/number slots
 * owned by {@link NonContainerDetail}; the format stays 6. Older samples have
 * fewer slots and report "" / 0, which is treated as UNKNOWN, never "false",
 * so this code never clobbers a default with a wrong value.
 *
 * <p>All field names are candidate lists resolved by reflection. A mismatch is
 * a no-op with at most one verbose line, never a crash. Not exercised in a
 * real game yet - written ahead of the shared verification pass.
 */
public final class ScreenDetail {

    private static final String SEP = "\u0001";
    private static final int MAX_CREATIVE_ITEMS = 512;
    private static final int MAX_CREATIVE_CHARS = 16000;
    private static final int MAX_TEXT_VALUE = 4096;

    private static boolean reported = false;

    /** Viewer's own creative item list, restored on teardown. */
    private static List<Object> creativeTarget;
    private static List<ItemStack> creativeBackup;

    private ScreenDetail() {
    }

    // ---- recording: ordinary + special screens ----------------------------

    public static void read(Screen screen, List<String> text, int[] numbers) {
        if (screen == null || text == null || numbers == null) { return; }
        readRecipeBook(screen, text, numbers);
        readCreativeItems(screen, text, numbers);
    }

    /** Called on the non-container branch, where the screen really is a book,
     *  sign, command block or structure block. */
    public static void readNonContainer(Screen screen, int kind,
                                        List<String> text, int[] numbers) {
        if (screen == null || text == null || numbers == null) { return; }
        switch (kind) {
            case 2: readSigned(screen, text); break;
            case 3: readWaxed(screen, text); break;
            case 4: readCommandOutput(screen, text, numbers); break;
            case 5: readStructureInclude(screen, text); break;
            default: break;
        }
        once("screen detail sampled kind=" + kind);
    }

    // ---- recipe book ------------------------------------------------------

    private static void readRecipeBook(Screen screen, List<String> text, int[] numbers) {
        Object book = recipeBookOf(screen);
        if (book == null) { return; }

        Boolean open = firstBoolean(book,
                "isVisible", "isOpen", "isRecipeBookVisible", "isShown");
        if (open == null) {
            open = boolOf(ScreenReflect.get(book, "visible", "isVisible", "open", "widthTooNarrow"));
        }
        putTri(text, NonContainerDetail.T_RECIPE_OPEN, open);

        Object tab = firstObject(book,
                "getCurrentTab", "getSelectedTab", "currentTab", "selectedTab", "activeTab");
        put(text, NonContainerDetail.T_RECIPE_TAB, nameOf(tab));
        put(numbers, NonContainerDetail.N_RECIPE_TAB_ORD, ordinalOf(tab));

        Object type = firstObject(book,
                "getRecipeBookType", "getBookType", "getType", "recipeBookType",
                "bookType", "category", "type");
        put(text, NonContainerDetail.T_RECIPE_TYPE, nameOf(type));
        put(numbers, NonContainerDetail.N_RECIPE_TYPE_ORD, ordinalOf(type));

        Object search = ScreenReflect.get(book, "searchBox", "search", "searchField");
        if (search == null) { search = ScreenReflect.get(screen, "searchBox", "search"); }
        put(text, NonContainerDetail.T_RECIPE_SEARCH, editValue(search));

        Boolean filter = firstBoolean(book,
                "isFilteringCraftable", "isOnlyCraftable", "onlyCraftable", "isFiltering");
        if (filter == null) {
            filter = boolOf(ScreenReflect.get(book,
                    "filtering", "onlyCraftable", "craftableFilter", "isFilteringCraftable"));
        }
        putTri(text, NonContainerDetail.T_RECIPE_FILTER, filter);
    }

    private static Object recipeBookOf(Screen screen) {
        Object book = ScreenReflect.get(screen,
                "recipeBookComponent", "recipeBook", "recipeBookGui", "recipeBookWidget");
        if (book == null) {
            book = ScreenReflect.call(screen,
                    "getRecipeBookComponent", "getRecipeBook", "recipeBook");
        }
        return book;
    }

    // ---- command block output --------------------------------------------

    private static void readCommandOutput(Screen screen, List<String> text, int[] numbers) {
        Object be = ScreenReflect.get(screen,
                "commandBlock", "blockEntity", "tileEntity", "commandBlockEntity");
        Object out = firstObject(screen,
                "getLastOutput", "lastOutput", "output", "outputText", "lastOutputText");
        if (out == null && be != null) {
            out = firstObject(be, "getLastOutput", "lastOutput", "output", "outputText");
        }
        put(text, NonContainerDetail.T_CMD_OUTPUT, ComponentJson.toJson(toComponent(out)));

        Object count = firstObject(screen, "getSuccessCount", "successCount", "outputCount");
        if (count == null && be != null) {
            count = firstObject(be, "getSuccessCount", "successCount");
        }
        put(numbers, NonContainerDetail.N_CMD_SUCCESS_COUNT, intOf(count));
        // The output edit box is where 1.21.1 actually renders the text; keep
        // its plain value too, in case the screen has no Component field.
        Object edit = ScreenReflect.get(screen, "outputEdit", "output", "outputBox");
        String value = editValue(edit);
        if (!value.isEmpty() && at(text, NonContainerDetail.T_CMD_OUTPUT).isEmpty()) {
            put(text, NonContainerDetail.T_CMD_OUTPUT,
                    ComponentJson.toJson(Component.literal(value)));
        }
    }

    // ---- written book / sign / structure block ---------------------------

    private static void readSigned(Screen screen, List<String> text) {
        Object access = ScreenReflect.get(screen, "bookAccess", "access", "book");
        Boolean signed = firstBoolean(screen, "isSigned", "signed", "isBookSigned");
        if (signed == null && access != null) {
            signed = firstBoolean(access, "isSigned", "signed", "isBookSigned");
        }
        if (signed == null) {
            signed = boolOf(ScreenReflect.get(screen, "isSigned", "signed", "bookSigned"));
        }
        // "isWritable" is the INVERSE of signed. Only use it if nothing
        // explicit was found, and flip it before storing.
        if (signed == null) {
            Boolean writable = firstBoolean(screen, "isWritable", "writable");
            if (writable == null && access != null) {
                writable = firstBoolean(access, "isWritable", "writable");
            }
            if (writable != null) { signed = Boolean.valueOf(!writable.booleanValue()); }
        }
        // Written books are signed by definition; only record an explicit flag
        // if the screen really exposes one, otherwise "" (unknown).
        putTri(text, NonContainerDetail.T_BOOK_SIGNED, signed);
    }

    private static void readWaxed(Screen screen, List<String> text) {
        Object be = ScreenReflect.get(screen, "sign", "blockEntity", "tileEntity");
        Boolean waxed = firstBoolean(screen, "isWaxed", "waxed", "hasWax");
        if (waxed == null && be != null) {
            waxed = firstBoolean(be, "isWaxed", "waxed", "hasWax");
        }
        if (waxed == null) {
            waxed = boolOf(ScreenReflect.get(screen, "waxed", "isWaxed", "hasWax"));
        }
        putTri(text, NonContainerDetail.T_SIGN_WAXED, waxed);
    }

    private static void readStructureInclude(Screen screen, List<String> text) {
        Object be = ScreenReflect.get(screen,
                "structureBlock", "blockEntity", "structure", "tileEntity");
        Boolean include = firstBoolean(screen, "isIncludeEntities", "includeEntities");
        if (include == null && be != null) {
            include = firstBoolean(be, "isIncludeEntities", "includeEntities", "getIncludeEntities");
        }
        if (include == null) {
            include = boolOf(ScreenReflect.get(screen,
                    "includeEntities", "includeEntity", "isIncludeEntities"));
        }
        if (include == null && be != null) {
            include = boolOf(ScreenReflect.get(be,
                    "includeEntities", "includeEntity", "isIncludeEntities"));
        }
        if (include == null) {
            // Vanilla stores the INVERSE on the block entity. Normalise it so
            // playback can write both spellings without flipping twice.
            Object ignored = be == null ? null
                    : ScreenReflect.get(be, "ignoreEntities", "isIgnoreEntities");
            if (ignored == null) {
                ignored = ScreenReflect.get(screen, "ignoreEntities", "isIgnoreEntities");
            }
            Boolean b = boolOf(ignored);
            if (b != null) { include = Boolean.valueOf(!b.booleanValue()); }
        }
        putTri(text, NonContainerDetail.T_STRUCT_INCLUDE, include);
    }

    // ---- creative visible items ------------------------------------------

    private static void readCreativeItems(Screen screen, List<String> text, int[] numbers) {
        if (!screen.getClass().getName().contains("CreativeModeInventoryScreen")) { return; }
        List<?> list = findItemList(screen);
        if (list == null) { return; }
        StringBuilder b = new StringBuilder();
        int count = 0;
        for (Object o : list) {
            if (count >= MAX_CREATIVE_ITEMS || b.length() >= MAX_CREATIVE_CHARS) { break; }
            if (!(o instanceof ItemStack) || ((ItemStack) o).isEmpty()) { continue; }
            String id = itemId((ItemStack) o);
            if (id.isEmpty()) { continue; }
            if (b.length() > 0) { b.append(SEP); }
            b.append(id);
            count++;
        }
        put(text, NonContainerDetail.T_CREATIVE_ITEMS, b.toString());
        put(numbers, NonContainerDetail.N_CREATIVE_ITEM_COUNT, count);
    }

    private static String itemId(ItemStack stack) {
        try {
            Object key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key == null ? "" : String.valueOf(key);
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---- playback: ordinary + special screens ----------------------------

    public static void apply(Screen screen, ExtendedState ex) {
        if (screen == null || ex == null) { return; }
        boolean ok = applyRecipeBook(screen, ex);
        ok |= applyCreativeItems(screen, ex);
        once("screen detail applied ok=" + ok);
    }

    public static void applyNonContainer(Screen screen, int kind, ExtendedState ex) {
        if (screen == null || ex == null) { return; }
        switch (kind) {
            case 2: applySigned(screen, ex); break;
            case 3: applyWaxed(screen, ex); break;
            case 4: applyCommandOutput(screen, ex); break;
            case 5: applyStructureInclude(screen, ex); break;
            default: break;
        }
    }

    private static boolean applyRecipeBook(Screen screen, ExtendedState ex) {
        Object book = recipeBookOf(screen);
        if (book == null) { return false; }
        boolean ok = false;
        Boolean open = tri(ex.textAt(NonContainerDetail.T_RECIPE_OPEN));
        if (open != null) {
            ok |= ScreenReflect.set(book, open, "visible", "isVisible", "open", "isOpen");
            ok |= ScreenReflect.set(screen, open, "recipeBookOpen", "isRecipeBookOpen");
            ok |= ScreenReflect.callWith(book, new Class<?>[]{boolean.class},
                    new Object[]{open}, "setVisible", "setOpen", "show", "hide") != null;
        }
        String tab = ex.textAt(NonContainerDetail.T_RECIPE_TAB);
        int tabOrd = ex.numberAt(NonContainerDetail.N_RECIPE_TAB_ORD);
        if (!tab.isEmpty()) {
            ok |= ScreenReflect.set(book, tab, "currentTab", "selectedTab", "activeTab");
        }
        if (tabOrd > 0) {
            ok |= ScreenReflect.set(book, Integer.valueOf(tabOrd - 1),
                    "currentTab", "selectedTab", "activeTab");
        }
        String type = ex.textAt(NonContainerDetail.T_RECIPE_TYPE);
        int typeOrd = ex.numberAt(NonContainerDetail.N_RECIPE_TYPE_ORD);
        if (!type.isEmpty()) {
            ok |= ScreenReflect.set(book, type, "recipeBookType", "bookType", "category", "type");
        }
        if (typeOrd > 0) {
            ok |= ScreenReflect.set(book, Integer.valueOf(typeOrd - 1),
                    "recipeBookType", "bookType", "category", "type");
        }
        String search = ex.textAt(NonContainerDetail.T_RECIPE_SEARCH);
        if (!search.isEmpty()) {
            Object box = ScreenReflect.get(book, "searchBox", "search", "searchField");
            if (box == null) { box = ScreenReflect.get(screen, "searchBox", "search"); }
            ok |= ScreenReflect.callWith(box, new Class<?>[]{String.class},
                    new Object[]{search}, "setValue", "setText") != null;
        }
        Boolean filter = tri(ex.textAt(NonContainerDetail.T_RECIPE_FILTER));
        if (filter != null) {
            ok |= ScreenReflect.set(book, filter, "filtering", "onlyCraftable",
                    "craftableFilter", "isFilteringCraftable");
        }
        ScreenReflect.call(book, "updateCollections", "updateTabs", "refresh", "initVisuals");
        return ok;
    }

    private static void applySigned(Screen screen, ExtendedState ex) {
        Boolean signed = tri(ex.textAt(NonContainerDetail.T_BOOK_SIGNED));
        if (signed == null) { return; }
        ScreenReflect.set(screen, signed, "isSigned", "signed", "bookSigned");
        Object access = ScreenReflect.get(screen, "bookAccess", "access", "book");
        ScreenReflect.set(access, signed, "isSigned", "signed");
        // isWritable means NOT signed; write the inverse where that is the
        // only field this mapping exposes.
        Boolean writable = Boolean.valueOf(!signed.booleanValue());
        ScreenReflect.set(screen, writable, "isWritable", "writable");
        ScreenReflect.set(access, writable, "isWritable", "writable");
    }

    private static void applyWaxed(Screen screen, ExtendedState ex) {
        Boolean waxed = tri(ex.textAt(NonContainerDetail.T_SIGN_WAXED));
        if (waxed == null) { return; }
        Object be = ScreenReflect.get(screen, "sign", "blockEntity", "tileEntity");
        ScreenReflect.set(screen, waxed, "waxed", "isWaxed", "hasWax");
        ScreenReflect.set(be, waxed, "waxed", "isWaxed", "hasWax");
        ScreenReflect.set(be, Boolean.valueOf(!waxed.booleanValue()),
                "isEditable", "editable");
    }

    private static void applyCommandOutput(Screen screen, ExtendedState ex) {
        String json = ex.textAt(NonContainerDetail.T_CMD_OUTPUT);
        Component text = json.isEmpty() ? null : ComponentJson.fromJson(json);
        Object be = ScreenReflect.get(screen,
                "commandBlock", "blockEntity", "tileEntity", "commandBlockEntity");
        if (text != null) {
            String plain = text.getString();
            ScreenReflect.set(screen, text, "lastOutput", "output", "outputText", "lastOutputText");
            ScreenReflect.set(screen, plain, "lastOutput", "output", "outputText");
            ScreenReflect.set(be, text, "lastOutput", "output", "outputText");
            Object edit = ScreenReflect.get(screen, "outputEdit", "output", "outputBox");
            ScreenReflect.callWith(edit, new Class<?>[]{String.class},
                    new Object[]{plain}, "setValue", "setText");
        }
        int count = ex.numberAt(NonContainerDetail.N_CMD_SUCCESS_COUNT);
        ScreenReflect.set(screen, Integer.valueOf(count), "successCount", "outputCount");
        ScreenReflect.set(be, Integer.valueOf(count), "successCount", "outputCount");
    }

    private static void applyStructureInclude(Screen screen, ExtendedState ex) {
        Boolean include = tri(ex.textAt(NonContainerDetail.T_STRUCT_INCLUDE));
        Object be = ScreenReflect.get(screen,
                "structureBlock", "blockEntity", "structure", "tileEntity");
        if (include != null) {
            boolean v = include.booleanValue();
            ScreenReflect.set(screen, Boolean.valueOf(v),
                    "includeEntities", "includeEntity", "isIncludeEntities");
            ScreenReflect.set(be, Boolean.valueOf(v),
                    "includeEntities", "includeEntity", "isIncludeEntities");
            ScreenReflect.set(be, Boolean.valueOf(!v), "ignoreEntities", "isIgnoreEntities");
        } else {
            // Back-compat with samples written before this slot existed: the
            // legacy number slot already holds ignoreEntities.
            int legacy = ex.numberAt(ExtendedReader.N_STRUCT_ENTITIES);
            ScreenReflect.set(be, legacy != 0, "ignoreEntities", "isIgnoreEntities");
            ScreenReflect.set(be, legacy == 0,
                    "includeEntities", "includeEntity", "isIncludeEntities");
        }
    }

    // ---- creative visible items, with viewer-data restore ----------------

    private static boolean applyCreativeItems(Screen screen, ExtendedState ex) {
        String packed = ex.textAt(NonContainerDetail.T_CREATIVE_ITEMS);
        if (packed.isEmpty()) { return false; }
        List<?> list = findItemList(screen);
        if (list == null || !isItemStackList(screen, list)) {
            DebugLog.verbose("creative items: target is not List<ItemStack>, skipping");
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Object> target = (List<Object>) list;
        if (creativeTarget != target) {
            creativeBackup = snapshotItems(target);
            creativeTarget = target;
        }
        List<ItemStack> rebuilt = new ArrayList<ItemStack>();
        for (String id : split(packed)) {
            ItemStack stack = itemFromId(id);
            if (stack != null && !stack.isEmpty()) { rebuilt.add(stack); }
        }
        if (rebuilt.isEmpty()) { return false; }
        try {
            target.clear();
            target.addAll(rebuilt);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Called byExtendedSupport.release() on every teardown. */
    public static void releaseCreativeItems() {
        if (creativeTarget != null && creativeBackup != null) {
            try {
                creativeTarget.clear();
                for (ItemStack stack : creativeBackup) {
                    creativeTarget.add(stack.copy());
                }
                DebugLog.verbose("creative items restored to the viewer's own list");
            } catch (Throwable ignored) {
            }
        }
        creativeTarget = null;
        creativeBackup = null;
    }

    private static List<?> findItemList(Screen screen) {
        String[] names = {"items", "displayItems", "creativeItems", "currentItems", "itemList"};
        Object v = ScreenReflect.get(screen, names);
        if (v instanceof List) { return (List<?>) v; }
        Object menu = ScreenReflect.get(screen, "menu", "containerMenu");
        if (menu == null) { menu = ScreenReflect.call(screen, "getMenu"); }
        v = ScreenReflect.get(menu, names);
        return v instanceof List ? (List<?>) v : null;
    }

    /** Refuse a field whose declared element type is not ItemStack. */
    private static boolean isItemStackList(Screen screen, List<?> list) {
        if (!list.isEmpty()) {
            for (Object o : list) { return o instanceof ItemStack; }
        }
        String[] names = {"items", "displayItems", "creativeItems", "currentItems", "itemList"};
        for (Object target : new Object[]{screen,
                ScreenReflect.get(screen, "menu", "containerMenu")}) {
            if (target == null) { continue; }
            for (String n : names) {
                Field f = declaredField(target.getClass(), n);
                if (f == null) { continue; }
                Type g = f.getGenericType();
                if (!(g instanceof ParameterizedType)) { continue; }
                Type[] args = ((ParameterizedType) g).getActualTypeArguments();
                if (args.length == 1 && args[0] == ItemStack.class) { return true; }
            }
        }
        return false;
    }

    private static Field declaredField(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (Throwable ignored) { }
        }
        return null;
    }

    private static List<ItemStack> snapshotItems(List<Object> list) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        for (Object o : list) {
            out.add(o instanceof ItemStack ? ((ItemStack) o).copy() : ItemStack.EMPTY);
        }
        return out;
    }

    private static ItemStack itemFromId(String id) {
        try {
            Class<?> loc = ScreenReflect.find("net.minecraft.resources.ResourceLocation");
            Object key = ScreenReflect.callStatic(loc,
                    new Class<?>[]{String.class}, new Object[]{id},
                    "parse", "tryParse", "of", "read");
            if (key == null) { return null; }
            Object item = ScreenReflect.callAny(BuiltInRegistries.ITEM, key,
                    "get", "getValue", "byId");
            if (item == null) { return null; }
            Object stack = ScreenReflect.construct(ItemStack.class, item, Integer.valueOf(1));
            return stack instanceof ItemStack ? (ItemStack) stack : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- small helpers ----------------------------------------------------

    private static void once(String message) {
        if (!reported) {
            reported = true;
            DebugLog.verbose(message);
        }
    }

    private static Object firstObject(Object target, String... names) {
        if (target == null) { return null; }
        Object v = ScreenReflect.call(target, names);
        if (v == null) { v = ScreenReflect.get(target, names); }
        return v;
    }

    private static Boolean firstBoolean(Object target, String... names) {
        Object v = firstObject(target, names);
        if (v == null) { v = ScreenReflect.get(target, names); }
        return boolOf(v);
    }

    private static Boolean boolOf(Object v) {
        if (v instanceof Boolean) { return (Boolean) v; }
        if (v instanceof Number) {
            return Boolean.valueOf(((Number) v).intValue() != 0);
        }
        if (v instanceof CharSequence) {
            String s = v.toString();
            if ("true".equalsIgnoreCase(s)) { return Boolean.TRUE; }
            if ("false".equalsIgnoreCase(s)) { return Boolean.FALSE; }
        }
        return null;
    }

    /** "" = unknown, "1" = true, "0" = false. Never turns missing into false. */
    private static Boolean tri(String value) {
        if ("1".equals(value)) { return Boolean.TRUE; }
        if ("0".equals(value)) { return Boolean.FALSE; }
        return null;
    }

    private static void putTri(List<String> text, int slot, Boolean value) {
        if (value == null) { return; }
        put(text, slot, value.booleanValue() ? "1" : "0");
    }

    private static String editValue(Object edit) {
        if (edit == null) { return ""; }
        Object v = ScreenReflect.call(edit, "getValue", "getText", "value");
        if (v == null) { v = ScreenReflect.get(edit, "value", "text"); }
        String s = v == null ? "" : String.valueOf(v);
        return s.length() > MAX_TEXT_VALUE ? s.substring(0, MAX_TEXT_VALUE) : s;
    }

    private static Component toComponent(Object o) {
        if (o instanceof Component) { return (Component) o; }
        if (o == null) { return Component.literal(""); }
        Object s = ScreenReflect.call(o, "getString", "getText", "toString");
        return Component.literal(s == null ? String.valueOf(o) : String.valueOf(s));
    }

    private static String nameOf(Object o) {
        if (o instanceof Enum) { return ((Enum<?>) o).name(); }
        return "";
    }

    /** 0 = unknown, 1..n = ordinal + 1, so a missing slot never selects tab 0. */
    private static int ordinalOf(Object o) {
        return o instanceof Enum ? ((Enum<?>) o).ordinal() + 1 : 0;
    }

    private static int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static List<String> split(String packed) {
        List<String> out = new ArrayList<String>();
        if (packed == null || packed.isEmpty()) { return out; }
        String[] parts = packed.split(SEP, -1);
        for (String p : parts) {
            if (!p.isEmpty()) { out.add(p); }
        }
        return out;
    }

    private static String at(List<String> text, int i) {
        return text != null && i >= 0 && i < text.size() ? text.get(i) : "";
    }

    private static void put(List<String> text, int i, String v) {
        if (text == null || i < 0 || i >= text.size()) { return; }
        text.set(i, v == null ? "" : v);
    }

    private static void put(int[] numbers, int i, int v) {
        if (numbers == null || i < 0 || i >= numbers.length) { return; }
        numbers[i] = v;
    }
}
