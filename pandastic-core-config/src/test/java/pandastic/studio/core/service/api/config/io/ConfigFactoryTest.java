package pandastic.studio.core.service.api.config.io;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.core.service.api.config.GeneratedConfig;
import pandastic.studio.core.service.api.config.defaults.DatabaseConfig;
import pandastic.studio.core.service.api.config.defaults.DatabaseSettings;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips {@link ConfigFactory} through the real generated {@link DatabaseConfig}: a
 * missing file is created with the declared defaults, an edited file is read back, and
 * reflective failures surface as clear {@link IllegalStateException}s instead of raw
 * reflection errors.
 */
@DisplayName("ConfigFactory")
class ConfigFactoryTest {

    @TempDir
    File tempDir;

    @Test
    @DisplayName("loadOrCreate on a missing file creates it with the declared defaults")
    void createsMissingFileWithDefaults() {
        // The nested path also proves the factory creates parent directories at write time.
        File file = new File(new File(tempDir, "nested"), DatabaseConfig.DEFAULT_FILENAME);

        DatabaseConfig config = ConfigFactory.loadOrCreate(file, DatabaseConfig.class);

        assertTrue(file.isFile(), "the file must be written on first load");
        assertEquals(DatabaseSettings.DEFAULT_HOST, config.getHost());
        assertEquals(DatabaseSettings.DEFAULT_PORT, config.getPort());

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
        assertEquals(DatabaseSettings.DEFAULT_HOST, onDisk.getString("host"));
        assertEquals(DatabaseSettings.DEFAULT_PORT, onDisk.getInt("port"));
        assertEquals(DatabaseSettings.DEFAULT_DATABASE, onDisk.getString("database"));
        assertEquals(DatabaseSettings.DEFAULT_USER, onDisk.getString("user"));
        assertEquals(DatabaseSettings.DEFAULT_PASSWORD, onDisk.getString("password"));
        assertEquals(DatabaseSettings.DEFAULT_USE_SSL, onDisk.getBoolean("useSSL"));
    }

    @Test
    @DisplayName("a second loadOrCreate reads edited values and keeps untouched keys")
    void readsEditedValuesOnSecondLoad() throws Exception {
        File file = new File(tempDir, DatabaseConfig.DEFAULT_FILENAME);
        ConfigFactory.loadOrCreate(file, DatabaseConfig.class);

        YamlConfiguration edit = YamlConfiguration.loadConfiguration(file);
        edit.set("host", "db.example.com");
        edit.set("port", 3307);
        edit.save(file);

        DatabaseConfig reloaded = ConfigFactory.loadOrCreate(file, DatabaseConfig.class);

        assertEquals("db.example.com", reloaded.getHost());
        assertEquals(3307, reloaded.getPort());
        // A key the edit did not touch still carries the value the file holds for it.
        assertEquals(DatabaseSettings.DEFAULT_DATABASE, reloaded.getDatabase());
    }

    @Test
    @DisplayName("loadOrCreateAsync completes with the loaded configuration")
    void asyncRoundTrip() throws Exception {
        File file = new File(tempDir, DatabaseConfig.DEFAULT_FILENAME);

        DatabaseConfig config = ConfigFactory.loadOrCreateAsync(file, DatabaseConfig.class)
                .get(10, TimeUnit.SECONDS);

        assertNotNull(config);
        assertTrue(file.isFile(), "the async variant must create the file too");
        assertEquals(DatabaseSettings.DEFAULT_HOST, config.getHost());
    }

    @Test
    @DisplayName("a type without a public no-arg constructor fails with a message naming it")
    void missingConstructorFailsClearly() {
        File file = new File(tempDir, "broken.yml");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ConfigFactory.loadOrCreate(file, PrivateConstructorConfig.class));

        assertTrue(failure.getMessage().contains(PrivateConstructorConfig.class.getName()),
                "the message must name the offending type: " + failure.getMessage());
        assertFalse(file.exists(), "nothing must be written when instantiation fails");
    }

    @Test
    @DisplayName("loadOrCreateAsync never throws; failures surface through the future")
    void asyncFailuresSurfaceThroughTheFuture() {
        File file = new File(tempDir, "broken.yml");

        // The call itself must not throw, even though the type cannot be instantiated.
        CompletableFuture<PrivateConstructorConfig> future =
                ConfigFactory.loadOrCreateAsync(file, PrivateConstructorConfig.class);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    /**
     * A syntactically valid {@link GeneratedConfig} that no factory can instantiate: its only
     * constructor is private, standing in for a hand-written class that broke the contract.
     */
    static final class PrivateConstructorConfig implements GeneratedConfig {

        private PrivateConstructorConfig() {
        }

        @Override
        public void init(@NotNull File file) {
            throw new UnsupportedOperationException("never reached");
        }

        @Override
        public void load() {
            throw new UnsupportedOperationException("never reached");
        }

        @Override
        public void save() {
            throw new UnsupportedOperationException("never reached");
        }

        @Override
        public void loadOrCreate() {
            throw new UnsupportedOperationException("never reached");
        }

        @Override
        public @NotNull CompletableFuture<Void> loadAsync() {
            throw new UnsupportedOperationException("never reached");
        }

        @Override
        public @NotNull CompletableFuture<Void> saveAsync() {
            throw new UnsupportedOperationException("never reached");
        }

        @Override
        public @NotNull CompletableFuture<Void> loadOrCreateAsync() {
            throw new UnsupportedOperationException("never reached");
        }
    }
}
