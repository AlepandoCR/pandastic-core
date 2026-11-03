package pandastic.studio.core.bootstrap.internal;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Logging sink for code that runs without a {@code Plugin} in hand.
 * <p>
 * Most of the bridge is static and is reached from both the leader and the follower side, so it
 * has no plugin logger to borrow. Using {@link System#getLogger(String)} keeps those call sites
 * loggable without inventing a dependency on Bukkit, and keeps them testable outside a server.
 * <p>
 * This exists so that security-relevant failures in the bridge (a rejected leader, a proxy that
 * could not be unwrapped, a filter rejection) are never swallowed. Code with access to a plugin
 * should prefer {@link PandasticBootstrapLogger}, whose output lands in the owning plugin's log.
 */
public final class PandasticCoreLog {

    private static final Logger LOGGER = System.getLogger(PandasticLibrary.DISPLAY_NAME);

    private PandasticCoreLog() {
    }

    /**
     * Logs a routine, operator-relevant event — leadership changes, failover progress. Unlike
     * {@link #warn}, nothing is wrong; the line exists because leadership transitions must be
     * observable per the project's security notes.
     *
     * @param message the message to log
     */
    public static void info(@NotNull String message) {
        LOGGER.log(Level.INFO, message);
    }

    /**
     * Logs a message that a server administrator should see.
     */
    public static void warn(@NotNull String message) {
        LOGGER.log(Level.WARNING, PandasticLibrary.LOG_PREFIX + message);
    }

    /**
     * Logs a message and the failure that caused it.
     */
    public static void warn(@NotNull String message, @NotNull Throwable cause) {
        LOGGER.log(Level.WARNING, PandasticLibrary.LOG_PREFIX + message, cause);
    }

    /**
     * Logs a benign, expected-in-some-configurations event. Off by default on most servers, which
     * is exactly why nothing security-relevant may be logged at this level.
     */
    public static void debug(@NotNull String message) {
        LOGGER.log(Level.DEBUG, PandasticLibrary.LOG_PREFIX + message);
    }

    /**
     * Logs a benign, expected-in-some-configurations event together with its cause.
     */
    public static void debug(@NotNull String message, @NotNull Throwable cause) {
        LOGGER.log(Level.DEBUG, PandasticLibrary.LOG_PREFIX + message, cause);
    }
}
