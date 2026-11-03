package pandastic.studio.core.service.api.config.defaults;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.service.api.config.GeneratedConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Proves that the defaults declared by {@link DatabaseSettings} survive into the configuration
 * class the annotation processor generates from it.
 * <p>
 * The interface is the single source of truth: its {@code default} methods carry the defaults, and
 * the generated constructor reads them through the {@code DatabaseSettings.super} call form, which
 * is legal because the generated class implements the interface directly. These tests pin that
 * behaviour down: a fresh {@code DatabaseConfig} has to carry the documented defaults rather than
 * the Java zero values, because those are also the values written out when the file does not exist
 * yet.
 */
@DisplayName("Generated DatabaseConfig")
class DatabaseConfigDefaultsTest {

    @Test
    @DisplayName("starts at the defaults declared by DatabaseSettings")
    void startsAtDeclaredDefaults() {
        DatabaseConfig config = new DatabaseConfig();

        assertEquals(DatabaseSettings.DEFAULT_HOST, config.getHost(), "host");
        assertEquals(DatabaseSettings.DEFAULT_PORT, config.getPort(), "port");
        assertEquals(DatabaseSettings.DEFAULT_DATABASE, config.getDatabase(), "database");
        assertEquals(DatabaseSettings.DEFAULT_USER, config.getUser(), "user");
        assertEquals(DatabaseSettings.DEFAULT_PASSWORD, config.getPassword(), "password");
        assertEquals(DatabaseSettings.DEFAULT_USE_SSL, config.isUseSsl(), "useSSL");
    }

    @Test
    @DisplayName("starts at the documented literal values, not the Java zero values")
    void startsAtDocumentedLiterals() {
        DatabaseConfig config = new DatabaseConfig();

        // Pinned literally as well: a regression that broke seeding would otherwise still pass the
        // test above if someone changed the constants to the zero values.
        assertEquals("localhost", config.getHost());
        assertEquals(3306, config.getPort());
        assertEquals("pandastic_core", config.getDatabase());
        assertEquals("root", config.getUser());
        assertEquals("", config.getPassword());
        assertFalse(config.isUseSsl());
    }

    @Test
    @DisplayName("agrees with the interface's default methods")
    void agreesWithTheDeclaration() {
        DatabaseConfig generated = new DatabaseConfig();
        // Every member of the declaration is a default method, so an empty anonymous class is a
        // complete implementation carrying exactly the declared defaults.
        DatabaseSettings declaration = new DatabaseSettings() {
        };

        assertEquals(declaration.getHost(), generated.getHost(), "host");
        assertEquals(declaration.getPort(), generated.getPort(), "port");
        assertEquals(declaration.getDatabase(), generated.getDatabase(), "database");
        assertEquals(declaration.getUser(), generated.getUser(), "user");
        assertEquals(declaration.getPassword(), generated.getPassword(), "password");
        assertEquals(declaration.isUseSsl(), generated.isUseSsl(), "useSSL");
    }

    @Test
    @DisplayName("is substitutable for DatabaseSettings and GeneratedConfig")
    void carriesOverTheDeclaredInterfaces() {
        DatabaseConfig config = new DatabaseConfig();

        // The processor makes the generated twin implement the annotated interface directly, which
        // is what lets a generated configuration be handed to anything expecting the read-only view
        // - and what makes the DatabaseSettings.super seeding calls legal in the first place.
        assertInstanceOf(DatabaseSettings.class, config);
        assertInstanceOf(GeneratedConfig.class, config);

        DatabaseSettings settings = config;
        assertEquals(DatabaseSettings.DEFAULT_HOST, settings.getHost());
    }

    @Test
    @DisplayName("gives every instance its own state")
    void doesNotShareStateBetweenInstances() {
        // Seeding re-invokes the interface's default methods per construction, so two generated
        // configurations must never alias each other, nor any shared template.
        assertNotSame(new DatabaseConfig(), new DatabaseConfig());

        assertEquals(List.of(DatabaseSettings.DEFAULT_HOST, DatabaseSettings.DEFAULT_HOST),
                List.of(new DatabaseConfig().getHost(), new DatabaseConfig().getHost()));
    }
}
