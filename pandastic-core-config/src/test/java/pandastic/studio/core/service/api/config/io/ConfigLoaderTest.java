package pandastic.studio.core.service.api.config.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import pandastic.studio.core.service.api.config.defaults.DatabaseConfig;
import pandastic.studio.core.service.api.config.defaults.DatabaseSettings;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link ConfigLoader} batch through its package-private test constructor, which
 * stands in for {@link ConfigLoader#ConfigLoader(org.bukkit.plugin.Plugin)} without requiring
 * a {@code Plugin} implementation: the production constructor only contributes the data folder
 * and the logger, which these tests supply directly.
 */
@DisplayName("ConfigLoader")
class ConfigLoaderTest {

    @TempDir
    File dataFolder;

    private ConfigLoader newLoader() {
        return new ConfigLoader(dataFolder, NOPLogger.NOP_LOGGER);
    }

    @Test
    @DisplayName("loads every queued configuration and hands each to its callback")
    void loadsAllQueuedConfigurations() throws Exception {
        AtomicReference<DatabaseConfig> first = new AtomicReference<>();
        AtomicReference<DatabaseConfig> second = new AtomicReference<>();

        newLoader()
                .load(ConfigFolder.root(), "first.yml", DatabaseConfig.class, first::set)
                .load(ConfigFolder.of("storage"), "second.yml", DatabaseConfig.class,
                        second::set, "second configuration")
                .loadAll()
                .get(10, TimeUnit.SECONDS);

        assertNotNull(first.get(), "first callback must receive its configuration");
        assertNotNull(second.get(), "second callback must receive its configuration");
        assertEquals(DatabaseSettings.DEFAULT_HOST, first.get().getHost());
        assertTrue(new File(dataFolder, "first.yml").isFile());
        assertTrue(new File(new File(dataFolder, "storage"), "second.yml").isFile(),
                "the subfolder must be created at write time");
    }

    @Test
    @DisplayName("contains a broken file to its own task and still completes the batch")
    void containsBrokenFileToItsTask() throws Exception {
        Files.writeString(new File(dataFolder, "broken.yml").toPath(), "host: \"unclosed\n  ::");
        AtomicReference<DatabaseConfig> broken = new AtomicReference<>();
        AtomicReference<DatabaseConfig> healthy = new AtomicReference<>();

        newLoader()
                .load(ConfigFolder.root(), "broken.yml", DatabaseConfig.class, broken::set)
                .load(ConfigFolder.root(), "healthy.yml", DatabaseConfig.class, healthy::set)
                .loadAll()
                .get(10, TimeUnit.SECONDS);

        assertNull(broken.get(), "a failed load must not invoke its callback");
        assertNotNull(healthy.get(), "one broken file must not sink the other tasks");
    }

    @Test
    @DisplayName("contains a throwing callback and still completes the batch")
    void containsThrowingCallback() throws Exception {
        AtomicReference<DatabaseConfig> other = new AtomicReference<>();

        newLoader()
                .load(ConfigFolder.root(), "angry.yml", DatabaseConfig.class, config -> {
                    throw new IllegalStateException("callback failure");
                })
                .load(ConfigFolder.root(), "calm.yml", DatabaseConfig.class, other::set)
                .loadAll()
                .get(10, TimeUnit.SECONDS);

        assertNotNull(other.get(), "a throwing callback must not sink the other tasks");
    }

    @Test
    @DisplayName("loadAndTransform hands the callback the transformed value")
    void transformsBeforeTheCallback() throws Exception {
        AtomicReference<String> host = new AtomicReference<>();

        newLoader()
                .loadAndTransform(ConfigFolder.root(), "transformed.yml", DatabaseConfig.class,
                        DatabaseConfig::getHost, host::set, "host only")
                .loadAll()
                .get(10, TimeUnit.SECONDS);

        assertEquals(DatabaseSettings.DEFAULT_HOST, host.get());
    }

    @Test
    @DisplayName("loadAll drains the queue, so a second call does not reload")
    void loadAllDrainsTheQueue() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        ConfigLoader loader = newLoader()
                .load(ConfigFolder.root(), "once.yml", DatabaseConfig.class,
                        config -> invocations.incrementAndGet());

        loader.loadAll().get(10, TimeUnit.SECONDS);
        loader.loadAll().get(10, TimeUnit.SECONDS);

        assertEquals(1, invocations.get(), "a drained task must not run again");
    }
}
