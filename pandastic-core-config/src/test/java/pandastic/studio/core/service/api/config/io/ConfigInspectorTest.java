package pandastic.studio.core.service.api.config.io;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.defaults.DatabaseConfig;
import pandastic.studio.core.service.api.config.defaults.DatabaseSettings;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inspects real {@link DatabaseConfig} files against the hand-written {@link DatabaseSettings}
 * declaration: present, missing, unknown and unparseable states, plus the reflective walk over
 * both declaration shapes with its most-derived-wins deduplication.
 */
@DisplayName("ConfigInspector")
class ConfigInspectorTest {

    /** The six keys {@link DatabaseSettings} declares, in declaration order. */
    private static final List<String> DECLARED_KEYS =
            List.of("host", "port", "database", "user", "password", "useSSL");

    @TempDir
    File tempDir;

    private File freshDatabaseFile() {
        File file = new File(tempDir, DatabaseConfig.DEFAULT_FILENAME);
        ConfigFactory.loadOrCreate(file, DatabaseConfig.class);
        return file;
    }

    private static ConfigInspection.FieldReport reportFor(ConfigInspection inspection, String key) {
        return inspection.fields().stream()
                .filter(report -> report.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no report for key " + key));
    }

    @Test
    @DisplayName("reports all six declared keys present on a freshly created file")
    void freshFileHasEveryKeyPresent() {
        ConfigInspection inspection =
                ConfigInspector.inspect("database", freshDatabaseFile(), DatabaseSettings.class);

        assertTrue(inspection.fileExists());
        assertNull(inspection.syntaxError());
        assertTrue(inspection.fieldMetadataAvailable());
        // Compared as sets: reflection does not guarantee source order for interface methods.
        assertEquals(Set.copyOf(DECLARED_KEYS), inspection.fields().stream()
                .map(ConfigInspection.FieldReport::key).collect(Collectors.toSet()));
        assertEquals(DECLARED_KEYS.size(), inspection.fields().size());
        assertTrue(inspection.fields().stream().allMatch(ConfigInspection.FieldReport::present),
                "every declared key must be present in a freshly written file");
        assertTrue(inspection.unknownKeys().isEmpty());

        ConfigInspection.FieldReport host = reportFor(inspection, "host");
        assertEquals("String", host.typeName());
        assertEquals("Hostname or IP address of the MySQL server", host.comment());
        assertEquals(DatabaseSettings.DEFAULT_HOST, host.value());
        assertEquals(DatabaseSettings.DEFAULT_PORT, reportFor(inspection, "port").value());
        assertEquals("boolean", reportFor(inspection, "useSSL").typeName());
    }

    @Test
    @DisplayName("reports a deleted key as missing while the others stay present")
    void deletedKeyIsReportedMissing() throws Exception {
        File file = freshDatabaseFile();
        YamlConfiguration edit = YamlConfiguration.loadConfiguration(file);
        edit.set("port", null);
        edit.save(file);

        ConfigInspection inspection =
                ConfigInspector.inspect("database", file, DatabaseSettings.class);

        ConfigInspection.FieldReport port = reportFor(inspection, "port");
        assertFalse(port.present(), "the deleted key must be reported missing");
        assertNull(port.value());
        assertTrue(reportFor(inspection, "host").present(), "other keys stay present");
        assertEquals(DECLARED_KEYS.size(), inspection.fields().size(),
                "a missing key is still reported, just as absent");
    }

    @Test
    @DisplayName("reports a stray key as unknown without disturbing the declared ones")
    void strayKeyIsReportedUnknown() throws Exception {
        File file = freshDatabaseFile();
        YamlConfiguration edit = YamlConfiguration.loadConfiguration(file);
        edit.set("stray", 42);
        edit.save(file);

        ConfigInspection inspection =
                ConfigInspector.inspect("database", file, DatabaseSettings.class);

        assertEquals(List.of("stray"), inspection.unknownKeys());
        assertEquals(DECLARED_KEYS.size(), inspection.fields().size());
        assertTrue(inspection.fields().stream().allMatch(ConfigInspection.FieldReport::present));
    }

    @Test
    @DisplayName("surfaces the parser message on a malformed file and keeps the metadata")
    void malformedFileKeepsFieldMetadata() throws Exception {
        File file = new File(tempDir, DatabaseConfig.DEFAULT_FILENAME);
        Files.writeString(file.toPath(), "host: \"unclosed\n  ::");

        ConfigInspection inspection =
                ConfigInspector.inspect("database", file, DatabaseSettings.class);

        assertTrue(inspection.fileExists());
        assertNotNull(inspection.syntaxError(), "the parser message must be surfaced");
        assertTrue(inspection.fieldMetadataAvailable(),
                "a broken file must not cost the declaration metadata");
        assertEquals(DECLARED_KEYS.size(), inspection.fields().size());
        assertTrue(inspection.fields().stream().noneMatch(ConfigInspection.FieldReport::present),
                "nothing can be present in a file that did not parse");
    }

    @Test
    @DisplayName("reports a missing file with every key absent and no syntax error")
    void missingFileHasEveryKeyAbsent() {
        File file = new File(tempDir, "missing.yml");

        ConfigInspection inspection =
                ConfigInspector.inspect("database", file, DatabaseSettings.class);

        assertFalse(inspection.fileExists());
        assertNull(inspection.syntaxError());
        assertEquals(DECLARED_KEYS.size(), inspection.fields().size());
        assertTrue(inspection.fields().stream().noneMatch(ConfigInspection.FieldReport::present));
        assertTrue(inspection.unknownKeys().isEmpty());
    }

    @Test
    @DisplayName("falls back to a raw dump when no declaration type is given")
    void rawDumpWithoutDeclaration() {
        ConfigInspection inspection =
                ConfigInspector.inspect("database", freshDatabaseFile(), null);

        assertFalse(inspection.fieldMetadataAvailable());
        assertEquals(DECLARED_KEYS.size(), inspection.fields().size(),
                "the raw dump must still show every key the file holds");
        assertTrue(inspection.fields().stream()
                        .allMatch(report -> report.typeName().equals("?")),
                "raw dump reports carry no type information");
        assertTrue(inspection.unknownKeys().isEmpty(),
                "without a declaration there is nothing to call a key unknown against");
    }

    @Test
    @DisplayName("inspectAsync produces the same snapshot as the synchronous inspect")
    void asyncMatchesSync() throws Exception {
        File file = freshDatabaseFile();

        ConfigInspection async = ConfigInspector
                .inspectAsync("database", file, DatabaseSettings.class)
                .get(10, TimeUnit.SECONDS);

        assertEquals(ConfigInspector.inspect("database", file, DatabaseSettings.class), async);
    }

    @Test
    @DisplayName("walks annotated fields up the hierarchy, most derived declaration first")
    void walksFieldHierarchyMostDerivedFirst() {
        ConfigInspection inspection = ConfigInspector.inspect("derived",
                new File(tempDir, "derived.yml"), DerivedDeclaration.class);

        List<String> keys = inspection.fields().stream()
                .map(ConfigInspection.FieldReport::key).toList();
        assertTrue(keys.containsAll(List.of("shared", "derivedOnly", "baseOnly")),
                "both the class and its superclass contribute keys: " + keys);
        assertEquals(3, keys.size(), "the duplicated key must be reported once");
        assertTrue(keys.indexOf("derivedOnly") < keys.indexOf("baseOnly"),
                "the derived class is walked before its superclass");
        assertEquals("derived", reportFor(inspection, "shared").comment(),
                "on a duplicated key the most derived declaration wins");
        assertEquals("int", reportFor(inspection, "baseOnly").typeName());
    }

    /** Superclass half of the duplicated-key fixture; its {@code shared} entry must lose. */
    static class BaseDeclaration {

        @ConfigValue(fieldName = "shared", comment = "base")
        private String shared = "from base";

        @ConfigValue
        private int baseOnly = 1;
    }

    /** Subclass half of the duplicated-key fixture; its {@code shared} entry must win. */
    static final class DerivedDeclaration extends BaseDeclaration {

        @ConfigValue(fieldName = "shared", comment = "derived")
        private String shared = "from derived";

        @ConfigValue
        private boolean derivedOnly;
    }
}
