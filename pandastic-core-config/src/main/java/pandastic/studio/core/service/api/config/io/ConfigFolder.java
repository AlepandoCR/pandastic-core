package pandastic.studio.core.service.api.config.io;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Names the directory a configuration file lives in, relative to a plugin's data folder.
 * <p>
 * A {@code ConfigFolder} is a pure location: resolving one never touches the file system and
 * never creates directories. Creation is deliberately deferred to whoever writes the file -
 * {@link ConfigFactory} ensures the parent directory exists right before it loads or creates
 * a configuration - so that merely describing a layout leaves the disk untouched.
 * <p>
 * The two factories cover the common layouts:
 * <pre>{@code
 * ConfigFolder.root()                  // <dataFolder>
 * ConfigFolder.of("storage")          // <dataFolder>/storage
 * ConfigFolder.of("storage", "sql")   // <dataFolder>/storage/sql
 * }</pre>
 * The interface is deliberately plugin-agnostic: the primitive operation resolves against a
 * plain base directory, and {@link #resolve(Plugin)} is a convenience that feeds it
 * {@link Plugin#getDataFolder()}. Custom layouts only need a lambda.
 *
 * @see ConfigFactory
 * @see ConfigLoader
 */
@FunctionalInterface
public interface ConfigFolder {

    /**
     * Resolves this folder against a base directory.
     * <p>
     * This is a pure path computation: the returned directory is not created and does not
     * have to exist yet.
     *
     * @param dataFolder the base directory, typically a plugin's data folder
     * @return the directory configuration files of this folder belong in
     */
    @NotNull File resolve(@NotNull File dataFolder);

    /**
     * Resolves this folder against {@code plugin}'s data folder.
     * <p>
     * Equivalent to {@code resolve(plugin.getDataFolder())}; like the primitive overload it
     * creates nothing on disk.
     *
     * @param plugin the plugin whose data folder anchors the resolution
     * @return the directory configuration files of this folder belong in
     */
    default @NotNull File resolve(@NotNull Plugin plugin) {
        return resolve(plugin.getDataFolder());
    }

    /**
     * The data folder itself, with no subdirectory.
     *
     * @return a folder resolving to the base directory it is given
     */
    static @NotNull ConfigFolder root() {
        return dataFolder -> dataFolder;
    }

    /**
     * A chain of subdirectories below the data folder.
     * <p>
     * {@code of("storage", "sql")} resolves to {@code <dataFolder>/storage/sql}. Calling it
     * with no arguments is equivalent to {@link #root()}.
     *
     * @param path the subdirectory names, applied in order; each must be non-blank
     * @return a folder resolving to the nested subdirectory
     * @throws IllegalArgumentException if any path segment is {@code null} or blank
     */
    static @NotNull ConfigFolder of(String @NotNull ... path) {
        String[] segments = path.clone();
        for (int i = 0; i < segments.length; i++) {
            if (segments[i] == null || segments[i].isBlank()) {
                throw new IllegalArgumentException("Path segment " + i + " must not be "
                        + (segments[i] == null ? "null" : "blank"));
            }
        }
        return dataFolder -> {
            File directory = dataFolder;
            for (String segment : segments) {
                directory = new File(directory, segment);
            }
            return directory;
        };
    }
}
