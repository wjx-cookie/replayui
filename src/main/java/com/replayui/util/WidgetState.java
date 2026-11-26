package com.replayui.util;

import com.replayui.log.DebugLog;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Widget-level state, captured by SHAPE instead of by class name or mapping
 * name.
 *
 * <p>This is the part of "what the player saw" that neither {@code menu.slots}
 * / {@code ContainerData} nor Flashback's own packet stream carries: the text
 * inside focusable input widgets (anvil rename, creative / recipe-book search,
 * book pages, sign lines, command fields - and the search box a mod adds to an
 * inventory screen).
 *
 * <p>No mod name, no screen name and no {@code field_XXXX} / {@code method_XXXX}
 * appears here. A "text widget" is simply an object with a no-arg String getter
 * and a one-String setter. That shape survives remapping, so a production
 * launch behaves like a dev workspace, and a widget from JEI / REI / EMI or any
 * other mod built on the vanilla edit box is covered without adapting it. The
 * original screen class is still rebuilt by {@code MenuScreens.create}; this
 * only fills in the values the original code cannot know.
 *
 * <p>The dump rides in one trailing {@code ExtendedState} text slot. Old samples
 * have no such slot and report "", in which case {@link #restore} is a complete
 * no-op: every previously verified path keeps its exact old behaviour. Nothing
 * is written to the viewer's own inventory, and restore is idempotent so a
 * per-tick refresh cannot clear a focused edit box.
 */
public final class WidgetState {

    private static final char UNIT = '\u0002';   // between widgets
    private static final char FIELD = '\u0001';  // between fields of one widget
    private static final int MAX_WIDGETS = 128;
    private static final int MAX_DUMP = 8192;
    private static final int MAX_VALUE = 1024;

    private static final String[] GET_NAMES = {"getValue", "getText", "getString", "getContents"};
    private static final String[] SET_NAMES = {"setValue", "setText", "setString"};
    private static final String[] FOCUS_GET = {"isFocused", "isFocus", "hasFocus"};
    private static final String[] FOCUS_SET = {"setFocused", "setFocus"};

    /** Last dump applied to each rebuilt screen. Weak keys: a closed screen is
     *  collected with its entry. This is what keeps per-tick refreshes from
     *  rewriting (and thus clearing) a focused edit box. */
    private static final Map<Screen, String> APPLIED = new WeakHashMap<Screen, String>();

    private static boolean reported = false;

    private WidgetState() {
    }

    /** Pure read. Swallows everything: a failure must never disable UI sampling. */
    public static String capture(Screen screen) {
        if (screen == null) { return ""; }
        try {
            List<?> children = childrenOf(screen);
            if (children == null || children.isEmpty()) { return ""; }
            StringBuilder sb = new StringBuilder();
            int n = Math.min(children.size(), MAX_WIDGETS);
            for (int i = 0; i < n; i++) {
                Object w = children.get(i);
                if (w == null) { continue; }
                Method getter = textGetter(w);
                if (getter == null) { continue; }
                String value = clean(textOf(w, getter), MAX_VALUE);
                Object focused = invoke(namedGetter(w, FOCUS_GET, boolean.class), w);
                if (sb.length() > 0) { sb.append(UNIT); }
                sb.append(i).append(FIELD).append(value).append(FIELD)
                        .append(focused instanceof Boolean
                                ? (((Boolean) focused).booleanValue() ? "1" : "0") : "");
                if (sb.length() >= MAX_DUMP) { break; }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Write back only when the dump differs from the last one applied. */
    public static void restore(Screen screen, String dump) {
        if (screen == null || dump == null || dump.isEmpty()) { return; }
        try {
            String prev = APPLIED.get(screen);
            if (dump.equals(prev)) { return; }
            APPLIED.put(screen, dump);
            List<?> children = childrenOf(screen);
            if (children == null) { return; }
            int focusIndex = -1;
            int touched = 0;
            for (String unit : dump.split(String.valueOf(UNIT))) {
                if (unit.isEmpty()) { continue; }
                String[] f = unit.split(String.valueOf(FIELD), -1);
                if (f.length < 2) { continue; }
                int idx;
                try { idx = Integer.parseInt(f[0]); } catch (Throwable ignored) { continue; }
                if (idx < 0 || idx >= children.size()) { continue; }
                Object w = children.get(idx);
                if (w == null) { continue; }
                Method getter = textGetter(w);
                if (getter == null) { continue; }
                String want = f[1];
                String now = clean(textOf(w, getter), MAX_VALUE);
                if (!want.equals(now)) {
                    Method setter = textSetter(w);
                    if (setter != null) {
                        try {
                            setter.setAccessible(true);
                            setter.invoke(w, want);
                            touched++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (f.length > 2 && "1".equals(f[2])) { focusIndex = idx; }
            }
            if (focusIndex >= 0 && focusIndex < children.size()) {
                Object w = children.get(focusIndex);
                Method set = namedSetter(w, FOCUS_SET, boolean.class);
                if (set != null) {
                    try {
                        set.setAccessible(true);
                        set.invoke(w, Boolean.TRUE);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!reported) {
                reported = true;
                DebugLog.verbose("widget state restored: " + touched + " text values");
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- mod overlay passthrough -------------------------------------------

    /**
     * True when a non-vanilla widget covers this point on a rebuilt screen.
     *
     * <p>JEI / REI / EMI and mod-added widgets hang themselves on the same
     * {@code Screen} through the vanilla {@code children()} list. They are real
     * {@link GuiEventListener}s living in the real replay client; the only
     * thing blocking them was our HEAD-cancel of every input callback.
     *
     * <p>No mod name, no screen name, no {@code field_XXXX} / {@code method_XXXX}.
     * The split is just "declared under net.minecraft.* vs anything else", read
     * from the package prefix, so it survives remapping and version changes.
     * A click on a container slot hits no such widget and stays read-only.
     */
    public static boolean foreignWidgetAt(Screen screen, double x, double y) {
        for (GuiEventListener g : foreign(screen)) {
            try {
                if (g.isMouseOver(x, y)) { return true; }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** True when a non-vanilla widget currently owns keyboard focus. */
    public static boolean foreignFocused(Screen screen) {
        for (GuiEventListener g : foreign(screen)) {
            try {
                if (g.isFocused()) { return true; }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static List<GuiEventListener> foreign(Screen screen) {
        List<GuiEventListener> out = new ArrayList<GuiEventListener>();
        if (screen == null) { return out; }
        try {
            List<?> children = childrenOf(screen);
            if (children == null) { return out; }
            for (Object w : children) {
                if (w instanceof GuiEventListener && !isVanilla(w)) {
                    out.add((GuiEventListener) w);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean isVanilla(Object w) {
        String name = w.getClass().getName();
        return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.");
    }

    // ---- shape-based discovery --------------------------------------------

    /**
     * Children by name first, then by shape (first no-arg method returning a
     * List). Both recording and playback take the SAME path, so the index is
     * symmetric even if a version exposes renderables instead of children.
     */
    private static List<?> childrenOf(Screen screen) {
        Object named = ScreenReflect.call(screen, "children", "getChildren", "getRenderables");
        if (named instanceof List && looksLikeWidgets((List<?>) named)) {
            return (List<?>) named;
        }
        // Production mappings rename children() to method_XXXX. Collect every
        // no-arg List getter across the hierarchy and pick by CONTENT, not by
        // declaration order: the widget list is the one whose elements are
        // GuiEventListeners. Grabbing "the first List" used to silently pick
        // an unrelated collection (items, tabs), which made the whole overlay
        // passthrough a no-op with no error in the log.
        List<?> best = null;
        for (Class<?> k = screen.getClass(); k != null && k != Object.class;
             k = k.getSuperclass()) {
            Method[] all;
            try { all = k.getDeclaredMethods(); } catch (Throwable ignored) { continue; }
            for (Method m : all) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!List.class.isAssignableFrom(m.getReturnType())) { continue; }
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(screen);
                    if (!(r instanceof List)) { continue; }
                    List<?> candidate = (List<?>) r;
                    if (looksLikeWidgets(candidate)) { return candidate; }
                    if (best == null && !candidate.isEmpty()) { best = candidate; }
                } catch (Throwable ignored) {
                }
            }
        }
        return best;
    }

    /** A widget list is non-empty and every element is a GuiEventListener. */
    private static boolean looksLikeWidgets(List<?> list) {
        if (list == null || list.isEmpty()) { return false; }
        for (Object o : list) {
            if (!(o instanceof GuiEventListener)) { return false; }
        }
        return true;
    }

    private static Method textGetter(Object w) {
        Method m = namedGetter(w, GET_NAMES, String.class);
        return m != null ? m : anyGetter(w, String.class);
    }

    private static Method textSetter(Object w) {
        Method m = namedSetter(w, SET_NAMES, String.class);
        return m != null ? m : anySetter(w, String.class);
    }

    private static Method namedGetter(Object w, String[] names, Class<?> ret) {
        return find(w, names, 0, ret, null);
    }

    private static Method namedSetter(Object w, String[] names, Class<?> param) {
        return find(w, names, 1, null, param);
    }

    /** Shape-only fallback: production mappings rename every method. */
    private static Method anyGetter(Object w, Class<?> ret) {
        return find(w, null, 0, ret, null);
    }

    private static Method anySetter(Object w, Class<?> param) {
        return find(w, null, 1, null, param);
    }

    private static Method find(Object target, String[] names, int paramCount,
                               Class<?> ret, Class<?> param) {
        if (target == null) { return null; }
        for (Class<?> k = target.getClass(); k != null && k != Object.class;
             k = k.getSuperclass()) {
            Method[] all;
            try { all = k.getDeclaredMethods(); } catch (Throwable ignored) { continue; }
            for (Method m : all) {
                if (m.getParameterCount() != paramCount) { continue; }
                if (Modifier.isStatic(m.getModifiers())) { continue; }
                if (ret != null && m.getReturnType() != ret) { continue; }
                if (param != null && !boxed(m.getParameterTypes()[0]).equals(boxed(param))) {
                    continue;
                }
                if (names != null) {
                    boolean hit = false;
                    for (String n : names) {
                        if (m.getName().equals(n)) { hit = true; break; }
                    }
                    if (!hit) { continue; }
                }
                try { m.setAccessible(true); } catch (Throwable ignored) { continue; }
                return m;
            }
        }
        return null;
    }

    private static Object invoke(Method m, Object target, Object... args) {
        if (m == null) { return null; }
        try {
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String textOf(Object w, Method getter) {
        Object v = invoke(getter, w);
        return v instanceof CharSequence ? v.toString() : "";
    }

    private static String clean(String s, int max) {
        String v = s == null ? "" : s;
        StringBuilder out = new StringBuilder(Math.min(v.length(), max));
        for (int i = 0; i < v.length() && out.length() < max; i++) {
            char c = v.charAt(i);
            if (c == UNIT || c == FIELD || c < 0x20) { c = ' '; }
            out.append(c);
        }
        return out.toString();
    }

    private static Class<?> boxed(Class<?> t) {
        if (t == boolean.class) { return Boolean.class; }
        if (t == int.class) { return Integer.class; }
        if (t == long.class) { return Long.class; }
        if (t == double.class) { return Double.class; }
        if (t == float.class) { return Float.class; }
        if (t == char.class) { return Character.class; }
        if (t == byte.class) { return Byte.class; }
        if (t == short.class) { return Short.class; }
        return t;
    }
}
