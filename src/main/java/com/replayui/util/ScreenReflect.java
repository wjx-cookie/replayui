package com.replayui.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Soft access to screen classes whose mapped names vary between versions and
 * which are not present at compile time. Every method here is total: a missing
 * class, field or constructor returns null / false, never throws.
 *
 * <p>This is why supporting the three special screens does not add a single
 * mixin. A wrong name means one screen is skipped with a verbose log line,
 * not a startup crash and not a compile-time dependency on a guessed API.
 */
public final class ScreenReflect {

    private static final Map<String, Class<?>> CACHE = new HashMap<String, Class<?>>();

    private ScreenReflect() {
    }

    /** First class that can be resolved, or null. */
    public static Class<?> find(String... names) {
        for (String name : names) {
            if (name == null) { continue; }
            Class<?> cached = CACHE.get(name);
            if (cached != null) { return cached; }
            try {
                Class<?> cls = Class.forName(name);
                CACHE.put(name, cls);
                return cls;
            } catch (Throwable ignored) {
                // Not present in this version / not loaded yet.
            }
        }
        return null;
    }

    public static boolean isInstance(Class<?> cls, Object value) {
        return cls != null && value != null && cls.isInstance(value);
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

    /** Write the first field whose type can actually hold value. */
    public static boolean set(Object target, Object value, String... names) {
        if (target == null) { return false; }
        for (String name : names) {
            Field f = field(target.getClass(), name);
            if (f == null) { continue; }
            if (!compatible(f.getType(), value)) { continue; }
            try {
                f.setAccessible(true);
                f.set(target, value);
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
            if (!compatible(f.getType(), value)) { continue; }
            try {
                f.setAccessible(true);
                f.set(null, value);
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

    private static final Object NO_VALUE = new Object();

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
