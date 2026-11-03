package pandastic.studio.core.service.api.config.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the two {@link ConfigFolder} factories: where they resolve to, and that resolving is a
 * pure path computation that never creates anything on disk.
 */
@DisplayName("ConfigFolder")
class ConfigFolderTest {

    @TempDir
    File dataFolder;

    @Test
    @DisplayName("root() resolves to the data folder itself")
    void rootResolvesToDataFolder() {
        assertEquals(dataFolder, ConfigFolder.root().resolve(dataFolder));
    }

    @Test
    @DisplayName("of() chains subdirectories below the data folder")
    void ofChainsSubdirectories() {
        File expected = new File(new File(dataFolder, "storage"), "sql");

        assertEquals(expected, ConfigFolder.of("storage", "sql").resolve(dataFolder));
    }

    @Test
    @DisplayName("of() with no segments behaves like root()")
    void emptyOfBehavesLikeRoot() {
        assertEquals(dataFolder, ConfigFolder.of().resolve(dataFolder));
    }

    @Test
    @DisplayName("resolving never creates the directory")
    void resolutionCreatesNothing() {
        File resolved = ConfigFolder.of("storage", "sql").resolve(dataFolder);

        assertFalse(resolved.exists(), "resolve must not touch the file system");
        assertFalse(new File(dataFolder, "storage").exists(), "no intermediate directory either");
    }

    @Test
    @DisplayName("rejects blank and null segments eagerly")
    void rejectsBadSegments() {
        assertThrows(IllegalArgumentException.class, () -> ConfigFolder.of("storage", " "));
        assertThrows(IllegalArgumentException.class, () -> ConfigFolder.of((String) null));
    }

    @Test
    @DisplayName("is not affected by later mutation of the path array")
    void defensivelyCopiesThePath() {
        String[] path = {"storage"};
        ConfigFolder folder = ConfigFolder.of(path);
        path[0] = "elsewhere";

        assertEquals(new File(dataFolder, "storage"), folder.resolve(dataFolder));
    }
}
