package com.replayui.render;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the desktop pointer, without adding a native library to the mod.
 *
 * <p>Windows keeps the pointer scheme in the registry:
 * {@code HKCU\Control Panel\Cursors} holds {@code Arrow} (a .cur/.ani path,
 * sometimes with {@code %SystemRoot%} in it) and {@code CursorBaseSize}
 * (DWORD, 32 by default, 32..256). Both are read through {@code reg query},
 * which needs no JNA and no extra dependency. Every failure is swallowed:
 * a non-Windows desktop, a missing value, a damaged cursor file and a
 * headless AWT all degrade to "no system info", never to a crash.
 *
 * <p>The result is cached for the process - probing starts an external
 * process - and {@link #setProbe} exists so the decision logic can be tested
 * with a synthetic desktop without a Windows machine.
 */
public final class SystemCursorReader {

    /** Decoded desktop pointer picture. All fields are independent. */
    public static final class Metrics {
        public final boolean ok;
        public final int cursorPx;
        public final int[] argb;
        public final int width;
        public final int height;
        public final int hotspotX;
        public final int hotspotY;
        public final String source;

        public Metrics(boolean ok, int cursorPx, int[] argb, int width, int height,
                       int hotspotX, int hotspotY, String source) {
            this.ok = ok;
            this.cursorPx = cursorPx;
            this.argb = argb;
            this.width = width;
            this.height = height;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
            this.source = source;
        }

        public boolean hasSprite() {
            return argb != null && width > 0 && height > 0;
        }
    }

    /** A raw icon decoded from a .cur/.ani payload. */
    public static final class Icon {
        public final int width;
        public final int height;
        public final int[] argb;
        public final int hotspotX;
        public final int hotspotY;

        Icon(int width, int height, int[] argb, int hotspotX, int hotspotY) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
        }
    }

    public interface Probe {
        Metrics read();
    }

    private static volatile Probe probe = new WindowsProbe();
    private static volatile Metrics cached;

    private SystemCursorReader() {
    }

    public static void setProbe(Probe p) {
        probe = p == null ? new WindowsProbe() : p;
        invalidate();
    }

    public static void invalidate() {
        cached = null;
    }

    public static Metrics read() {
        Metrics m = cached;
        if (m == null) {
            try {
                m = probe.read();
            } catch (Throwable ignored) {
                m = MetricsHolder.NONE;
            }
            if (m == null) {
                m = MetricsHolder.NONE;
            }
            cached = m;
        }
        return m;
    }

    private static final class MetricsHolder {
        static final Metrics NONE = new Metrics(false, 32, null, 0, 0, 0, 0, "unavailable");
    }

    /** Desktop pointer size from AWT, used when the registry gives nothing. */
    public static int desktopCursorPixels() {
        try {
            Dimension d = Toolkit.getDefaultToolkit().getBestCursorSize(32, 32);
            if (d != null && d.width >= 16 && d.width <= 256) {
                return d.width;
            }
        } catch (Throwable ignored) {
            // Headless or locked down: the documented 32px default is fine.
        }
        return 32;
    }

    /** Expand %VAR% tokens the way the Windows cursor scheme stores them. */
    public static String expandEnv(String path) {
        if (path == null) {
            return null;
        }
        String out = path.trim();
        Matcher m = Pattern.compile("%([^%]+)%").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = System.getenv(key);
            if (value == null && key.equalsIgnoreCase("SystemRoot")) {
                value = System.getenv("WINDIR");
            }
            if (value == null && (key.equalsIgnoreCase("SystemRoot")
                    || key.equalsIgnoreCase("WINDIR"))) {
                value = "C:\\Windows";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? m.group(0) : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Pull the REG_SZ / REG_DWORD value out of one {@code reg query} output. */
    public static String regValue(String output, String name) {
        if (output == null) {
            return null;
        }
        Pattern p = Pattern.compile("(?m)^\\s*" + Pattern.quote(name)
                + "\\s+REG_(?:SZ|EXPAND_SZ|DWORD)\\s+(.+?)\\s*$");
        Matcher m = p.matcher(output);
        return m.find() ? m.group(1).trim() : null;
    }

    public static int regDword(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            String s = raw.trim();
            int v = s.toLowerCase(Locale.ROOT).startsWith("0x")
                    ? Integer.parseUnsignedInt(s.substring(2), 16)
                    : Integer.parseInt(s);
            return v >= 16 && v <= 256 ? v : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /**
     * Decode a PNG, a .cur, or the first frame of an .ani into ARGB pixels.
     * Returns {@code null} for anything it cannot handle; callers fall back.
     */
    public static Icon parseCursor(byte[] data) {
        return parseCursor(data, false);
    }

    private static Icon parseCursor(byte[] data, boolean insideAni) {
        if (data == null || data.length < 6) {
            return null;
        }
        if (!insideAni && ascii(data, 0, 4).equals("RIFF")
                && ascii(data, 8, 4).equals("ACON")) {
            byte[] frame = firstIconChunk(data, 12, data.length);
            return frame == null ? null : parseCursor(frame, true);
        }
        if ((data[0] & 0xff) == 0x89 && ascii(data, 1, 3).equals("PNG")) {
            return fromPng(data, 0, 0);
        }
        return fromCur(data);
    }

    private static byte[] firstIconChunk(byte[] data, int start, int end) {
        int p = start;
        while (p + 8 <= end) {
            String name = ascii(data, p, 4);
            long size = le32(data, p + 4);
            int body = p + 8;
            if (size < 0 || size > end - body) {
                return null;
            }
            if (name.equals("icon")) {
                return Arrays.copyOfRange(data, body, body + (int) size);
            }
            if (name.equals("LIST") && size >= 4) {
                byte[] nested = firstIconChunk(data, body + 4, body + (int) size);
                if (nested != null) {
                    return nested;
                }
            }
            p = body + (int) size + ((int) size & 1);
        }
        return null;
    }

    private static Icon fromPng(byte[] data, int hotspotX, int hotspotY) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null || image.getWidth() > 256 || image.getHeight() > 256) {
                return null;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    argb[y * w + x] = image.getRGB(x, y);
                }
            }
            return new Icon(w, h, argb, hotspotX, hotspotY);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** ICONDIR (type 2) + one ICONDIRENTRY + a DIB or embedded PNG. */
    private static Icon fromCur(byte[] d) {
        if (d.length < 22 || le16(d, 0) != 0 || le16(d, 2) != 2 || le16(d, 4) < 1) {
            return null;
        }
        int hotspotX = le16(d, 10);
        int hotspotY = le16(d, 12);
        long bytes = le32(d, 14);
        long offset = le32(d, 18);
        if (offset < 0 || bytes < 1 || offset + bytes > d.length) {
            return null;
        }
        byte[] image = Arrays.copyOfRange(d, (int) offset, (int) (offset + bytes));
        if (image.length >= 4 && (image[0] & 0xff) == 0x89
                && ascii(image, 1, 3).equals("PNG")) {
            return fromPng(image, hotspotX, hotspotY);
        }
        return fromDib(image, hotspotX, hotspotY);
    }

    /**
     * BITMAPINFOHEADER + XOR bitmap + 1bpp AND mask, bottom-up rows.
     * 32bpp honours its alpha byte; when that byte is zero everywhere (older
     * cursors) the AND mask decides transparency instead. 24/8/1bpp use the
     * colour table and the AND mask.
     */
    private static Icon fromDib(byte[] d, int hotspotX, int hotspotY) {
        if (d.length < 40) {
            return null;
        }
        int header = (int) le32(d, 0);
        if (header < 40) {
            return null;
        }
        int w = (int) le32(d, 4);
        int hFull = (int) le32(d, 8);
        int bpp = le16(d, 14);
        long compression = le32(d, 16);
        if (w < 1 || w > 256 || hFull == 0 || compression != 0) {
            return null;
        }
        int h = Math.abs(hFull) / 2;
        if (h < 1 || h > 256 || (bpp != 32 && bpp != 24 && bpp != 8 && bpp != 1)) {
            return null;
        }
        int stride = ((w * bpp + 31) / 32) * 4;
        int colors = bpp <= 8 ? (1 << bpp) : 0;
        int xor = header + colors * 4;
        int andStride = ((w + 31) / 32) * 4;
        int and = xor + stride * h;
        if (and + andStride * h > d.length) {
            return null;
        }
        int[] palette = new int[Math.max(colors, 2)];
        for (int i = 0; i < colors && i < palette.length; i++) {
            int p = header + i * 4;
            int b = d[p] & 0xff;
            int g = d[p + 1] & 0xff;
            int r = d[p + 2] & 0xff;
            palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (colors == 0) {
            palette[0] = 0xFF000000;
            palette[1] = 0xFFFFFFFF;
        }
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            int srcRow = xor + (h - 1 - y) * stride;
            int maskRow = and + (h - 1 - y) * andStride;
            for (int x = 0; x < w; x++) {
                int color;
                int alpha = 255;
                if (bpp == 32) {
                    int p = srcRow + x * 4;
                    int b = d[p] & 0xff;
                    int g = d[p + 1] & 0xff;
                    int r = d[p + 2] & 0xff;
                    int a = d[p + 3] & 0xff;
                    color = 0xFF000000 | (r << 16) | (g << 8) | b;
                    if (a == 0) {
                        alpha = maskBit(d, maskRow, x) ? 0 : 255;
                    } else {
                        alpha = a;
                    }
                } else if (bpp == 24) {
                    int p = srcRow + x * 3;
                    int b = d[p] & 0xff;
                    int g = d[p + 1] & 0xff;
                    int r = d[p + 2] & 0xff;
                    color = 0xFF000000 | (r << 16) | (g << 8) | b;
                    alpha = maskBit(d, maskRow, x) ? 0 : 255;
                } else {
                    int index = bpp == 8
                            ? (d[srcRow + x] & 0xff)
                            : ((d[srcRow + (x >> 3)] >> (7 - (x & 7))) & 1);
                    color = palette[Math.min(index, palette.length - 1)];
                    alpha = maskBit(d, maskRow, x) ? 0 : 255;
                }
                argb[y * w + x] = alpha == 0 ? 0 : ((alpha << 24) | (color & 0x00FFFFFF));
            }
        }
        return new Icon(w, h, argb, hotspotX, hotspotY);
    }

    private static boolean maskBit(byte[] d, int row, int x) {
        if (row + (x >> 3) >= d.length) {
            return false;
        }
        return ((d[row + (x >> 3)] >> (7 - (x & 7))) & 1) != 0;
    }

    private static String ascii(byte[] d, int off, int len) {
        if (off < 0 || off + len > d.length) {
            return "";
        }
        return new String(d, off, len, StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] d, int p) {
        return (d[p] & 0xff) | ((d[p + 1] & 0xff) << 8);
    }

    private static long le32(byte[] d, int p) {
        return (d[p] & 0xffL) | ((d[p + 1] & 0xffL) << 8)
                | ((d[p + 2] & 0xffL) << 16) | ((d[p + 3] & 0xffL) << 24);
    }

    /** The real desktop probe. Only runs on Windows; everything else is a no-op. */
    static final class WindowsProbe implements Probe {
        @Override
        public Metrics read() {
            int pixels = desktopCursorPixels();
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("win")) {
                return new Metrics(false, pixels, null, 0, 0, 0, 0, "non-Windows desktop");
            }
            String arrow = regValue(query("Arrow"), "Arrow");
            String size = regValue(query("CursorBaseSize"), "CursorBaseSize");
            if (size != null) {
                pixels = regDword(size, pixels);
            }
            if (arrow == null) {
                return new Metrics(false, pixels, null, 0, 0, 0, 0, "registry has no Arrow");
            }
            File file = new File(expandEnv(arrow));
            if (!file.isFile()) {
                return new Metrics(false, pixels, null, 0, 0, 0, 0, "cursor file missing");
            }
            byte[] data;
            try {
                data = Files.readAllBytes(file.toPath());
            } catch (IOException | RuntimeException ignored) {
                return new Metrics(false, pixels, null, 0, 0, 0, 0, "cursor file unreadable");
            }
            Icon icon = parseCursor(data);
            if (icon == null) {
                return new Metrics(true, pixels, null, 0, 0, 0, 0, "unsupported cursor format");
            }
            return new Metrics(true, pixels, icon.argb, icon.width, icon.height,
                    icon.hotspotX, icon.hotspotY, file.getAbsolutePath());
        }

        /** A hung `reg query` must never stall the client render thread. */
        static final long QUERY_TIMEOUT_MS = 400L;
        /** reg query prints a handful of lines; anything larger is bogus. */
        static final int MAX_QUERY_BYTES = 256 * 1024;

        private String query(String value) {
            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("reg", "query",
                        "HKCU\\Control Panel\\Cursors", "/v", value);
                pb.redirectErrorStream(true);
                process = pb.start();
                // Wait FIRST with a deadline. Reading the stream to EOF before
                // waiting would block forever on a process that never exits -
                // exactly the freeze this guard exists to prevent. reg prints
                // far less than a pipe buffer, so waiting first cannot deadlock.
                if (!await(process, QUERY_TIMEOUT_MS)) {
                    return null;
                }
                byte[] out = readAll(process.getInputStream(), MAX_QUERY_BYTES);
                return new String(out, StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                return null;
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        /** Package-visible so it can be exercised against a real process. */
        static boolean await(Process process, long timeoutMs) {
            try {
                if (process.waitFor(timeoutMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
                // fall through to the kill below
            }
            process.destroyForcibly();
            return false;
        }

        static byte[] readAll(java.io.InputStream in, int limit) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n, total = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > limit) {
                    throw new IOException("reg query output exceeds " + limit + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
