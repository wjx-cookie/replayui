package com.replayui.util;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft access to screen classes whose mapped names vary between versions and
 * which are not present at compile time. Every method here is total: a missing
 * class, field or constructor returns null / false, never throws.
 *
 * <p>This is why supporting the three special screens does not add a single
 * mixin. A wrong name means one screen is skipped with a verbose log line,
 * not a startup crash and not a compile-time dependency on a guessed API.
 *
 * <p>{@link #set} also performs a small, explicit set of conversions (enum by
 * ordinal/name, int to long/float/double, Object[] to a typed array). Several
 * recorded fields are enums or longs stored as integer slots; without this the
 * write would be silently skipped by the old "exact type only" check. Every
 * conversion is type-directed - nothing is written into a field that cannot
 * hold the resulting value.
 */
public final class ScreenReflect {

    private static final Map<String, Class<?>> CACHE = new HashMap<String, Class<?>>();
    private static final Object NO_VALUE = new Object();

    private ScreenReflect() {
    }

    /**
     * First class that can be resolved, or null.
     *
     * <p>A string class name is NOT remapped by Loom: a Mojang-mapped name like
     * {@code CreativeModeInventoryScreen} resolves in a dev workspace but is
     * {@code class_XXX} in a production launch. Every reflective screen path
     * therefore silently failed on a real install. Ask Fabric Loader's mapping
     * resolver for the intermediary name as a second candidate. Both are tried,
     * so dev and production work with the same code.
     */
    public static Class<?> find(String... names) {
        for (String name : names) {
            if (name == null) { continue; }
            Class<?> cached = CACHE.get(name);
            if (cached != null) { return cached; }
            Class<?> cls = load(name);
            if (cls == null) {
                String mapped = intermediaryName(name);
                if (mapped != null && !mapped.equals(name)) {
                    cls = load(mapped);
                }
            }
            if (cls == null && name.indexOf('$') > 0) {
                // Inner classes: map the outer part, keep the simple inner name.
                String outer = name.substring(0, name.indexOf('$'));
                String inner = name.substring(name.indexOf('$'));
                String mappedOuter = intermediaryName(outer);
                if (mappedOuter != null) {
                    cls = load(mappedOuter + inner);
                }
            }
            if (cls != null) {
                CACHE.put(name, cls);
                return cls;
            }
        }
        return null;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Reflective use of FabricLoader so no compile-time dependency is added. */
    private static String intermediaryName(String named) {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            Object resolver = loaderClass.getMethod("getMappingResolver").invoke(loader);
            Class<?> resolverClass = Class.forName("net.fabricmc.loader.api.MappingResolver");
            Object mapped = resolverClass
                    .getMethod("mapClassName", String.class, String.class)
                    .invoke(resolver, "intermediary", named);
            return mapped instanceof String ? (String) mapped : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isInstance(Class<?> cls, Object value) {
        return cls != null && value != null && cls.isInstance(value);
    }

    /**
     * First field whose declared type is exactly {@code type}. Mapped field
     * names are {@code field_XXX} in production, so name-based lookup alone
     * cannot find e.g. the player's connection. Type is stable across mappings.
     */
    public static Object getByType(Object target, Class<?> type) {
        if (target == null || type == null) { return null; }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Field[] all;
            try { all = k.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field f : all) {
                if (f.getType() != type) { continue; }
                try {
                    f.setAccessible(true);
                    return f.get(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * All instance fields of exactly {@code type}, in declaration order.
     *
     * <p>Mappings rename every field to {@code field_XXX}, but the order and
     * the types of the fields a value object carries are its shape. Reading
     * "the third ItemStack field" needs no name at all, so it survives both
     * a remap and a version bump that keeps the same layout.
     */
    public static List<Object> fieldsByType(Object target, Class<?> type) {
        List<Object> out = new ArrayList<Object>();
        if (target == null || type == null) { return out; }
        for (Class<?> k = target.getClass(); k != null && k != Object.class;
             k = k.getSuperclass()) {
            Field[] all;
            try { all = k.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field f : all) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != type) { continue; }
                try {
                    f.setAccessible(true);
                    out.add(f.get(target));
                } catch (Throwable ignored) {
                    out.add(null);
                }
            }
        }
        return out;
    }

    /**
     * First non-static enum field with exactly {@code constantCount} constants.
     * Mapped field names are {@code field_XXX} in production, but the shape of
     * a value is stable - the F3+F4 switcher's four GameModeIcon constants are
     * a good example.
     */
    public static Object firstEnumField(Object target, int constantCount) {
        if (target == null) { return null; }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Field[] all;
            try { all = k.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field f : all) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) { continue; }
                Class<?> t = f.getType();
                if (!t.isEnum()) { continue; }
                Object[] cs = t.getEnumConstants();
                if (cs == null || cs.length != constantCount) { continue; }
                try {
                    f.setAccessible(true);
                    return f.get(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** First non-static List field containing exactly {@code size} elements. */
    public static Object firstListField(Object target, int size) {
        if (target == null) { return null; }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Field[] all;
            try { all = k.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field f : all) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) { continue; }
                if (!List.class.isAssignableFrom(f.getType())) { continue; }
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v instanceof List<?> && ((List<?>) v).size() == size) { return v; }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * The {@code index}-th non-static boolean field, in declaration order.
     * Mapping-aware field names are preferred everywhere else; this is the last
     * resort for a version whose intermediary field numbers differ. Both the
     * recording and the playback side call this with the SAME index, so even a
     * wrong guess stays symmetric and cannot desync the wire format.
     */
    public static Boolean booleanFieldAt(Object target, int index) {
        if (target == null || index < 0) { return null; }
        int seen = 0;
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Field[] all;
            try { all = k.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field f : all) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) { continue; }
                if (f.getType() != boolean.class) { continue; }
                if (seen++ != index) { continue; }
                try {
                    f.setAccessible(true);
                    return Boolean.valueOf(f.getBoolean(target));
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static boolean setBooleanFieldAt(Object target, int index, boolean value) {
        if (target == null || index < 0) { return false; }
        int seen = 0;
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Field[] all;
            try { all = k.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field f : all) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) { continue; }
                if (f.getType() != boolean.class) { continue; }
                if (seen++ != index) { continue; }
                try {
                    f.setAccessible(true);
                    f.setBoolean(target, value);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /** First no-arg method whose return type is exactly {@code type}. */
    public static Object callReturning(Object target, Class<?> type) {
        if (target == null || type == null) { return null; }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Method[] all;
            try { all = k.getDeclaredMethods(); } catch (Throwable ignored) { continue; }
            for (Method m : all) {
                if (m.getParameterCount() != 0 || m.getReturnType() != type) { continue; }
                try {
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * First no-arg method whose return type is assignable to {@code type}.
     *
     * <p>Production mappings rename {@code getOffers()} to {@code method_XXXX},
     * but "the one method on this menu that returns an Iterable of offers" is
     * a shape, not a name. Used as the fallback when name lookup misses, so a
     * remap or a version bump cannot silently empty the merchant list.
     */
    public static Object callReturningAssignable(Object target, Class<?> type) {
        if (target == null || type == null) { return null; }
        for (Class<?> k = target.getClass(); k != null && k != Object.class;
             k = k.getSuperclass()) {
            Method[] all;
            try { all = k.getDeclaredMethods(); } catch (Throwable ignored) { continue; }
            for (Method m : all) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!type.isAssignableFrom(m.getReturnType())) { continue; }
                try {
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** Read the first field with one of these names, walking superclasses. */
    public static Object get(Object target, String... names) {
        if (target == null) { return null; }
        for (String name : names) {
            Field f = field(target.getClass(), name);
            if (f == null) { continue; }
            try {
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Same, for a static field. */
    public static Object getStatic(Class<?> cls, String... names) {
        if (cls == null) { return null; }
        for (String name : names) {
            Field f = field(cls, name);
            if (f == null) { continue; }
            try {
                f.setAccessible(true);
                return f.get(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Write the first field whose type can hold value (after coercion). */
    public static boolean set(Object target, Object value, String... names) {
        if (target == null) { return false; }
        for (String name : names) {
            Field f = field(target.getClass(), name);
            if (f == null) { continue; }
            Object v = coerce(f.getType(), value);
            if (v == NO_VALUE) { continue; }
            try {
                f.setAccessible(true);
                f.set(target, v);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean setStatic(Class<?> cls, Object value, String... names) {
        if (cls == null) { return false; }
        for (String name : names) {
            Field f = field(cls, name);
            if (f == null) { continue; }
            Object v = coerce(f.getType(), value);
            if (v == NO_VALUE) { continue; }
            try {
                f.setAccessible(true);
                f.set(null, v);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Invoke the first no-arg method with one of these names. */
    public static Object call(Object target, String... names) {
        return callWith(target, new Class<?>[0], new Object[0], names);
    }

    /** Invoke the first method with these parameter types. */
    public static Object callWith(Object target, Class<?>[] types, Object[] args,
                                  String... names) {
        if (target == null) { return null; }
        for (String name : names) {
            Method m = method(target.getClass(), name, types);
            if (m == null) { continue; }
            try {
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static Object callStatic(Class<?> cls, Class<?>[] types, Object[] args,
                                    String... names) {
        if (cls == null) { return null; }
        for (String name : names) {
            Method m = method(cls, name, types);
            if (m == null) { continue; }
            try {
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * One-argument call matched by NAME and assignability, not by exact
     * signature. Needed for methods whose parameter is a mapped interface
     * (Level, BlockPos, ResourceLocation) that we cannot name at compile time.
     */
    public static Object callAny(Object target, Object arg, String... names) {
        if (target == null || arg == null) { return null; }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            Method[] all;
            try {
                all = k.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method m : all) {
                if (m.getParameterCount() != 1) { continue; }
                boolean named = false;
                for (String n : names) {
                    if (m.getName().equals(n)) { named = true; break; }
                }
                if (!named) { continue; }
                if (!boxed(m.getParameterTypes()[0]).isInstance(arg)) { continue; }
                try {
                    m.setAccessible(true);
                    return m.invoke(target, arg);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Try every declared constructor, filling parameters from preferred values
     * by type. Primitives get a default when nothing suitable was supplied.
     * Returns null if no constructor can be satisfied.
     */
    public static Object construct(Class<?> cls, Object... preferred) {
        if (cls == null) { return null; }
        Constructor<?>[] all;
        try {
            all = cls.getDeclaredConstructors();
        } catch (Throwable t) {
            return null;
        }
        for (Constructor<?> c : all) {
            try {
                c.setAccessible(true);
                Class<?>[] types = c.getParameterTypes();
                Object[] args = new Object[types.length];
                boolean ok = true;
                for (int i = 0; i < types.length; i++) {
                    Object v = pick(types[i], preferred);
                    if (v == NO_VALUE) { ok = false; break; }
                    args[i] = v;
                }
                if (!ok) { continue; }
                return c.newInstance(args);
            } catch (Throwable ignored) {
                // Try the next signature.
            }
        }
        return null;
    }

    private static Object pick(Class<?> type, Object[] preferred) {
        Class<?> boxed = boxed(type);
        for (Object p : preferred) {
            if (p == null) { continue; }
            if (boxed.isInstance(p)) { return p; }
        }
        if (type == int.class) { return Integer.valueOf(0); }
        if (type == long.class) { return Long.valueOf(0L); }
        if (type == float.class) { return Float.valueOf(0.0f); }
        if (type == double.class) { return Double.valueOf(0.0); }
        if (type == boolean.class) { return Boolean.FALSE; }
        if (type == short.class) { return Short.valueOf((short) 0); }
        if (type == byte.class) { return Byte.valueOf((byte) 0); }
        if (type == char.class) { return Character.valueOf('\0'); }
        if (!type.isPrimitive()) { return null; }
        return NO_VALUE;
    }

    private static Class<?> boxed(Class<?> t) {
        if (t == int.class) { return Integer.class; }
        if (t == long.class) { return Long.class; }
        if (t == float.class) { return Float.class; }
        if (t == double.class) { return Double.class; }
        if (t == boolean.class) { return Boolean.class; }
        if (t == short.class) { return Short.class; }
        if (t == byte.class) { return Byte.class; }
        if (t == char.class) { return Character.class; }
        return t;
    }

    private static boolean compatible(Class<?> type, Object value) {
        Class<?> boxed = boxed(type);
        if (value == null) { return !type.isPrimitive(); }
        return boxed.isInstance(value);
    }

    /**
     * Convert a recorded value into something the field can actually hold.
     * Only well-defined, type-directed conversions happen here; everything else
     * is left untouched (NO_VALUE) so the caller skips the field.
     */
    private static Object coerce(Class<?> type, Object value) {
        if (compatible(type, value)) { return value; }
        if (value == null) { return NO_VALUE; }

        if (type.isEnum()) {
            Object[] all = type.getEnumConstants();
            if (all == null) { return NO_VALUE; }
            if (value instanceof Number) {
                int i = ((Number) value).intValue();
                if (i >= 0 && i < all.length) { return all[i]; }
            }
            if (value instanceof CharSequence) {
                String s = value.toString();
                for (Object c : all) {
                    if (((Enum<?>) c).name().equalsIgnoreCase(s)) { return c; }
                }
            }
            return NO_VALUE;
        }
        if ((type == long.class || type == Long.class) && value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if ((type == int.class || type == Integer.class) && value instanceof Number
                && !(value instanceof Float) && !(value instanceof Double)) {
            return Integer.valueOf(((Number) value).intValue());
        }
        if ((type == float.class || type == Float.class) && value instanceof Number) {
            return Float.valueOf(((Number) value).floatValue());
        }
        if ((type == double.class || type == Double.class) && value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Number) {
                return Boolean.valueOf(((Number) value).intValue() != 0);
            }
        }
        if (type.isArray()) {
            return coerceArray(type.getComponentType(), value);
        }
        return NO_VALUE;
    }

    /** Object[] / Iterable -> a typed array, converting each element. */
    private static Object coerceArray(Class<?> comp, Object value) {
        List<Object> src = new ArrayList<Object>();
        if (value instanceof Object[]) {
            for (Object o : (Object[]) value) { src.add(o); }
        } else if (value instanceof Iterable) {
            for (Object o : (Iterable<?>) value) { src.add(o); }
        } else {
            return NO_VALUE;
        }
        Object out = Array.newInstance(comp, src.size());
        for (int i = 0; i < src.size(); i++) {
            Object e = src.get(i);
            Object c = coerce(comp, e);
            if (c == NO_VALUE) {
                if (comp == String.class) {
                    c = stringOf(e);
                } else {
                    return NO_VALUE;
                }
            }
            try {
                Array.set(out, i, c);
            } catch (Throwable ignored) {
                return NO_VALUE;
            }
        }
        return out;
    }

    /** Best effort text for a Component-like object, without a compile-time dep. */
    private static String stringOf(Object o) {
        if (o == null) { return ""; }
        if (o instanceof CharSequence) { return o.toString(); }
        Object s = call(o, "getString", "getText", "getStringValue", "getContents");
        return s == null ? String.valueOf(o) : String.valueOf(s);
    }

    private static Field field(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method method(Class<?> cls, String name, Class<?>[] types) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
