package pandastic.studio.core.service.api.config.io;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import pandastic.studio.core.service.api.config.GeneratedConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Chainable batch loader for {@link GeneratedConfig} files.
 * <p>
 * The {@code load} methods only queue work; nothing touches the disk until {@link #loadAll()}
 * launches every queued task through {@link ConfigFactory#loadOrCreateAsync(File, Class)}:
 * <pre>{@code
 * new ConfigLoader(plugin)
 *         .load(ConfigFolder.root(), "DatabaseConfig.yml", DatabaseConfig.class,
 *                 config -> this.database = config, "database settings")
 *         .loadAndTransform(ConfigFolder.of("storage"), "CacheConfig.yml", CacheConfig.class,
 *                 CacheSettings::fromConfig, settings -> this.cache = settings)
 *         .loadAll();
 * }</pre>
 * Each task succeeds or fails on its own: a missing directory, an unreadable file or a
 * throwing callback is logged with its full stack trace and swallowed, so one broken
 * configuration never prevents the others from loading, and the future returned by
 * {@link #loadAll()} always completes normally once every task has finished either way.
 * <p>
 * <strong>Threading.</strong> The {@code onLoad} callbacks - and the transformers of
 * {@code loadAndTransform} - run on the asynchronous pool behind
 * {@link GeneratedConfig#loadOrCreateAsync()}, which for generated classes is the common
 * {@link java.util.concurrent.ForkJoinPool}. They never run on the server main thread, so
 * they must not touch the Bukkit API: storing the loaded values and handing them to the main
 * thread via the scheduler is the caller's job. The same applies to anything chained onto the
 * future returned by {@link #loadAll()}.
 * <p>
 * This class is not thread-safe; queue the tasks and call {@link #loadAll()} from one thread.
 *
 * @see ConfigFactory
 * @see ConfigFolder
 */
public final class ConfigLoader {

    /** Base directory every {@link ConfigFolder} of this loader resolves against. */
    private final File dataFolder;

    /** Sink of the per-task debug, error and completion lines. */
    private final Logger logger;

    /** Tasks queued by the {@code load} methods, launched and drained by {@link #loadAll()}. */
    private final List<LoadTask<?>> tasks = new ArrayList<>();

    /**
     * Creates a loader resolving folders against {@code plugin}'s data folder and logging
     * through {@link Plugin#getSLF4JLogger() its SLF4J logger}.
     *
     * @param plugin the plugin owning the configuration files
     */
    public ConfigLoader(@NotNull Plugin plugin) {
        this(plugin.getDataFolder(), plugin.getSLF4JLogger());
    }

    /**
     * Test seam: a loader bound to an arbitrary base directory and logger, so the batch
     * behavior can be exercised without standing up a {@link Plugin} implementation.
     * <p>
     * Package-private on purpose - production code goes through
     * {@link #ConfigLoader(Plugin)}, which derives both values from the plugin.
     *
     * @param dataFolder the directory standing in for the plugin data folder
     * @param logger     the logger receiving the per-task and completion lines
     */
    ConfigLoader(@NotNull File dataFolder, @NotNull Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    /**
     * Queues a configuration whose log display name is its file name.
     * <p>
     * See the class Javadoc for the threading contract of {@code onLoad}: it runs on the
     * asynchronous load pool, never on the server main thread.
     *
     * @param folder   the directory the file lives in, resolved against the data folder
     * @param fileName the file name, including its extension
     * @param type     the generated configuration class, which must expose a public no-arg constructor
     * @param onLoad   callback receiving the loaded configuration; skipped when loading fails
     * @param <GeneratedConfigType>      the generated configuration type
     * @return this loader, for chaining
     */
    public <GeneratedConfigType extends GeneratedConfig> @NotNull ConfigLoader load(
            @NotNull ConfigFolder folder,
            @NotNull String fileName,
            @NotNull Class<GeneratedConfigType> type,
            @NotNull Consumer<? super GeneratedConfigType> onLoad
    ) {
        return load(folder, fileName, type, onLoad, fileName);
    }

    /**
     * Queues a configuration under an explicit log display name.
     * <p>
     * See the class Javadoc for the threading contract of {@code onLoad}: it runs on the
     * asynchronous load pool, never on the server main thread.
     *
     * @param folder     the directory the file lives in, resolved against the data folder
     * @param fileName   the file name, including its extension
     * @param type       the generated configuration class, which must expose a public no-arg constructor
     * @param onLoad     callback receiving the loaded configuration; skipped when loading fails
     * @param configName the name the log lines refer to this configuration by
     * @param <GeneratedConfigType>        the generated configuration type
     * @return this loader, for chaining
     */
    public <GeneratedConfigType extends GeneratedConfig> @NotNull ConfigLoader load(
            @NotNull ConfigFolder folder,
            @NotNull String fileName,
            @NotNull Class<GeneratedConfigType> type,
            @NotNull Consumer<? super GeneratedConfigType> onLoad,
            @NotNull String configName
    ) {
        tasks.add(new LoadTask<>(folder, fileName, type, onLoad, configName));
        return this;
    }

    /**
     * Queues a configuration that is transformed before being handed to the callback, under
     * its file name as log display name.
     * <p>
     * Useful when the rest of the plugin should see a derived value - an immutable snapshot,
     * a connection pool - rather than the configuration object itself. Both the transformer
     * and {@code onLoad} run on the asynchronous load pool; see the class Javadoc.
     *
     * @param folder      the directory the file lives in, resolved against the data folder
     * @param fileName    the file name, including its extension
     * @param type        the generated configuration class, which must expose a public no-arg constructor
     * @param transformer maps the loaded configuration to the value the callback receives
     * @param onLoad      callback receiving the transformed value; skipped when loading fails
     * @param <GeneratedConfigType>         the generated configuration type
     * @param <TransformedType>         the transformed value type
     * @return this loader, for chaining
     */
    public <GeneratedConfigType extends GeneratedConfig, TransformedType> @NotNull ConfigLoader loadAndTransform(
            @NotNull ConfigFolder folder,
            @NotNull String fileName,
            @NotNull Class<GeneratedConfigType> type,
            @NotNull Function<? super GeneratedConfigType, ? extends TransformedType> transformer,
            @NotNull Consumer<? super TransformedType> onLoad) {
        return loadAndTransform(folder, fileName, type, transformer, onLoad, fileName);
    }

    /**
     * Queues a transformed configuration under an explicit log display name.
     * <p>
     * Both the transformer and {@code onLoad} run on the asynchronous load pool; see the
     * class Javadoc for the threading contract.
     *
     * @param folder      the directory the file lives in, resolved against the data folder
     * @param fileName    the file name, including its extension
     * @param type        the generated configuration class, which must expose a public no-arg constructor
     * @param transformer maps the loaded configuration to the value the callback receives
     * @param onLoad      callback receiving the transformed value; skipped when loading fails
     * @param configName  the name the log lines refer to this configuration by
     * @param <GeneratedConfigType>         the generated configuration type
     * @param <TransformedType>         the transformed value type
     * @return this loader, for chaining
     */
    public <GeneratedConfigType extends GeneratedConfig, TransformedType> @NotNull ConfigLoader loadAndTransform(
            @NotNull ConfigFolder folder,
            @NotNull String fileName,
            @NotNull Class<GeneratedConfigType> type,
            @NotNull Function<? super GeneratedConfigType, ? extends TransformedType> transformer,
            @NotNull Consumer<? super TransformedType> onLoad,
            @NotNull String configName) {
        return load(folder, fileName, type, config -> onLoad.accept(transformer.apply(config)), configName);
    }

    /**
     * Launches every queued task and drains the queue.
     * <p>
     * Each task loads (or creates) its file through
     * {@link ConfigFactory#loadOrCreateAsync(File, Class)} and runs its callback on success,
     * logging the display name at debug level. A failure - unreadable file, broken YAML,
     * missing constructor or a throwing callback - is logged at error level with the full
     * exception and contained to its own task, so the returned future completes normally once
     * every task has finished, successfully or not. A completion line is logged at that point.
     * <p>
     * The returned future completes on the asynchronous load pool; see the class Javadoc
     * before chaining main-thread work onto it.
     *
     * @return a future completing once every queued task has either loaded or failed
     */
    public @NotNull CompletableFuture<Void> loadAll() {
        List<LoadTask<?>> queued = List.copyOf(tasks);
        tasks.clear();

        CompletableFuture<?>[] futures = new CompletableFuture<?>[queued.size()];
        for (int i = 0; i < queued.size(); i++) {
            futures[i] = launch(queued.get(i));
        }
        return CompletableFuture.allOf(futures)
                .thenRun(() -> logger.info("Finished loading {} configuration(s).", queued.size()));
    }

    /**
     * Launches one queued task, containing its failures.
     * <p>
     * This private method is what keeps the whole class free of raw types: the wildcard
     * {@code LoadTask<?>} taken from the queue is capture-converted here, giving the body a
     * single named {@code G} under which the task's class, future and callback all agree.
     *
     * @param task the task to launch
     * @param <GeneratedConfigType>  the generated configuration type of the task
     * @return a future that always completes normally, after logging success or failure
     */
    private <GeneratedConfigType extends GeneratedConfig> @NonNull CompletableFuture<Void> launch(@NonNull LoadTask<GeneratedConfigType> task) {
        File file = new File(task.folder().resolve(dataFolder), task.fileName());
        return ConfigFactory.loadOrCreateAsync(file, task.type())
                .thenAccept(config -> {
                    task.onLoad().accept(config);
                    logger.debug("Loaded configuration '{}' from {}.", task.configName(), file);
                })
                .exceptionally(failure -> {
                    logger.error("Failed to load configuration '{}' from {}.",
                            task.configName(), file, failure);
                    return null;
                });
    }

    /**
     * One queued load: where the file lives, what class reads it, who receives the result and
     * what the log calls it.
     *
     * @param folder     the directory the file lives in, resolved against the data folder
     * @param fileName   the file name, including its extension
     * @param type       the generated configuration class
     * @param onLoad     callback receiving the loaded configuration
     * @param configName the display name used by the log lines
     * @param <GeneratedConfigType>        the generated configuration type
     */
    private record LoadTask<GeneratedConfigType extends GeneratedConfig>(
            @NotNull ConfigFolder folder,
            @NotNull String fileName,
            @NotNull Class<GeneratedConfigType> type,
            @NotNull Consumer<? super GeneratedConfigType> onLoad,
            @NotNull String configName
    ) {
    }
}
