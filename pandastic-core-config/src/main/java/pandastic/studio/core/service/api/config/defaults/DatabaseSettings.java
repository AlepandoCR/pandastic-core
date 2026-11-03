package pandastic.studio.core.service.api.config.defaults;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of the database configuration file and, at the same time, the narrow read-only
 * view of the credentials needed to reach a MySQL server.
 * <p>
 * This single interface carries the whole schema: every {@link ConfigValue} annotated method
 * names a key, documents it and - through its {@code default} implementation - supplies the
 * value used when the file does not define it. The annotation processor turns the declaration
 * into {@code DatabaseConfig}, a {@link pandastic.studio.core.service.api.config.GeneratedConfig}
 * implementation placed in this same package that reads and writes {@code DatabaseConfig.yml}
 * and implements this interface directly.
 * <p>
 * This is also the only type {@code pandastic-core-database} needs to know about: it lets the connection
 * manager be handed its settings by whoever owns them, without ever learning where they came
 * from. The generated configuration is the implementation shipped by this module, but any
 * value holder, test double or already parsed configuration may implement the interface - the
 * default methods even make an anonymous {@code new DatabaseSettings() {}} a complete,
 * default-valued implementation.
 */
@Configurable(name = "DatabaseConfig", format = ConfigFormat.YAML)
public interface DatabaseSettings {

    /** Host used when the file does not define one: the server's own machine. */
    String DEFAULT_HOST = "localhost";

    /** Port used when the file does not define one: the MySQL default. */
    int DEFAULT_PORT = 3306;

    /** Schema used when the file does not define one. */
    String DEFAULT_DATABASE = "pandastic_core";

    /** Account used when the file does not define one. */
    String DEFAULT_USER = "root";

    /** Password used when the file does not define one: none, so a fresh install fails loudly. */
    String DEFAULT_PASSWORD = "";

    /** TLS setting used when the file does not define one: off, as local sockets rarely offer it. */
    boolean DEFAULT_USE_SSL = false;

    /**
     * Hostname or IP address of the MySQL server.
     *
     * @return the server host, never {@code null}; {@value #DEFAULT_HOST} by default
     */
    @ConfigValue(fieldName = "host", comment = "Hostname or IP address of the MySQL server")
    default @NotNull String getHost() {
        return DEFAULT_HOST;
    }

    /**
     * TCP port the MySQL server listens on.
     *
     * @return the server port, expected to be within {@code 1..65535}; {@value #DEFAULT_PORT} by default
     */
    @ConfigValue(fieldName = "port", comment = "TCP port the MySQL server listens on (default 3306)")
    default int getPort() {
        return DEFAULT_PORT;
    }

    /**
     * Name of the schema to use, created on first connection when it is missing.
     *
     * @return the database name, never {@code null}; {@value #DEFAULT_DATABASE} by default
     */
    @ConfigValue(fieldName = "database", comment = "Schema to use; it is created automatically if missing")
    default @NotNull String getDatabase() {
        return DEFAULT_DATABASE;
    }

    /**
     * Account used to authenticate against the server.
     *
     * @return the username, never {@code null}; {@value #DEFAULT_USER} by default
     */
    @ConfigValue(fieldName = "user", comment = "Account used to authenticate against the server")
    default @NotNull String getUser() {
        return DEFAULT_USER;
    }

    /**
     * Password of {@link #getUser()}.
     *
     * @return the password, never {@code null}; empty when the account has none, as it is by default
     */
    @ConfigValue(fieldName = "password", comment = "Password of the account above; leave empty if it has none")
    default @NotNull String getPassword() {
        return DEFAULT_PASSWORD;
    }

    /**
     * Whether the JDBC connection negotiates TLS.
     *
     * @return {@code true} to connect over SSL/TLS, {@code false} for a plain connection;
     *         {@value #DEFAULT_USE_SSL} by default
     */
    @ConfigValue(fieldName = "useSSL", comment = "Set to true to negotiate TLS when connecting to the server")
    default boolean isUseSsl() {
        return DEFAULT_USE_SSL;
    }
}
