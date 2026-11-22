package com.replayui.render;

import com.replayui.ReplayUIConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.function.DoubleSupplier;

/**
 * Where the recorded pointer comes from, and how big it is drawn.
 *
 * <p>Sources, in order. A player picture always wins over a system one:
 * <ol>
 *   <li>{@code config/replayui/cursor.png} (or a {@code texture=} pointing at
 *       a resource pack, or a pack overriding the default path).</li>
 *   <li>The desktop pointer, but only while {@code followSystemCursorStyle}
 *       is active - see below.</li>
 *   <li>The resource-pack / jar PNG.</li>
 *   <li>The built-in arrow bitmap in {@link RecordedCursorIcon}.</li>
 * </ol>
 *
 * <p>"Inactive" is deliberately not "off": when a player picture is present,
 * style following is overridden for as long as that picture exists, but the
 * stored option stays {@code true} and is never written back to the config.
 * Delete the picture and system following quietly resumes. Size following is
 * a separate switch and is never blocked by a player picture.
 *
 * <p>Everything is decoded to pixels and drawn with {@code GuiGraphics#fill}
 * rather than bound as a texture: a fill inherits the screen's pose, so a
 * Spatial GUI 3D panel carries the pointer with it and an exported frame keeps
 * it. Minecraft classes are reached reflectively so a wrong mapping name can
 * only cost a source, never break the screen.
 */
public final class CursorTexture {

    public static final String DEFAULT_TEXTURE = "replayui:textures/gui/recorded_cursor.png";

    private static final String BUILT_IN_RESOURCE =
            "/assets/replayui/textures/gui/recorded_cursor.png";

    public static final int MAX_SIZE = 128;
    public static final long TTL_NANOS = 2_000_000_000L;

    private static volatile File configDir =
            new File("config" + File.separator + "replayui");
    private static volatile DoubleSupplier guiScaleSupplier = CursorTexture::probeGuiScale;
    private static volatile boolean packOverride;

    private static final int TRANSPARENT = 0;
    private static final int OUTLINE = 0xFF000000;

    /** A decoded, optionally outlined picture. {@code argb} is row-major. */
    public static final class Sprite {
        public final int width;
        public final int height;
        public final int[] argb;
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

    /** Text settings, read fresh on every reload. Public for tests. */
    public static final class Settings {
        public String texture = ReplayUIConfig.cursorTexture;
        public boolean outline = ReplayUIConfig.cursorOutline;
        public int hotspotX = ReplayUIConfig.cursorHotspotX;
        public int hotspotY = ReplayUIConfig.cursorHotspotY;
        public double scale = ReplayUIConfig.cursorScale;
        public boolean followStyle = ReplayUIConfig.followSystemCursorStyle;
        public boolean followSize = ReplayUIConfig.followSystemCursorSize;
        public int maxHeight = ReplayUIConfig.cursorMaxHeight;
        public boolean hotspotXSet;
        public boolean hotspotYSet;
    }

    private static boolean loaded;
    private static long loadedAt;
    private static Sprite cached;
    private static String cachedSource = "built-in bitmap";
    private static String cachedTag = "bitmap";
    private static boolean styleFollowActive;
    private static boolean userSpritePresent;
    private static int effectiveMaxHeight;
    private static Settings settings = new Settings();

    private CursorTexture() {
    }

    /** Test / integration hooks; none of them change shipped behaviour. */
    public static void setConfigDir(File dir) {
        configDir = dir;
        invalidate();
    }

    public static void setGuiScaleSupplier(DoubleSupplier supplier) {
        guiScaleSupplier = supplier == null ? CursorTexture::probeGuiScale : supplier;
        invalidate();
    }

    public static void setPackOverride(boolean value) {
        packOverride = value;
        invalidate();
    }

    public static Sprite get() {
        ensureLoaded();
        return cached;
    }

    public static String source() {
        ensureLoaded();
        return cachedSource;
    }

    public static String sourceTag() {
        ensureLoaded();
        return cachedTag;
    }

    public static Settings config() {
        ensureLoaded();
        return settings;
    }

    /** True only when the option is enabled AND no player picture overrides it. */
    public static boolean styleFollowActive() {
        ensureLoaded();
        return styleFollowActive;
    }

    public static boolean userSpritePresent() {
        ensureLoaded();
        return userSpritePresent;
    }

    public static int effectiveMaxHeight() {
        ensureLoaded();
        return effectiveMaxHeight;
    }

    public static void invalidate() {
        loaded = false;
        cached = null;
        SystemCursorReader.invalidate();
    }

    /** Everything a resolved picture needs, shared by the live path and preview. */
    public static final class Resolved {
        public final Sprite sprite;
        public final String source;
        public final String tag;
        public final boolean styleFollowActive;
        public final boolean userSpritePresent;
        public final int effectiveMaxHeight;

        Resolved(Sprite sprite, String source, String tag, boolean styleFollowActive,
                 boolean userSpritePresent, int effectiveMaxHeight) {
            this.sprite = sprite;
            this.source = source;
            this.tag = tag;
            this.styleFollowActive = styleFollowActive;
            this.userSpritePresent = userSpritePresent;
            this.effectiveMaxHeight = effectiveMaxHeight;
        }
    }

    /**
     * The config screen's static preview. Source selection and sizing are the
     * exact same code path as {@link #get()}, with the four draft values
     * substituted, so what the panel shows is what Done will actually draw.
     *
     * <p>Pure query: it does not touch the live cache, the runtime switches or
     * the config file. Cancel therefore cannot change anything.
     */
    public static Resolved preview(double scale, int maxHeight,
                                   boolean followStyle, boolean followSize) {
        Settings s = copy(readSettings());
        s.scale = clamp(scale, 0.25, 4.0);
        s.maxHeight = (int) Math.round(clamp(maxHeight, 8, 128));
        s.followStyle = followStyle;
        s.followSize = followSize;
        return resolve(s);
    }

    /** No writes to static state: both get() and preview() call this. */
    private static Resolved resolve(Settings s) {
        boolean userSprite = playerSpritePresent(s);
        boolean styleActive = s.followStyle && !userSprite;
        Sprite sprite = null;
        String source = "built-in bitmap";
        String tag = "bitmap";
        File external = new File(configDir, "cursor.png");

        if (external.isFile()) {
            sprite = decode(openFile(external), s);
            if (sprite != null) {
                source = external.getAbsolutePath();
                tag = "player-file";
            }
        }
        if (sprite == null && styleActive) {
            SystemCursorReader.Metrics m = SystemCursorReader.read();
            if (m.hasSprite()) {
                sprite = new Sprite(m.width, m.height, m.argb.clone(),
                        m.hotspotX, m.hotspotY);
                source = "system cursor (" + m.source + ")";
                tag = "system";
            }
        }
        if (sprite == null) {
            sprite = loadFromPacks(s);
            if (sprite != null) {
                source = s.texture + " (resource packs)";
                tag = DEFAULT_TEXTURE.equals(s.texture) ? "pack" : "player-texture";
            }
        }
        if (sprite == null) {
            Settings jar = s;
            if (!s.hotspotXSet || !s.hotspotYSet) {
                jar = copy(s);
                jar.hotspotX = s.hotspotXSet ? s.hotspotX : 1;
                jar.hotspotY = s.hotspotYSet ? s.hotspotY : 1;
            }
            sprite = decode(builtInStream(), jar);
            if (sprite != null) {
                source = DEFAULT_TEXTURE + " (mod jar)";
                tag = "jar";
            }
        }
        int maxH = (int) Math.round(clamp(s.maxHeight, 8, 128));
        if (sprite != null) {
            Sized sized = applySize(sprite, s);
            sprite = sized.sprite;
            maxH = sized.maxHeight;
        }
        return new Resolved(sprite, source, tag, styleActive, userSprite, maxH);
    }

    private static synchronized void ensureLoaded() {
        long now = System.nanoTime();
        if (loaded && now - loadedAt < TTL_NANOS) {
            return;
        }
        settings = readSettings();
        detectPackOverride(settings);
        Resolved r = resolve(settings);

        cached = r.sprite;
        cachedSource = r.source;
        cachedTag = r.tag;
        styleFollowActive = r.styleFollowActive;
        userSpritePresent = r.userSpritePresent;
        effectiveMaxHeight = r.effectiveMaxHeight;
        loadedAt = now;
        loaded = true;

        if (isDebug()) {
            System.out.println("[replayui] cursor sprite: " + r.source
                    + (r.sprite == null ? " - falling back to built-in bitmap"
                            : " " + r.sprite.width + "x" + r.sprite.height)
                    + " (tag=" + r.tag + ", max=" + effectiveMaxHeight
                    + ", guiScale=" + String.format("%.1f", safeGuiScale()) + ")");
            if (!r.styleFollowActive && settings.followStyle) {
                System.out.println("[replayui] cursor style follow: inactive -"
                        + " overridden by player picture; option stays true"
                        + " (remove it to restore)");
            } else if (r.styleFollowActive) {
                System.out.println("[replayui] cursor style follow: active");
            }
        }
    }

    private static Settings copy(Settings s) {
        Settings n = new Settings();
        n.texture = s.texture;
        n.outline = s.outline;
        n.hotspotX = s.hotspotX;
        n.hotspotY = s.hotspotY;
        n.scale = s.scale;
        n.followStyle = s.followStyle;
        n.followSize = s.followSize;
        n.maxHeight = s.maxHeight;
        n.hotspotXSet = s.hotspotXSet;
        n.hotspotYSet = s.hotspotYSet;
        return n;
    }

    private static boolean playerSpritePresent(Settings s) {
        if (new File(configDir, "cursor.png").isFile()) {
            return true;
        }
        if (!DEFAULT_TEXTURE.equals(s.texture)) {
            return true;
        }
        return packOverride;
    }

    private static Settings readSettings() {
        Settings s = new Settings();
        Properties p = new Properties();
        File file = new File(configDir, "cursor.properties");
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
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
        s.scale = readDouble(p, "scale", s.scale, 0.25, 4.0);
        s.maxHeight = (int) Math.round(readDouble(p, "maxHeight", s.maxHeight, 8, 128));
        String fs = p.getProperty("followSystemCursorStyle");
        if (fs != null) {
            s.followStyle = Boolean.parseBoolean(fs.trim());
        }
        String fz = p.getProperty("followSystemCursorSize");
        if (fz != null) {
            s.followSize = Boolean.parseBoolean(fz.trim());
        }
        // Backwards-friendly alias: one switch for both, when present.
        String legacy = p.getProperty("useSystemCursor");
        if (legacy != null) {
            boolean both = Boolean.parseBoolean(legacy.trim());
            s.followStyle = both;
            s.followSize = both;
        }
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

    private static double readDouble(Properties p, String key, double fallback,
                                     double lo, double hi) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return d >= lo && d <= hi ? d : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double safeGuiScale() {
        try {
            double d = guiScaleSupplier.getAsDouble();
            return d >= 1.0 && d <= 8.0 ? d : 2.0;
        } catch (Throwable ignored) {
            return 2.0;
        }
    }

    private static double probeGuiScale() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object window = mcClass.getMethod("getWindow").invoke(mc);
            Object scale = window.getClass().getMethod("getGuiScale").invoke(window);
            return ((Number) scale).doubleValue();
        } catch (Throwable ignored) {
            return 2.0;
        }
    }

    /** Is the default path being supplied by a pack other than this mod's jar? */
    private static void detectPackOverride(Settings s) {
        if (!DEFAULT_TEXTURE.equals(s.texture)) {
            return;
        }
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object manager = mcClass.getMethod("getResourceManager").invoke(mc);
            Class<?> rl = Class.forName("net.minecraft.resources.ResourceLocation");
            Object location = rl.getMethod("parse", String.class).invoke(null, s.texture);
            Object optional = manager.getClass().getMethod("getResource", rl)
                    .invoke(manager, location);
            if (!(optional instanceof Optional)) {
                return;
            }
            Optional<?> res = (Optional<?>) optional;
            if (!res.isPresent()) {
                return;
            }
            Object resource = res.get();
            String packId = null;
            for (String name : new String[]{"sourcePackId", "packId"}) {
                try {
                    Object v = resource.getClass().getMethod(name).invoke(resource);
                    if (v != null) {
                        packId = v.toString();
                        break;
                    }
                } catch (Throwable ignored) {
                    // Different mapping name; try the next one.
                }
            }
            if (packId != null && !packId.toLowerCase().contains("replayui")) {
                packOverride = true;
            }
        } catch (Throwable ignored) {
            // No Minecraft in tests, or a changed mapping: keep the old flag.
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

    /** Reflective so 1.21.1 mapping differences can only lose one source. */
    private static Sprite loadFromPacks(Settings s) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            if (mc == null) {
                return null;
            }
            Object manager = mcClass.getMethod("getResourceManager").invoke(mc);
            if (manager == null) {
                return null;
            }
            Class<?> rl = Class.forName("net.minecraft.resources.ResourceLocation");
            Object location = rl.getMethod("parse", String.class).invoke(null, s.texture);
            InputStream in = (InputStream) manager.getClass().getMethod("open", rl)
                    .invoke(manager, location);
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

    /** Compressed-size cap, enforced before any image is decoded. */
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final long MAX_PIXELS = (long) MAX_SIZE * MAX_SIZE;

    private static byte[] readBounded(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0, n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > MAX_FILE_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** {width,height} from a PNG IHDR, or null when this is not a PNG. */
    private static int[] pngDimensions(byte[] b) {
        if (b.length < 24
                || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G'
                || (b[4] & 0xFF) != 0x0D || (b[5] & 0xFF) != 0x0A
                || (b[6] & 0xFF) != 0x1A || (b[7] & 0xFF) != 0x0A
                || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            return null;
        }
        return new int[]{readInt(b, 16), readInt(b, 20)};
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    /** Decode a PNG into a row-major ARGB array, then optionally add a border. */
    static Sprite decode(InputStream in, Settings s) {
        if (in == null) {
            return null;
        }
        try {
            // Bound the raw bytes AND inspect the IHDR before ImageIO allocates
            // a BufferedImage. A player dropping an 8000x8000 PNG must not cost
            // hundreds of MB just to be rejected afterwards.
            byte[] bytes = readBounded(in);
            if (bytes == null) {
                debug("cursor PNG rejected: file larger than " + MAX_FILE_BYTES
                        + " bytes");
                return null;
            }
            int[] dims = pngDimensions(bytes);
            if (dims != null && (dims[0] < 1 || dims[1] < 1
                    || dims[0] > MAX_SIZE || dims[1] > MAX_SIZE
                    || (long) dims[0] * dims[1] > MAX_PIXELS)) {
                debug("cursor PNG rejected before decode: " + dims[0] + "x"
                        + dims[1] + " (max " + MAX_SIZE + "x" + MAX_SIZE + ")");
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (w < 1 || h < 1 || w > MAX_SIZE || h > MAX_SIZE
                    || (long) w * h > MAX_PIXELS) {
                debug("cursor PNG rejected: " + w + "x" + h
                        + " (max " + MAX_SIZE + "x" + MAX_SIZE + ")");
                return null;
            }
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = image.getRGB(x, y);
                    pixels[y * w + x] = (argb >>> 24) < 16 ? TRANSPARENT : argb;
                }
            }
            return s.outline
                    ? outline(pixels, w, h, s)
                    : new Sprite(w, h, pixels, s.hotspotX, s.hotspotY);
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

    /**
     * One place for all sizing. Desktop pixels become GUI pixels by dividing
     * by guiScale, so a 32px desktop pointer at guiScale 2 is 16 GUI pixels -
     * exactly the shipped arrow, not twice its size. A large system cursor
     * raises the height ceiling instead of being clamped away.
     */
    /** Sizing result; the ceiling is returned rather than written to a field. */
    private static final class Sized {
        final Sprite sprite;
        final int maxHeight;

        Sized(Sprite sprite, int maxHeight) {
            this.sprite = sprite;
            this.maxHeight = maxHeight;
        }
    }

    private static Sized applySize(Sprite base, Settings s) {
        double guiScale = safeGuiScale();
        double scale = clamp(s.scale, 0.25, 4.0);
        double maxH = clamp(s.maxHeight, 8, 128);
        if (s.followSize) {
            SystemCursorReader.Metrics m = SystemCursorReader.read();
            if (m.ok) {
                double target = m.cursorPx / guiScale;
                double ratio = clamp(target / base.height, 0.25, 4.0);
                scale = clamp(scale * ratio, 0.25, 4.0);
                maxH = Math.max(maxH, Math.min(128, Math.round(target)));
            }
        }
        Sprite out = scale(base, scale);
        if (out.height > maxH) {
            out = scale(out, maxH / out.height);
        }
        return new Sized(out, (int) Math.round(maxH));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Nearest-neighbour rescale; hard edges and a 1px outline stay crisp. */
    public static Sprite scale(Sprite src, double factor) {
        if (factor == 1.0) {
            return src;
        }
        int nw = Math.max(1, (int) Math.round(src.width * factor));
        int nh = Math.max(1, (int) Math.round(src.height * factor));
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(src.height - 1, (int) (y * src.height / (double) nh));
            for (int x = 0; x < nw; x++) {
                int sx = Math.min(src.width - 1, (int) (x * src.width / (double) nw));
                out[y * nw + x] = src.argb[sy * src.width + sx];
            }
        }
        return new Sprite(nw, nh, out,
                (int) Math.round(src.offsetX * factor),
                (int) Math.round(src.offsetY * factor));
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
