package com.replayui.compat;

/**
 * Smooths the recorded cursor between the once-per-tick samples.
 *
 * <p>The recording only writes a sample when something visible changed, so a
 * moving cursor gives roughly one point every 50 ms. Drawing those points
 * verbatim makes the held stack jump. This keeps the previous point and eases
 * toward the newest one over the same 50 ms window, which makes the motion
 * continuous without inventing any data.
 *
 * <p>Wall-clock based on purpose: it also advances during Flashback's video
 * export, where there is no client tick per rendered frame and a tick counter
 * would stay frozen. The whole class is pure arithmetic so it can be tested
 * without Minecraft.
 */
public final class CursorInterpolator {

    /** One client tick, in nanoseconds. */
    public static final long WINDOW_NANOS = 50_000_000L;

    private static boolean initialised;
    private static double fromX, fromY, toX, toY;
    private static long start;

    private CursorInterpolator() {
    }

    public static void reset() {
        initialised = false;
        fromX = fromY = toX = toY = 0.0;
        start = 0L;
    }

    /**
     * Feeds a new sample. The current interpolated position becomes the new
     * starting point, so a burst of samples never snaps backwards.
     *
     * @param smooth false pins the cursor straight to the sample (the old
     *               behaviour, kept as a switch)
     */
    public static void update(double x, double y, long now, boolean smooth) {
        if (!initialised) {
            fromX = toX = x;
            fromY = toY = y;
            start = now;
            initialised = true;
            return;
        }
        if (x == toX && y == toY) {
            return;
        }
        double currentX = value(fromX, toX, now);
        double currentY = value(fromY, toY, now);
        fromX = currentX;
        fromY = currentY;
        toX = x;
        toY = y;
        start = now;
        if (!smooth) {
            fromX = toX;
            fromY = toY;
        }
    }

    public static double x(long now) {
        return value(fromX, toX, now);
    }

    public static double y(long now) {
        return value(fromY, toY, now);
    }

    private static double value(double a, double b, long now) {
        if (!initialised) {
            return 0.0;
        }
        long elapsed = now - start;
        if (elapsed <= 0L) {
            return a;
        }
        if (elapsed >= WINDOW_NANOS) {
            return b;
        }
        double t = (double) elapsed / (double) WINDOW_NANOS;
        // smoothstep: zero velocity at both ends, so consecutive samples join
        // without a visible corner.
        t = t * t * (3.0 - 2.0 * t);
        return a + (b - a) * t;
    }
}
