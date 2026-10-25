package com.replayui.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single logging entry point.
 *
 * <p>Everything noisy goes through {@link #verbose} which is off unless the
 * system property {@code replayui.debug} is set, so a normal session stays
 * quiet but a broken one can be inspected without a rebuild.
 */
public final class DebugLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("replayui");

    /**
     * Property present but explicitly "false" must mean off, so a plain
     * {@code != null} check is wrong: -Dreplayui.debug=false would enable it.
     * Anything else present (true, 1, no value at all) means on.
     */
    private static final boolean VERBOSE = readVerbose();

    private static boolean readVerbose() {
        String value = System.getProperty("replayui.debug");
        return value != null && !"false".equalsIgnoreCase(value.trim());
    }

    private DebugLog() {
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warn(String message) {
        LOGGER.warn(message);
    }

    public static void warn(String message, Throwable throwable) {
        LOGGER.warn(message, throwable);
    }

    public static void verbose(String message) {
        if (VERBOSE) {
            LOGGER.info("[verbose] " + message);
        }
    }

    /**
     * Same as {@link #verbose(String)} but keeps the stack trace available for
     * the one place that wants it: a failure that is only interesting while
     * debugging, so it must not turn into a warning in a normal session.
     */
    public static void verbose(String message, Throwable throwable) {
        if (VERBOSE) {
            LOGGER.info("[verbose] " + message, throwable);
        }
    }

    public static boolean isVerbose() {
        return VERBOSE;
    }
}
