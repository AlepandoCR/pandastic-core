package pandastic.studio.core.service.api.config;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Contract implemented by every configuration class the annotation processor generates
 * from a {@link Configurable} declaration.
 * <p>
 * An implementation is always bound to a single file, so {@link #init(File)} has to run
 * before any other method: it is what hands the implementation the file to work on.
 * <p>
 * Typical start-up, using the factory the processor emits next to this contract:
 * <pre>{@code
 * DatabaseConfig config = DatabaseConfig.create(
 *         new File(plugin.getDataFolder(), DatabaseConfig.DEFAULT_FILENAME));
 * config.loadOrCreate();
 * }</pre>
 * The asynchronous variants move the very same work off the calling thread. They are
 * therefore safe to call from the main thread, but the values they publish must be handed
 * back to the main thread by the caller before they drive any game logic.
 *
 * @see Configurable
 * @see ConfigValue
 */
public interface GeneratedConfig {

    /**
     * Binds this configuration to the file it reads from and writes to.
     * <p>
     * No I/O happens here, so the file does not have to exist yet.
     *
     * @param file the file backing this configuration
     */
    void init(@NotNull File file);

    /**
     * Reads every declared value from the backing file, keeping the declared default for
     * each key the file does not contain. Does nothing when the file is missing.
     */
    void load();

    /**
     * Writes every declared value, together with its comment, to the backing file,
     * creating or overwriting it.
     */
    void save();

    /**
     * Loads the backing file when it exists, otherwise writes the current values to it.
     * <p>
     * This is the method to call on start-up: it honors an existing file and ships a
     * fully commented one on a fresh installation.
     */
    void loadOrCreate();

    /**
     * Asynchronous counterpart of {@link #load()}.
     *
     * @return a future completing once the values have been read
     */
    @NotNull CompletableFuture<Void> loadAsync();

    /**
     * Asynchronous counterpart of {@link #save()}.
     *
     * @return a future completing once the file has been written
     */
    @NotNull CompletableFuture<Void> saveAsync();

    /**
     * Asynchronous counterpart of {@link #loadOrCreate()}.
     *
     * @return a future completing once the file has been read or created
     */
    @NotNull CompletableFuture<Void> loadOrCreateAsync();
}
