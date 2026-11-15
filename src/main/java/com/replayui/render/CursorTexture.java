package com.replayui.render;

import com.replayui.ReplayUIConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Where the recorded pointer's picture comes from.
 *
 * <p>The sprite is a PNG, not a hardcoded bitmap. Four sources are tried in
 * order, and each one that fails simply falls through to the next:
 *
 * <ol>
 *   <li>{@code config/replayui/cursor.png} - a plain file next to the game's
 *       config folder. This is the one a player edits; drop a PNG there and
 *       it wins over everything else.</li>
 *   <li>The resource-pack path named by {@code texture} in
 *       {@code config/replayui/cursor.properties}, default
 *       {@code replayui:textures/gui/recorded_cursor.png}. Any resource pack
 *       can override that path in the usual way. 1.21.1's
 *       {@code ResourceProvider#open(ResourceLocation)} resolves packs by
 *       priority, so no pack enumeration is needed here.</li>
 *   <li>The same PNG shipped inside the mod jar, read from the classpath.</li>
 *   <li>The built-in arrow bitmap in {@link RecordedCursorIcon}, so a broken
 *       or missing PNG can never make the pointer disappear.</li>
 * </ol>
 *
 * <p>The decoded pixels are drawn with {@code GuiGraphics#fill} rather than
 * bound as a texture and blitted. That is deliberate: a fill is another draw
 * call in the screen's existing batch, so it inherits the pose of the
 * {@code GuiGraphics} it is given. Binding a texture would flush that batch
 * and could detach the pointer from a scaled, rotated or Spatial GUI 3D panel.
 *
 * <p>Decoding is cached. Replacing a file or reloading resource packs takes
 * effect within {@link #TTL_NANOS} without a restart, without touching the
 * disk on every frame.
 */
public final class CursorTexture {

    /** Default resource-pack path, also used for the file inside the jar. */
    public static final String DEFAULT_TEXTURE = "replayui:textures/gui/recorded_cursor.png";

    /** Classpath location of the shipped PNG. */
    private static final String BUILT_IN_RESOURCE =
            "/assets/replayui/textures/gui/recorded_cursor.png";

    /** Refuse absurdly large images; a pointer is a few dozen pixels. */
    public static final int MAX_SIZE = 128;

    /** Two seconds. Resource reloads and file swaps show up quickly enough. */
    public static final long TTL_NANOS = 2_000_000_000L;

    private static final String CONFIG_DIR = "config" + File.separator + "replayui";
    private static final File EXTERNAL_PNG = new File(CONFIG_DIR, "cursor.png");
    private static final File CONFIG_FILE = new File(CONFIG_DIR, "cursor.properties");

    private static final int TRANSPARENT = 0;
    private static final int OUTLINE = 0xFF000000;

    /** A decoded, optionally outlined picture. {@code argb} is row-major. */
    public static final class Sprite {
        public final int width;
        public final int height;
        public final int[] argb;
        /** Pixels to shift the picture so its hotspot lands on the cursor. */
        public final int offsetX;
        public final int offsetY;

        Sprite(int width, int height, int[] argb, int offsetX, int offsetY) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    /** Text settings, read fresh on every reload. */
    private static final class Settings {
        String texture = ReplayUIConfig.cursorTexture;
        boolean outline = ReplayUIConfig.cursorOutline;
        int hotspotX = ReplayUIConfig.cursorHotspotX;
        int hotspotY = ReplayUIConfig.cursorHotspotY;
        boolean hotspotXSet;
        boolean hotspotYSet;
    }

    private static boolean loaded;
    private static long loadedAt;
    private static Sprite cached;
    private static String cachedSource = "built-in bitmap";
    private static Settings settings = new Settings();

    private CursorTexture() {
    }

    /** Current sprite, or {@code null} when every source failed (use bitmap). */
    public static Sprite get() {
        ensureLoaded();
        return cached;
    }

    public static String source() {
        ensureLoaded();
        return cachedSource;
    }

    public static Settings config() {
        ensureLoaded();
        return settings;
    }

    /** Force a reload on the next draw, e.g. after a resource reload. */
    public static void invalidate() {
        loaded = false;
        cached = null;
    }

    private static synchronized void ensureLoaded() {
        long now = System.nanoTime();
        if (loaded && now - loadedAt < TTL_NANOS) {
            return;
        }
        settings = readSettings();
        Sprite sprite = null;
        String source = "built-in bitmap";

        if (EXTERNAL_PNG.isFile()) {
            sprite = decode(openFile(EXTERNAL_PNG), settings);
            if (sprite != null) {
                source = EXTERNAL_PNG.getAbsolutePath();
            }
        }
        if (sprite == null) {
            sprite = loadFromPacks(settings);
            if (sprite != null) {
                source = settings.texture + " (resource packs)";
            }
        }
        if (sprite == null) {
            // The jar's PNG has its 1px outline baked in, so its tip sits at
            // (1,1). Only that picture needs the shift. A player's own PNG
            // (file or resource pack) keeps the configured hotspot, default
            // (0,0), and is drawn exactly as authored.
            Settings jar = settings;
            if (!settings.hotspotXSet || !settings.hotspotYSet) {
                jar = new Settings();
                jar.texture = settings.texture;
                jar.outline = settings.outline;
                jar.hotspotX = settings.hotspotXSet ? settings.hotspotX : 1;
                jar.hotspotY = settings.hotspotYSet ? settings.hotspotY : 1;
                jar.hotspotXSet = settings.hotspotXSet;
                jar.hotspotYSet = settings.hotspotYSet;
            }
            sprite = decode(builtInStream(), jar);
            if (sprite != null) {
                source = settings.texture + " (mod jar)";
            }
        }

        cached = sprite;
        cachedSource = source;
        loadedAt = now;
        loaded = true;
        if (isDebug()) {
            System.out.println("[replayui] cursor sprite: " + source
                    + (sprite == null ? " - falling back to built-in bitmap"
                            : " " + sprite.width + "x" + sprite.height));
        }
    }

    private static Settings readSettings() {
        Settings s = new Settings();
        Properties p = new Properties();
        if (CONFIG_FILE.isFile()) {
            try (InputStream in = new FileInputStream(CONFIG_FILE)) {
                p.load(in);
            } catch (IOException | RuntimeException ignored) {
                // A malformed config must not stop the pointer from drawing.
            }
        }
        String t = p.getProperty("texture");
        if (t != null && !t.trim().isEmpty()) {
            s.texture = t.trim();
        }
        String o = p.getProperty("outline");
        if (o != null) {
            s.outline = Boolean.parseBoolean(o.trim());
        }
        s.hotspotXSet = p.getProperty("hotspotX") != null;
        s.hotspotYSet = p.getProperty("hotspotY") != null;
        s.hotspotX = readInt(p, "hotspotX", s.hotspotX);
        s.hotspotY = readInt(p, "hotspotY", s.hotspotY);
        return s;
    }

    private static int readInt(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static InputStream openFile(File file) {
        try {
            return new FileInputStream(file);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static InputStream builtInStream() {
        return CursorTexture.class.getResourceAsStream(BUILT_IN_RESOURCE);
    }

    /**
     * Resource packs resolve by priority inside the manager, so asking it to
     * open the location is enough - no pack enumeration, no texture binding.
     * Every failure is swallowed: a wrong mapping name or an absent client
     * must degrade to the jar's own PNG, never break the screen.
     */
    private static Sprite loadFromPacks(Settings s) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return null;
            }
            ResourceManager manager = mc.getResourceManager();
            if (manager == null) {
                return null;
            }
            ResourceLocation location = ResourceLocation.parse(s.texture);
            InputStream in = manager.open(location);
            if (in == null) {
                return null;
            }
            try {
                return decode(in, s);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            debug("cursor resource '" + s.texture + "' unavailable: " + t);
            return null;
        }
    }

    /** Decode a PNG into a row-major ARGB array, then optionally add a border. */
    static Sprite decode(InputStream in, Settings s) {
        if (in == null) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return null;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (w < 1 || h < 1 || w > MAX_SIZE || h > MAX_SIZE) {
                debug("cursor PNG rejected: " + w + "x" + h
                        + " (max " + MAX_SIZE + "x" + MAX_SIZE + ")");
                return null;
            }
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = image.getRGB(x, y);
                    // Very faint pixels only cost fill calls and look like dirt.
                    pixels[y * w + x] = (argb >>> 24) < 16 ? TRANSPARENT : argb;
                }
            }
            if (!s.outline) {
                return new Sprite(w, h, pixels, s.hotspotX, s.hotspotY);
            }
            return outline(pixels, w, h, s);
        } catch (Throwable t) {
            debug("cursor PNG decode failed: " + t);
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing left to do with a stream we already finished with.
            }
        }
    }

    /**
     * Grow the picture by one pixel on each side and paint a black border into
     * the emptiness around every visible pixel. The original picture is then
     * laid on top, so its own colours are never overwritten. The hotspot
     * therefore moves to (1, 1) inside the grown canvas.
     */
    private static Sprite outline(int[] src, int w, int h, Settings s) {
        int ow = w + 2;
        int oh = h + 2;
        int[] out = new int[ow * oh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (src[y * w + x] == TRANSPARENT) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + 1 + dx;
                        int ny = y + 1 + dy;
                        if (nx >= 0 && ny >= 0 && nx < ow && ny < oh
                                && out[ny * ow + nx] == TRANSPARENT) {
                            out[ny * ow + nx] = OUTLINE;
                        }
                    }
                }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src[y * w + x];
                if (argb != TRANSPARENT) {
                    out[(y + 1) * ow + (x + 1)] = argb;
                }
            }
        }
        return new Sprite(ow, oh, out, 1 + s.hotspotX, 1 + s.hotspotY);
    }

    private static boolean isDebug() {
        return Boolean.getBoolean("replayui.debug");
    }

    private static void debug(String message) {
        if (isDebug()) {
            System.out.println("[replayui] " + message);
        }
    }
}
