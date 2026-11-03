package pandastic.studio.core.bootstrap.internal;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Plugin-scoped logger for the bootstrap, so election messages appear under the plugin they
 * concern instead of under a shared library name.
 * <p>
 * The prefix comes from {@link PandasticLibrary#LOG_PREFIX}: with one copy of this library per plugin,
 * a stray literal here would make the same library announce itself under two different names in
 * one log file.
 *
 * @see PandasticCoreLog for the static sites that have no plugin to log through
 */
final class PandasticBootstrapLogger {

    private final boolean enabled;
    private final Plugin plugin;

    PandasticBootstrapLogger(boolean enabled, @NotNull Plugin plugin) {
        this.enabled = enabled;
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void info(@NotNull String message) {
        if (!enabled) return;
        plugin.getLogger().info(PandasticLibrary.LOG_PREFIX + message);
    }

    void warn(@NotNull String message) {
        if (!enabled) return;
        plugin.getLogger().warning(PandasticLibrary.LOG_PREFIX + message);
    }

    void warn(@NotNull String message, @NotNull Throwable cause) {
        if (!enabled) return;
        plugin.getLogger().log(Level.WARNING, PandasticLibrary.LOG_PREFIX + message, cause);
    }
}
