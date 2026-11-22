package com.replayui.config;

import com.replayui.ReplayUIConfig;
import com.replayui.render.CursorTexture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Persistence for the switches shown in the Mod Menu config screen.
 *
 * <p>The file is the same {@code config/replayui/cursor.properties} that
 * {@link CursorTexture} already reads. Keys the screen owns are deliberately
 * reused with the same names, so the screen never has two competing sources
 * for one setting. Keys the screen does not show ({@code texture},
 * {@code outline}, {@code hotspotX/Y}, {@code useSystemCursor}) are preserved
 * verbatim on save.
 *
 * <p>Core behaviours are intentionally absent from both this screen and this
 * file. Older builds exposed them as switches; {@link #LEGACY_KEYS} lists
 * those keys so a save cleans them up, and {@link #load()} deliberately never
 * reads them. That way a stale {@code enabled=false} left behind by an old
 * GUI cannot silently disable the mod after an upgrade.
 *
 * <p>Loading happens in a static block of {@link ReplayUIConfig}: every
 * consumer of a switch sees the file's values before it runs.
 */
public final class ConfigStore {

    private static volatile File configDir =
            new File("config" + File.separator + "replayui");

    /** The keys the screen writes, in a stable order. */
    public static final String[] SCREEN_KEYS = {
            "followSystemCursorStyle", "followSystemCursorSize",
            "showRecordedTooltips", "showSpecialScreens", "scale", "maxHeight"
    };

    /** Switches removed from the screen; dropped from the file on save. */
    public static final String[] LEGACY_KEYS = {
            "enabled", "showContainerUi", "showRecordedPlayer", "showCursorIcon",
            "includeUiInExport", "smoothCursor", "pinOnInteraction", "animateItemMoves"
    };

    /** Editable snapshot; the screen mutates one of these and saves on Done. */
    public static final class Values {
        public boolean followSystemCursorStyle;
        public boolean followSystemCursorSize;
        public boolean showRecordedTooltips;
        public boolean showSpecialScreens;
        public double scale;
        public int maxHeight;
    }

    private ConfigStore() {
    }

    /** Test / integration hook. */
    public static void setConfigDirectory(File dir) {
        configDir = dir;
    }

    public static File file() {
        return new File(configDir, "cursor.properties");
    }

    /** Current live values of the editable preferences. */
    public static Values read() {
        Values v = new Values();
        v.followSystemCursorStyle = ReplayUIConfig.followSystemCursorStyle;
        v.followSystemCursorSize = ReplayUIConfig.followSystemCursorSize;
        v.showRecordedTooltips = ReplayUIConfig.showRecordedTooltips;
        v.showSpecialScreens = ReplayUIConfig.showSpecialScreens;
        v.scale = ReplayUIConfig.cursorScale;
        v.maxHeight = ReplayUIConfig.cursorMaxHeight;
        return v;
    }

    /** File -> live preferences. Legacy switches are never read back. */
    public static void load() {
        Properties p = new Properties();
        File f = file();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException | RuntimeException ignored) {
                return; // A broken config must never stop the mod.
            }
        }
        Values v = read();
        v.followSystemCursorStyle = bool(p, "followSystemCursorStyle", v.followSystemCursorStyle);
        v.followSystemCursorSize = bool(p, "followSystemCursorSize", v.followSystemCursorSize);
        v.showRecordedTooltips = bool(p, "showRecordedTooltips", v.showRecordedTooltips);
        v.showSpecialScreens = bool(p, "showSpecialScreens", v.showSpecialScreens);
        v.scale = number(p, "scale", v.scale, 0.25, 4.0);
        v.maxHeight = (int) Math.round(number(p, "maxHeight", v.maxHeight, 8, 128));
        apply(v);
        debug("config loaded from " + f.getPath());
    }

    /** Snapshot -> live switches + file, preserving keys the screen does not own. */
    public static void save(Values v) {
        v.scale = clamp(v.scale, 0.25, 4.0);
        v.maxHeight = (int) Math.round(clamp(v.maxHeight, 8, 128));
        apply(v);

        Properties existing = new Properties();
        File f = file();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                existing.load(in);
            } catch (IOException | RuntimeException ignored) {
                // Start from a clean file rather than failing the save.
            }
        }
        Map<String, String> screen = keys(v);
        Set<String> retired = new HashSet<>(Arrays.asList(LEGACY_KEYS));
        Properties out = new Properties();
        for (String name : existing.stringPropertyNames()) {
            if (!screen.containsKey(name) && !retired.contains(name)) {
                out.setProperty(name, existing.getProperty(name));
            }
        }
        for (Map.Entry<String, String> e : screen.entrySet()) {
            out.setProperty(e.getKey(), e.getValue());
        }
        try {
            File dir = f.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            try (OutputStream os = new FileOutputStream(f)) {
                out.store(os, "ReplayUI - edited in Mod Menu, or by hand");
            }
        } catch (IOException | RuntimeException ignored) {
            debug("config save failed: " + f.getPath());
        }
        // The cursor settings live in the same file; drop the 2 s cache so
        // Done takes effect on the very next frame.
        CursorTexture.invalidate();
        debug("config saved to " + f.getPath());
    }

    /** Public for the screen and tests: the exact key/value pairs written. */
    public static Map<String, String> keys(Values v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("followSystemCursorStyle", String.valueOf(v.followSystemCursorStyle));
        m.put("followSystemCursorSize", String.valueOf(v.followSystemCursorSize));
        m.put("showRecordedTooltips", String.valueOf(v.showRecordedTooltips));
        m.put("showSpecialScreens", String.valueOf(v.showSpecialScreens));
        m.put("scale", String.valueOf(clamp(v.scale, 0.25, 4.0)));
        m.put("maxHeight", String.valueOf((int) Math.round(clamp(v.maxHeight, 8, 128))));
        return m;
    }

    private static void apply(Values v) {
        ReplayUIConfig.followSystemCursorStyle = v.followSystemCursorStyle;
        ReplayUIConfig.followSystemCursorSize = v.followSystemCursorSize;
        ReplayUIConfig.showRecordedTooltips = v.showRecordedTooltips;
        ReplayUIConfig.showSpecialScreens = v.showSpecialScreens;
        ReplayUIConfig.cursorScale = clamp(v.scale, 0.25, 4.0);
        ReplayUIConfig.cursorMaxHeight = (int) Math.round(clamp(v.maxHeight, 8, 128));
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String s = p.getProperty(key);
        return s == null ? fallback : Boolean.parseBoolean(s.trim());
    }

    private static double number(Properties p, String key, double fallback,
                                 double lo, double hi) {
        String s = p.getProperty(key);
        if (s == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(s.trim());
            return d >= lo && d <= hi ? d : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void debug(String message) {
        if (Boolean.getBoolean("replayui.debug")) {
            System.out.println("[replayui] " + message);
        }
    }
}
