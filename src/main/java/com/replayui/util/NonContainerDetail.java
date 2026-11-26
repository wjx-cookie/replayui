package com.replayui.util;

import com.replayui.codec.ExtendedState;
import com.replayui.log.DebugLog;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The four details the first non-container pass left out:
 * book caret/selection, sign focused line, structure block mirror/rotation,
 * and command block suggestion list / command history / caret position.
 *
 * <p>All of it rides in the trailing {@link ExtendedState} text/number slots.
 * Format 6 length-prefixes the whole block, and both readers index by slot
 * number with bounds checks, so adding slots is backward and forward safe:
 * an older sample simply reports 0 / "" for these, and an older reader skips
 * the block. No version bump and no forced re-record.
 *
 * <p>Slots 0..8 (text) and 0..20 (numbers) belong to ExtendedReader; 9..17
 * and 21..33 are the four details below; 18..27 and 34..37 are owned by
 * {@link ScreenDetail} (recipe book, command output, signed/waxed/include,
 * creative item ids). All three share one array length, so an old sample
 * simply reports "" / 0 for slots it never wrote. Nothing here depends on a
 * compile-time MC mapping:
 * every field is a candidate list resolved at runtime, and a mismatch is a
 * no-op with one verbose line, never a crash.
 *
 * <p>Not exercised in a real game yet - written ahead of the shared verification
 * pass, on purpose.
 */
public final class NonContainerDetail {

    private static final String SEP = "\u0001";
    private static final int MAX_SUGGESTIONS = 24;
    private static final int MAX_HISTORY = 24;
    private static final int MAX_TEXT_VALUE = 4096;

    // text slots: 0..8 are owned by ExtendedReader
    public static final int T_BOOK_PAGE_TEXT = 9;   // raw text of the page being edited
    public static final int T_BOOK_FOCUS = 10;      // focused widget class, diagnostics only
    public static final int T_SIGN_LINE_TEXT = 11;  // raw text of the focused sign line
    public static final int T_CMD_SUGGESTIONS = 12; // SEP-joined suggestion strings
    public static final int T_CMD_HISTORY = 13;     // SEP-joined command history
    public static final int T_STRUCT_MIRROR = 14;   // enum name
    public static final int T_STRUCT_ROTATION = 15; // enum name
    public static final int T_STRUCT_SEED = 16;     // full long, as decimal text
    public static final int T_NC_POS = 17;          // "x,y,z" of the block entity
    // ---- screen-wide detail slots (owned by ScreenDetail) ----------------
    public static final int T_RECIPE_TAB = 18;      // recipe book: selected tab name
    public static final int T_RECIPE_TYPE = 19;     // recipe book: book type / category
    public static final int T_RECIPE_SEARCH = 20;   // recipe book: search box text
    public static final int T_RECIPE_OPEN = 21;     // "1"/"0"/"" (unknown)
    public static final int T_RECIPE_FILTER = 22;   // "1"/"0"/"" only-craftable
    public static final int T_CMD_OUTPUT = 23;      // lastOutput, Component JSON
    public static final int T_BOOK_SIGNED = 24;     // "1"/"0"/"" written book
    public static final int T_SIGN_WAXED = 25;      // "1"/"0"/"" waxed sign
    public static final int T_STRUCT_INCLUDE = 26;  // "1"/"0"/"" include entities
    public static final int T_CREATIVE_ITEMS = 27;  // displayed item ids, SEP-joined
    // Widget-level text (shape-based, no class names) and the stable registry
    // key of the MenuType. Both are trailing slots: old samples report "" and
    // every previously verified path keeps its exact old behaviour.
    public static final int T_WIDGET_STATE = 28;
    public static final int T_MENU_KEY = 29;
    public static final int TEXT_SLOTS = 30;

    // number slots: 0..20 are owned by ExtendedReader
    public static final int N_BOOK_CURSOR = 21;
    public static final int N_BOOK_SEL_START = 22;
    public static final int N_BOOK_SEL_END = 23;
    public static final int N_BOOK_LINE = 24;
    public static final int N_BOOK_MODIFIED = 25;
    public static final int N_SIGN_FOCUS_LINE = 26;
    public static final int N_SIGN_CURSOR = 27;
    public static final int N_SIGN_SEL_END = 28;
    public static final int N_CMD_CURSOR = 29;
    public static final int N_CMD_SEL_END = 30;
    public static final int N_CMD_SUGGEST_INDEX = 31;
    public static final int N_STRUCT_MIRROR_ORD = 32;
    public static final int N_STRUCT_ROT_ORD = 33;
    // ---- screen-wide detail slots (owned by ScreenDetail) ----------------
    public static final int N_RECIPE_TAB_ORD = 34;
    public static final int N_RECIPE_TYPE_ORD = 35;
    public static final int N_CMD_SUCCESS_COUNT = 36;
    public static final int N_CREATIVE_ITEM_COUNT = 37;
    public static final int NUMBER_SLOTS = 38;

    private static boolean reported = false;

    private NonContainerDetail() {
    }

    // ---- recording --------------------------------------------------------

    /** Fills the new slots in-place. Safe to call with a sample from an old mod. */
    public static void read(Screen screen, int kind, List<String> text, int[] numbers) {
        if (screen == null || text == null || numbers == null) { return; }
        switch (kind) {
            case 1:
            case 2:
                readBook(screen, text, numbers);
                break;
            case 3:
                readSign(screen, text, numbers);
                break;
            case 4:
                readCommand(screen, text, numbers);
                break;
            case 5:
                readStructure(screen, text, numbers);
                break;
            default:
                break;
        }
        if (!reported) {
            reported = true;
            DebugLog.verbose("non-container detail sampled kind=" + kind
                    + " slots=" + TEXT_SLOTS + "/" + NUMBER_SLOTS);
        }
    }

    private static void readBook(Object screen, List<String> text, int[] n) {
        Object edit = editOf(screen, "pageEdit", "editBox", "textField", "pageMsg", "commandEdit");
        readEdit(edit, text, T_BOOK_PAGE_TEXT, n, N_BOOK_CURSOR, N_BOOK_SEL_END);
        Object start = ScreenReflect.get(screen, "selectionStart", "selectionPos", "selectPos");
        int cursor = n.length > N_BOOK_CURSOR ? n[N_BOOK_CURSOR] : 0;
        put(n, N_BOOK_SEL_START, start == null ? cursor : intOf(start));
        Object line = ScreenReflect.get(screen, "currentLine", "line", "lineIndex", "lineStart");
        put(n, N_BOOK_LINE, intOf(line));
        Object mod = ScreenReflect.get(screen, "isModified", "modified", "dirty");
        put(n, N_BOOK_MODIFIED, boolInt(mod));
        Object focus = ScreenReflect.call(screen, "getFocused", "getFocusedChild");
        put(text, T_BOOK_FOCUS, focus == null ? "" : focus.getClass().getSimpleName());
        // BookEditScreen also keeps the caret on the screen itself.
        Object own = ScreenReflect.get(screen, "cursorPos", "caretPos", "cursorPosition");
        if (own != null) { put(n, N_BOOK_CURSOR, intOf(own)); }
    }

    private static void readSign(Object screen, List<String> text, int[] n) {
        Object line = ScreenReflect.get(screen, "line", "focusedLine", "currentLine", "lineIndex");
        put(n, N_SIGN_FOCUS_LINE, intOf(line));
        Object edit = editOf(screen, "editBox", "textField", "lineEdit");
        readEdit(edit, text, T_SIGN_LINE_TEXT, n, N_SIGN_CURSOR, N_SIGN_SEL_END);
    }

    private static void readCommand(Object screen, List<String> text, int[] n) {
        Object edit = editOf(screen, "commandEdit", "command", "commandBox", "editBox");
        // The value itself already lives in ExtendedReader.T_COMMAND; -1 means
        // "do not overwrite that slot", we only need caret and selection here.
        readEdit(edit, text, -1, n, N_CMD_CURSOR, N_CMD_SEL_END);
        put(text, T_CMD_SUGGESTIONS, suggestionsOf(screen));
        put(text, T_CMD_HISTORY, stringListOf(ScreenReflect.get(screen,
                "history", "previousCommands", "commandHistory", "lastCommands")));
        Object sug = ScreenReflect.get(screen,
                "commandSuggestions", "suggestions", "suggestionHelper", "autoComplete");
        Object idx = ScreenReflect.get(sug,
                "suggestionIndex", "selectedSuggestion", "selected", "currentIndex");
        if (idx == null) {
            idx = ScreenReflect.get(screen, "suggestionIndex", "selectedSuggestion");
        }
        put(n, N_CMD_SUGGEST_INDEX, intOf(idx));
    }

    private static void readStructure(Object screen, List<String> text, int[] n) {
        Object be = ScreenReflect.get(screen, "structureBlock", "blockEntity", "structure");
        Object mirror = ScreenReflect.get(be, "mirror", "mirrorType", "mirrorMode");
        if (mirror == null) {
            mirror = ScreenReflect.get(screen, "mirror", "mirrorType", "mirrorMode");
        }
        Object rotation = ScreenReflect.get(be, "rotation", "rotationType", "rotationMode");
        if (rotation == null) {
            rotation = ScreenReflect.get(screen, "rotation", "rotationType", "rotationMode");
        }
        put(text, T_STRUCT_MIRROR, nameOf(mirror));
        put(n, N_STRUCT_MIRROR_ORD, ordinalOf(mirror));
        put(text, T_STRUCT_ROTATION, nameOf(rotation));
        put(n, N_STRUCT_ROT_ORD, ordinalOf(rotation));
    }

    // ---- playback ---------------------------------------------------------

    /**
     * Called after ExtendedSupport already restored pages / messages / command
     * text. This only adds the fine state, so a failure here never removes the
     * screen contents.
     */
    public static void apply(Screen screen, int kind, ExtendedState ex) {
        if (screen == null || ex == null) { return; }
        boolean ok = false;
        switch (kind) {
            case 1:
            case 2:
                ok = applyBook(screen, ex);
                break;
            case 3:
                ok = applySign(screen, ex);
                break;
            case 4:
                ok = applyCommand(screen, ex);
                break;
            case 5:
                ok = applyStructure(screen, ex);
                break;
            default:
                break;
        }
        if (!reported) {
            reported = true;
            DebugLog.verbose("non-container detail applied kind=" + kind + " ok=" + ok);
        }
    }

    private static boolean applyBook(Object screen, ExtendedState ex) {
        Object edit = editOf(screen, "pageEdit", "editBox", "textField", "pageMsg", "commandEdit");
        boolean ok = applyEdit(edit, ex.textAt(T_BOOK_PAGE_TEXT),
                ex.numberAt(N_BOOK_CURSOR), ex.numberAt(N_BOOK_SEL_END));
        ok |= ScreenReflect.set(screen, Integer.valueOf(ex.numberAt(N_BOOK_CURSOR)),
                "cursorPos", "caretPos", "cursorPosition");
        ok |= ScreenReflect.set(screen, Integer.valueOf(ex.numberAt(N_BOOK_SEL_START)),
                "selectionStart", "selectionPos", "selectPos");
        ok |= ScreenReflect.set(screen, Integer.valueOf(ex.numberAt(N_BOOK_SEL_END)),
                "selectionEnd", "highlightPos", "selectionPos");
        ok |= ScreenReflect.set(screen, ex.numberAt(N_BOOK_MODIFIED) != 0,
                "isModified", "modified", "dirty");
        ScreenReflect.call(screen, "updateButtonVisibility", "updatePage", "refresh");
        return ok;
    }

    private static boolean applySign(Object screen, ExtendedState ex) {
        boolean ok = ScreenReflect.set(screen,
                Integer.valueOf(ex.numberAt(N_SIGN_FOCUS_LINE)),
                "line", "focusedLine", "currentLine", "lineIndex");
        Object edit = editOf(screen, "editBox", "textField", "lineEdit");
        ok |= applyEdit(edit, ex.textAt(T_SIGN_LINE_TEXT),
                ex.numberAt(N_SIGN_CURSOR), ex.numberAt(N_SIGN_SEL_END));
        ScreenReflect.call(screen, "updateSign", "refresh", "updateButtonVisibility");
        return ok;
    }

    private static boolean applyCommand(Object screen, ExtendedState ex) {
        Object edit = editOf(screen, "commandEdit", "command", "commandBox", "editBox");
        // Value is restored by ExtendedSupport.applyCommandBlock; here we only
        // re-apply the caret position and the selection.
        boolean ok = applyCaret(edit, ex.numberAt(N_CMD_CURSOR), ex.numberAt(N_CMD_SEL_END));
        ok |= ScreenReflect.set(screen,
                Integer.valueOf(ex.numberAt(N_CMD_SUGGEST_INDEX)),
                "suggestionIndex", "selectedSuggestion", "selected", "currentIndex");
        Object sug = ScreenReflect.get(screen,
                "commandSuggestions", "suggestions", "suggestionHelper", "autoComplete");
        ok |= ScreenReflect.set(sug,
                Integer.valueOf(ex.numberAt(N_CMD_SUGGEST_INDEX)),
                "suggestionIndex", "selectedSuggestion", "selected", "currentIndex");
        List<String> suggestions = split(ex.textAt(T_CMD_SUGGESTIONS));
        List<String> history = split(ex.textAt(T_CMD_HISTORY));
        // Only ever write into a field declared as List<String>. Vanilla's
        // List<Suggestion> would ClassCastException the moment it is iterated.
        ok |= setStringList(screen, suggestions,
                "suggestionList", "suggestions", "suggestionStrings");
        ok |= setStringList(screen, history,
                "history", "previousCommands", "commandHistory", "lastCommands");
        ok |= setStringList(sug, suggestions,
                "suggestionList", "suggestions", "suggestionStrings");
        return ok;
    }

    private static boolean applyStructure(Object screen, ExtendedState ex) {
        Object be = ScreenReflect.get(screen, "structureBlock", "blockEntity", "structure");
        boolean ok = setEnum(be, ex.textAt(T_STRUCT_MIRROR),
                ex.numberAt(N_STRUCT_MIRROR_ORD), "mirror", "mirrorType", "mirrorMode");
        ok |= setEnum(screen, ex.textAt(T_STRUCT_MIRROR),
                ex.numberAt(N_STRUCT_MIRROR_ORD), "mirror", "mirrorType", "mirrorMode");
        ok |= setEnum(be, ex.textAt(T_STRUCT_ROTATION),
                ex.numberAt(N_STRUCT_ROT_ORD), "rotation", "rotationType", "rotationMode");
        ok |= setEnum(screen, ex.textAt(T_STRUCT_ROTATION),
                ex.numberAt(N_STRUCT_ROT_ORD), "rotation", "rotationType", "rotationMode");
        return ok;
    }

    /** Pos / size were recorded but never written back. Public so the playback
     * side can reuse it with ExtendedReader's slot constants. */
    public static boolean writeTriple(Object target, String[] names, int x, int y, int z) {
        if (target == null) { return false; }
        Object current = ScreenReflect.get(target, names);
        if (current == null) { return false; }
        Object made = ScreenReflect.construct(current.getClass(),
                Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
        if (made == null) { return false; }
        return setRaw(target, made, names);
    }

    /** Restore the structure mode enum from the recorded ordinal. */
    public static boolean writeEnumOrdinal(Object target, int ordinal, String... names) {
        return setEnum(target, "", ordinal, names);
    }

    // ---- small reflection helpers ----------------------------------------

    private static Object editOf(Object screen, String... names) {
        Object focus = ScreenReflect.call(screen, "getFocused", "getFocusedChild");
        if (focus != null && ScreenReflect.call(focus, "getValue", "getText") != null) {
            return focus;
        }
        for (String n : names) {
            Object o = ScreenReflect.get(screen, n);
            if (o != null) { return o; }
        }
        return null;
    }

    private static void readEdit(Object edit, List<String> text, int textSlot,
                                 int[] n, int cursorSlot, int selectionSlot) {
        if (edit == null) { return; }
        Object v = ScreenReflect.call(edit, "getValue", "getText", "value");
        if (v == null) { v = ScreenReflect.get(edit, "value", "text"); }
        String s = v == null ? "" : String.valueOf(v);
        if (s.length() > MAX_TEXT_VALUE) { s = s.substring(0, MAX_TEXT_VALUE); }
        if (textSlot >= 0) { put(text, textSlot, s); }
        Object c = ScreenReflect.call(edit,
                "getCursorPosition", "getCursorPos", "cursorPos");
        if (c == null) {
            c = ScreenReflect.get(edit, "cursorPos", "cursorPosition", "caretPos");
        }
        put(n, cursorSlot, intOf(c));
        Object h = ScreenReflect.call(edit,
                "getHighlightPos", "getSelectionPos", "selectionPos", "getSelectionEnd");
        if (h == null) {
            h = ScreenReflect.get(edit,
                    "highlightPos", "selectionPos", "selectionEnd", "selectPos");
        }
        put(n, selectionSlot, intOf(h));
    }

    private static boolean applyEdit(Object edit, String value, int cursor, int selection) {
        boolean ok = false;
        if (edit == null) { return false; }
        ok |= ScreenReflect.callWith(edit, new Class<?>[]{String.class},
                new Object[]{clamp(value)}, "setValue", "setText") != null;
        ok |= applyCaret(edit, cursor, selection);
        return ok;
    }

    private static boolean applyCaret(Object edit, int cursor, int selection) {
        if (edit == null) { return false; }
        boolean ok = ScreenReflect.set(edit, Integer.valueOf(cursor),
                "cursorPos", "cursorPosition", "caretPos");
        ok |= ScreenReflect.callWith(edit, new Class<?>[]{int.class},
                new Object[]{Integer.valueOf(cursor)},
                "setCursorPosition", "moveCursorTo", "setCursorPos") != null;
        ok |= ScreenReflect.set(edit, Integer.valueOf(selection),
                "highlightPos", "selectionPos", "selectionEnd", "selectPos");
        ok |= ScreenReflect.callWith(edit, new Class<?>[]{int.class},
                new Object[]{Integer.valueOf(selection)},
                "setHighlightPos", "setSelectionPos", "setSelectionEnd") != null;
        return ok;
    }

    /**
     * Only writes when the field is declared {@code List<String>}. A vanilla
     * {@code List<Suggestion>} would blow up on the first iteration, so it is
     * deliberately left alone: the strings are still in the sample for logs.
     */
    /**
     * Public so ExtendedSupport can rebuild BookEditScreen's {@code pages}.
     * Still refuses any field that is not declared {@code List<String>}.
     */
    public static boolean applyStringList(Object target, List<String> value, String... names) {
        return setStringList(target, value, names);
    }

    private static boolean setStringList(Object target, List<String> value, String... names) {
        if (target == null || value == null) { return false; }
        for (String n : names) {
            Field f = field(target, n);
            if (f == null || !f.getType().isInstance(value)) { continue; }
            Type g = f.getGenericType();
            if (!(g instanceof ParameterizedType)) { continue; }
            Type[] args = ((ParameterizedType) g).getActualTypeArguments();
            if (args.length != 1 || args[0] != String.class) { continue; }
            try {
                f.setAccessible(true);
                f.set(target, new ArrayList<String>(value));
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean setEnum(Object target, String name, int ordinal, String... names) {
        if (target == null) { return false; }
        for (String n : names) {
            Field f = field(target, n);
            if (f == null || !f.getType().isEnum()) { continue; }
            Object v = enumValue(f.getType(), name, ordinal);
            if (v == null) { continue; }
            try {
                f.setAccessible(true);
                f.set(target, v);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Object enumValue(Class<?> type, String name, int ordinal) {
        Object[] all = type.getEnumConstants();
        if (all == null) { return null; }
        if (name != null && name.length() > 0) {
            for (Object c : all) {
                if (((Enum<?>) c).name().equals(name)) { return c; }
            }
        }
        if (ordinal >= 0 && ordinal < all.length) { return all[ordinal]; }
        return null;
    }

    private static boolean setRaw(Object target, Object value, String... names) {
        if (target == null || value == null) { return false; }
        for (String n : names) {
            Field f = field(target, n);
            if (f == null || !f.getType().isInstance(value)) { continue; }
            try {
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Field field(Object target, String name) {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ---- value helpers ----------------------------------------------------

    private static String suggestionsOf(Object screen) {
        Object sug = ScreenReflect.get(screen,
                "commandSuggestions", "suggestions", "suggestionHelper", "autoComplete");
        Object list = ScreenReflect.get(sug,
                "suggestions", "suggestionList", "list", "currentSuggestions");
        if (list == null) {
            Object s = ScreenReflect.call(sug, "getSuggestions", "suggestions");
            list = ScreenReflect.call(s, "getList", "getSuggestions", "list");
        }
        if (list == null) {
            list = ScreenReflect.get(screen, "suggestions", "suggestionList");
        }
        List<String> out = new ArrayList<String>();
        if (list instanceof Iterable) {
            for (Object o : (Iterable<?>) list) {
                if (out.size() >= MAX_SUGGESTIONS) { break; }
                Object t = ScreenReflect.call(o,
                        "getText", "getSuggestion", "text", "getValue");
                out.add(clean(t == null ? String.valueOf(o) : String.valueOf(t)));
            }
        }
        return join(out);
    }

    private static String stringListOf(Object raw) {
        List<String> out = new ArrayList<String>();
        if (raw instanceof Iterable) {
            for (Object o : (Iterable<?>) raw) {
                if (out.size() >= MAX_HISTORY) { break; }
                out.add(clean(String.valueOf(o)));
            }
        } else if (raw instanceof Object[]) {
            for (Object o : (Object[]) raw) {
                if (out.size() >= MAX_HISTORY) { break; }
                out.add(clean(String.valueOf(o)));
            }
        }
        return join(out);
    }

    private static String join(List<String> values) {
        StringBuilder b = new StringBuilder();
        for (String s : values) {
            if (b.length() > 0) { b.append(SEP); }
            b.append(s);
        }
        return b.toString();
    }

    private static List<String> split(String packed) {
        List<String> out = new ArrayList<String>();
        if (packed == null || packed.length() == 0) { return out; }
        String[] parts = packed.split(SEP, -1);
        for (String p : parts) {
            if (p.length() > 0) { out.add(p); }
        }
        return out;
    }

    private static String clean(String s) {
        if (s == null) { return ""; }
        String v = s.replace(SEP, " ");
        return v.length() > 256 ? v.substring(0, 256) : v;
    }

    private static String clamp(String s) {
        if (s == null) { return ""; }
        return s.length() > MAX_TEXT_VALUE ? s.substring(0, MAX_TEXT_VALUE) : s;
    }

    private static String nameOf(Object o) {
        if (o instanceof Enum) { return ((Enum<?>) o).name(); }
        return o == null ? "" : String.valueOf(o);
    }

    private static void put(List<String> text, int i, String v) {
        if (text == null || i < 0 || i >= text.size()) { return; }
        text.set(i, v == null ? "" : v);
    }

    private static void put(int[] numbers, int i, int v) {
        if (numbers == null || i < 0 || i >= numbers.length) { return; }
        numbers[i] = v;
    }

    private static int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static int boolInt(Object o) {
        return Boolean.TRUE.equals(o) ? 1 : 0;
    }

    private static int ordinalOf(Object o) {
        if (o instanceof Enum) { return ((Enum<?>) o).ordinal(); }
        return -1;
    }
}
