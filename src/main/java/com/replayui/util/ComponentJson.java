package com.replayui.util;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component <-> JSON without a compile-time dependency on the Serializer's
 * {@code HolderLookup.Provider} overload. 1.21.1 changed that signature, and
 * guessing it would turn a mapping change into a startup crash.
 *
 * <p>The reflective Serializer is tried first (with an explicitly supplied
 * registry access when the caller has one). If it cannot be resolved we do NOT
 * drop styling and we do NOT print the raw JSON as text: a small hand-written
 * codec below carries text, extra siblings, colour, the five boolean style
 * flags, insertion and click/hover events. That covers book pages, sign lines
 * and custom container names even on an unexpected mapping.
 */
public final class ComponentJson {

    private static final String SERIALIZER =
            "net.minecraft.network.chat.Component$Serializer";

    private ComponentJson() {
    }

    /** Uses the client's registry access when available. */
    public static String toJson(Component component) {
        return toJson(component, registryAccess());
    }

    public static String toJson(Component component, Object suppliedAccess) {
        if (component == null) { return ""; }
        Object access = suppliedAccess == null ? registryAccess() : suppliedAccess;
        Class<?> ser = ScreenReflect.find(SERIALIZER);
        if (ser != null) {
            Method[] methods;
            try { methods = ser.getDeclaredMethods(); }
            catch (Throwable t) { methods = new Method[0]; }
            // Prefer the 2-argument overload when we have an access object.
            for (int pass = 0; pass < 2; pass++) {
                int wanted = pass == 0 && access != null ? 2 : 1;
                if (pass == 1 && wanted == 1 && access == null) { break; }
                for (Method m : methods) {
                    if (!m.getName().equals("toJson")
                            || !Modifier.isStatic(m.getModifiers())) { continue; }
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length != wanted) { continue; }
                    Object[] args = new Object[types.length];
                    args[0] = component;
                    if (wanted == 2) { args[1] = access; }
                    try {
                        m.setAccessible(true);
                        Object out = m.invoke(null, args);
                        if (out instanceof String) { return (String) out; }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return manualToJson(component);
    }

    /** Uses the client's registry access when available. */
    public static Component fromJson(String json) {
        return fromJson(json, registryAccess());
    }

    public static Component fromJson(String json, Object suppliedAccess) {
        if (json == null || json.isEmpty()) { return null; }
        Object access = suppliedAccess == null ? registryAccess() : suppliedAccess;
        Class<?> ser = ScreenReflect.find(SERIALIZER);
        if (ser != null && json.trim().startsWith("{")) {
            Method[] methods;
            try { methods = ser.getDeclaredMethods(); }
            catch (Throwable t) { methods = new Method[0]; }
            for (int pass = 0; pass < 2; pass++) {
                int wanted = pass == 0 && access != null ? 2 : 1;
                if (pass == 1 && wanted == 1 && access == null) { break; }
                for (Method m : methods) {
                    if (!m.getName().equals("fromJson")
                            || !Modifier.isStatic(m.getModifiers())) { continue; }
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length != wanted) { continue; }
                    Object[] args = new Object[types.length];
                    args[0] = json;
                    if (wanted == 2) { args[1] = access; }
                    try {
                        m.setAccessible(true);
                        Object out = m.invoke(null, args);
                        if (out instanceof Component) { return (Component) out; }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return manualFromJson(json);
    }

    // ---- manual serialisation --------------------------------------------

    private static String manualToJson(Component component) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("text", component.getString());
        Object style = ScreenReflect.call(component, "getStyle", "style");
        if (style != null) {
            putIf(root, "color", colorOf(style));
            putBool(root, "bold", ScreenReflect.call(style, "isBold", "bold"));
            putBool(root, "italic", ScreenReflect.call(style, "isItalic", "italic"));
            putBool(root, "underlined", ScreenReflect.call(style, "isUnderlined", "underlined"));
            putBool(root, "strikethrough", ScreenReflect.call(style, "isStrikethrough", "strikethrough"));
            putBool(root, "obfuscated", ScreenReflect.call(style, "isObfuscated", "obfuscated"));
            putIf(root, "insertion", str(ScreenReflect.call(style, "getInsertion", "insertion")));
            Object click = ScreenReflect.call(style, "getClickEvent", "clickEvent");
            if (click != null) {
                Map<String, Object> ev = eventOf(click, "getAction", "getValue");
                if (ev != null) { root.put("clickEvent", ev); }
            }
            Object hover = ScreenReflect.call(style, "getHoverEvent", "hoverEvent");
            if (hover != null) {
                Map<String, Object> ev = eventOf(hover, "getAction", "getValue");
                if (ev != null) { root.put("hoverEvent", ev); }
            }
        }
        Object siblings = ScreenReflect.call(component, "getSiblings", "siblings");
        if (siblings instanceof Iterable) {
            List<Object> extra = new ArrayList<Object>();
            for (Object s : (Iterable<?>) siblings) {
                if (s instanceof Component) {
                    extra.add(parse(manualToJson((Component) s)));
                }
            }
            if (!extra.isEmpty()) { root.put("extra", extra); }
        }
        return stringify(root);
    }

    private static String colorOf(Object style) {
        Object c = ScreenReflect.call(style, "getColor", "color");
        if (c == null) { return ""; }
        Object s = ScreenReflect.call(c, "serialize", "toString", "getName");
        return str(s == null ? String.valueOf(c) : s);
    }

    private static Map<String, Object> eventOf(Object event, String actionName, String valueName) {
        Object action = ScreenReflect.call(event, actionName);
        Object value = ScreenReflect.call(event, valueName);
        if (action == null || value == null) { return null; }
        Object name = ScreenReflect.call(action, "getSerializedName", "getName", "name");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("action", str(name == null ? String.valueOf(action) : name));
        out.put("value", str(value));
        return out;
    }

    private static void putIf(Map<String, Object> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) { map.put(key, value); }
    }

    private static void putBool(Map<String, Object> map, String key, Object value) {
        if (value instanceof Boolean && ((Boolean) value).booleanValue()) {
            map.put(key, Boolean.TRUE);
        }
    }

    // ---- manual deserialisation ------------------------------------------

    private static Component manualFromJson(String json) {
        Object parsed = json.trim().startsWith("{") ? parse(json) : null;
        if (!(parsed instanceof Map)) {
            // Not JSON at all - a legacy plain-text value.
            return Component.literal(json);
        }
        return fromMap((Map<?, ?>) parsed);
    }

    private static Component fromMap(Map<?, ?> map) {
        String text = str(map.get("text"));
        if (text.isEmpty()) { text = str(map.get("translate")); }
        if (text.isEmpty()) { text = str(map.get("keybind")); }
        Component result = Component.literal(text);

        Object extras = map.get("extra");
        if (extras instanceof Iterable) {
            for (Object e : (Iterable<?>) extras) {
                if (e instanceof Map) {
                    Component child = fromMap((Map<?, ?>) e);
                    Object appended = ScreenReflect.callAny(result, child, "append", "appendSibling");
                    if (appended instanceof Component) { result = (Component) appended; }
                }
            }
        }
        Object style = buildStyle(map);
        if (style != null) {
            Object styled = ScreenReflect.callAny(result, style, "withStyle", "setStyle");
            if (styled instanceof Component) { result = (Component) styled; }
        }
        return result;
    }

    private static Object buildStyle(Map<?, ?> map) {
        Class<?> styleClass = ScreenReflect.find("net.minecraft.network.chat.Style");
        if (styleClass == null) { return null; }
        Object style = ScreenReflect.getStatic(styleClass, "EMPTY");
        if (style == null) {
            style = ScreenReflect.callStatic(styleClass,
                    new Class<?>[0], new Object[0], "empty", "of");
        }
        if (style == null) { return null; }

        String color = str(map.get("color"));
        if (!color.isEmpty()) {
            Object applied = applyColor(style, color);
            if (applied != null) { style = applied; }
        }
        style = applyFlag(style, map.get("bold"), "withBold");
        style = applyFlag(style, map.get("italic"), "withItalic");
        style = applyFlag(style, map.get("underlined"), "withUnderlined");
        style = applyFlag(style, map.get("strikethrough"), "withStrikethrough");
        style = applyFlag(style, map.get("obfuscated"), "withObfuscated");
        String insertion = str(map.get("insertion"));
        if (!insertion.isEmpty()) {
            Object s = ScreenReflect.callAny(style, insertion, "withInsertion");
            if (s != null) { style = s; }
        }
        Object click = map.get("clickEvent");
        if (click instanceof Map) {
            Object ev = buildEvent((Map<?, ?>) click,
                    "net.minecraft.network.chat.ClickEvent", "clickEvent");
            if (ev != null) {
                Object s = ScreenReflect.callAny(style, ev, "withClickEvent");
                if (s != null) { style = s; }
            }
        }
        Object hover = map.get("hoverEvent");
        if (hover instanceof Map) {
            Object ev = buildEvent((Map<?, ?>) hover,
                    "net.minecraft.network.chat.HoverEvent", "hoverEvent");
            if (ev != null) {
                Object s = ScreenReflect.callAny(style, ev, "withHoverEvent");
                if (s != null) { style = s; }
            }
        }
        return style;
    }

    private static Object applyColor(Object style, String color) {
        // 1) Style#withColor(TextColor) built from TextColor.parseColor/fromRgb.
        Class<?> textColor = ScreenReflect.find("net.minecraft.network.chat.TextColor");
        Object tc = null;
        if (textColor != null) {
            if (color.startsWith("#")) {
                try {
                    int rgb = Integer.parseInt(color.substring(1), 16);
                    tc = ScreenReflect.callStatic(textColor,
                            new Class<?>[]{int.class}, new Object[]{Integer.valueOf(rgb)},
                            "fromRgb", "fromRgbLegacy", "of");
                } catch (Throwable ignored) {
                }
            }
            if (tc == null) {
                tc = ScreenReflect.callStatic(textColor,
                        new Class<?>[]{String.class}, new Object[]{color},
                        "parseColor", "fromRgb", "of");
            }
        }
        if (tc != null) {
            Object s = ScreenReflect.callAny(style, tc, "withColor", "setColor");
            if (s != null) { return s; }
        }
        // 2) ChatFormatting by name.
        Class<?> formatting = ScreenReflect.find("net.minecraft.ChatFormatting");
        if (formatting != null) {
            Object fmt = ScreenReflect.callStatic(formatting,
                    new Class<?>[]{String.class}, new Object[]{color.toUpperCase()},
                    "getByName", "valueOf", "byName");
            if (fmt != null) {
                Object s = ScreenReflect.callAny(style, fmt, "withColor", "applyFormat");
                if (s != null) { return s; }
            }
        }
        return null;
    }

    private static Object applyFlag(Object style, Object value, String method) {
        if (!(value instanceof Boolean)) { return style; }
        Object s = ScreenReflect.callAny(style, value, method);
        return s == null ? style : s;
    }

    private static Object buildEvent(Map<?, ?> map, String className, String unused) {
        String action = str(map.get("action"));
        String value = str(map.get("value"));
        if (action.isEmpty() || value.isEmpty()) { return null; }
        Class<?> cls = ScreenReflect.find(className);
        if (cls == null) { return null; }
        Object ev = ScreenReflect.construct(cls, action, value);
        if (ev == null) { ev = ScreenReflect.construct(cls, value, action); }
        if (ev != null) { return ev; }
        // Enum action + String value.
        Class<?>[] nested = cls.getDeclaredClasses();
        for (Class<?> n : nested) {
            if (!n.isEnum()) { continue; }
            for (Object c : n.getEnumConstants()) {
                if (((Enum<?>) c).name().equalsIgnoreCase(action)) {
                    return ScreenReflect.construct(cls, c, value);
                }
            }
        }
        return null;
    }

    // ---- tiny JSON parser / writer ---------------------------------------

    private static Object parse(String json) {
        try { return new Parser(json).value(); } catch (Throwable ignored) { return null; }
    }

    private static final class Parser {
        private final String s;
        private int i;
        Parser(String s) { this.s = s; }

        Object value() {
            skip();
            if (i >= s.length()) { return null; }
            char c = s.charAt(i);
            if (c == '{') { return object(); }
            if (c == '[') { return array(); }
            if (c == '"') { return string(); }
            if (c == 't' && s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            i++; // {
            while (i < s.length()) {
                skip();
                if (i < s.length() && s.charAt(i) == '}') { i++; return out; }
                Object key = value();
                skip();
                if (i < s.length() && s.charAt(i) == ':') { i++; }
                out.put(String.valueOf(key), value());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; }
            }
            return out;
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<Object>();
            i++; // [
            while (i < s.length()) {
                skip();
                if (i < s.length() && s.charAt(i) == ']') { i++; return out; }
                out.add(value());
                skip();
                if (i < s.length() && s.charAt(i) == ',') { i++; }
            }
            return out;
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') { return b.toString(); }
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) { break; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                            catch (Throwable ignored) { }
                            i += 4;
                        }
                        break;
                    default: b.append(e); break;
                }
            }
            return b.toString();
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) { i++; }
            String n = s.substring(start, i);
            if (n.isEmpty()) { i++; return Integer.valueOf(0); }
            try {
                return n.contains(".") || n.contains("e") || n.contains("E")
                        ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
            } catch (Throwable ignored) {
                return Integer.valueOf(0);
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) { i++; }
        }
    }

    private static String stringify(Object value) {
        StringBuilder b = new StringBuilder();
        write(value, b);
        return b.toString();
    }

    private static void write(Object value, StringBuilder b) {
        if (value == null) { b.append("null"); return; }
        if (value instanceof String) { quote((String) value, b); return; }
        if (value instanceof Boolean || value instanceof Number) {
            b.append(value); return;
        }
        if (value instanceof Map) {
            b.append('{');
            boolean first = true;
            Map<?, ?> map = (Map<?, ?>) value;
            for (Object key : map.keySet()) {
                if (!first) { b.append(','); }
                first = false;
                quote(String.valueOf(key), b);
                b.append(':');
                write(map.get(key), b);
            }
            b.append('}');
            return;
        }
        if (value instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) value) {
                if (!first) { b.append(','); }
                first = false;
                write(o, b);
            }
            b.append(']');
            return;
        }
        quote(String.valueOf(value), b);
    }

    private static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) { b.append(String.format("\\u%04x", (int) c)); }
                    else { b.append(c); }
            }
        }
        b.append('"');
    }

    private static String str(Object o) {
        if (o == null) { return ""; }
        if (o instanceof Component) { return ((Component) o).getString(); }
        return String.valueOf(o);
    }

    /** Fully reflective: never depends on Level#registryAccess being mapped. */
    private static Object registryAccess() {
        try {
            Class<?> mcClass = ScreenReflect.find("net.minecraft.client.Minecraft");
            Object mc = ScreenReflect.callStatic(mcClass,
                    new Class<?>[0], new Object[0], "getInstance");
            Object level = ScreenReflect.get(mc, "level");
            return ScreenReflect.call(level, "registryAccess", "getRegistryAccess");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
