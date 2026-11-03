package pandastic.studio.core.database.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.config.defaults.DatabaseSettings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * MySQL connection manager for obtaining JDBC connections and running tasks asynchronously.
 * <p>
 * The manager is handed its credentials; it never reads them itself. Pass a
 * {@link DatabaseSettings} - typically the configuration shipped in {@code pandastic-core-config} - or
 * use {@link #create} when the values are already at hand:
 * <pre>{@code
 * MySQLManager database = new MySQLManager(plugin, settings);
 * database.setupDatabase();
 * }</pre>
 * <p>
 * Contract:
 * <ul>
 *   <li>Takes host, port, database, user, password and the SSL flag from a {@link DatabaseSettings}</li>
 *   <li>Validates the host and the database name, since both are interpolated into the JDBC URL</li>
 *   <li>Creates the database if it does not exist</li>
 *   <li>Provides a lazily created single JDBC connection (guarded by a lock for multi-threaded access)</li>
 *   <li>Exposes a Gson instance for JSON serialization used by table helpers</li>
 *   <li>Offers {@link #executeWithConnection} to safely execute code with the current connection</li>
 *   <li>Offers {@link #executeWithTransaction} for atomic multi-statement operations</li>
 *   <li>Provides async utilities for non-blocking database operations</li>
 * </ul>
 */
public class MySQLManager {

    /** A Gson instance with null serialization enabled for JSON column serialization. */
    public final Gson gson = new GsonBuilder().serializeNulls().create();

    /** Scheme every JDBC URL this manager builds starts with. */
    private static final String JDBC_URL_PREFIX = "jdbc:mysql://";

    /** Driver options appended to every JDBC URL, after the {@code useSSL} flag. */
    private static final String JDBC_URL_PARAMETERS = "autoReconnect=true&characterEncoding=UTF-8&serverTimezone=UTC";

    /** Character set the managed database is created with. */
    private static final String DATABASE_CHARACTER_SET = "utf8mb4";

    /** Collation the managed database is created with. */
    private static final String DATABASE_COLLATION = "utf8mb4_unicode_ci";

    /** Lowest port a TCP service can listen on. */
    private static final int MINIMUM_PORT = 1;

    /** Highest port a TCP service can listen on. */
    private static final int MAXIMUM_PORT = 65535;

    /**
     * Hostnames and bracketed IPv6 literals. Anything else - most importantly {@code ?},
     * {@code &} and {@code /} - would let a configuration file smuggle extra driver
     * properties into the JDBC URL.
     */
    private static final Pattern VALID_HOST = Pattern.compile("[A-Za-z0-9._-]{1,255}|\\[[0-9A-Fa-f:.]{2,45}]");

    /**
     * Unquoted MySQL identifiers. The database name reaches both the JDBC URL and a
     * {@code CREATE DATABASE} statement, neither of which can be parameterised, so it is
     * restricted to characters that cannot break out of either.
     */
    private static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]{1,64}");

    private final String databaseHost;
    private final int databasePort;
    private final String databaseName;
    private final String databaseUsername;
    private final String databasePassword;
    private final boolean useSslConnection;

    private volatile Connection activeConnection;
    private final Object connectionLock = new Object();

    /** Dedicated single database thread; see {@link #runAsyncTask(Runnable)}. */
    private final ExecutorService databaseExecutor;
    private final Plugin ownerPlugin;

    /**
     * Creates a manager for the server described by the given settings.
     *
     * @param plugin   the owning plugin, used for logging and async scheduling
     * @param settings the credentials to connect with
     * @throws NullPointerException     if {@code plugin} or {@code settings} is {@code null},
     *                                  or if any of the settings returns {@code null}
     * @throws IllegalArgumentException if the host, port or database name is not usable in a JDBC URL
     */
    public MySQLManager(@NotNull Plugin plugin, @NotNull DatabaseSettings settings) {
        this(plugin,
                Objects.requireNonNull(settings, "settings cannot be null").getHost(),
                settings.getPort(),
                settings.getDatabase(),
                settings.getUser(),
                settings.getPassword(),
                settings.isUseSsl());
    }

    /**
     * Creates a manager with explicit connection parameters.
     * <p>
     * Handy for programmatic set-ups and for bridging a configuration object that is not a
     * {@link DatabaseSettings} itself.
     *
     * @param plugin   the owning plugin, used for logging and async scheduling
     * @param host     database server hostname or IP address
     * @param port     database server port
     * @param database database name
     * @param username database username
     * @param password database password
     * @param useSSL   whether to negotiate TLS
     * @return a configured MySQLManager instance
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if the host, port or database name is not usable in a JDBC URL
     */
    public static MySQLManager create(@NotNull Plugin plugin, @NotNull String host, int port,
                                      @NotNull String database, @NotNull String username,
                                      @NotNull String password, boolean useSSL) {
        return new MySQLManager(plugin, host, port, database, username, password, useSSL);
    }

    private MySQLManager(Plugin plugin, String host, int port, String database,
                         String username, String password, boolean useSSL) {
        this.ownerPlugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.databaseHost = requireUrlSafe(Objects.requireNonNull(host, "host cannot be null"),
                VALID_HOST, "host");
        this.databasePort = requireValidPort(port);
        this.databaseName = requireUrlSafe(Objects.requireNonNull(database, "database cannot be null"),
                VALID_IDENTIFIER, "database");
        this.databaseUsername = Objects.requireNonNull(username, "username cannot be null");
        this.databasePassword = Objects.requireNonNull(password, "password cannot be null");
        this.useSslConnection = useSSL;
        // See runAsyncTask for why this is a dedicated thread and not the Bukkit scheduler, and
        // why exactly one. Daemon: a missed shutdown() must never keep the JVM alive.
        this.databaseExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pandastic-db-" + this.databaseName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Stops the database thread after running the tasks already queued, and closes the
     * connection. Call it from {@code onDisable} once the owning plugin is done with every
     * repository built on this manager; the thread is a daemon, so missing this call leaks a
     * thread until the JVM exits but never blocks server shutdown.
     */
    public void shutdown() {
        databaseExecutor.shutdown();
        disconnect();
    }

    /**
     * Stops the database thread like {@link #shutdown()}, but <em>waits</em> for the already
     * queued work to finish before closing the connection, and reports whether the queue
     * actually drained within the timeout.
     * <p>
     * This is the method a hot-reload-supporting plugin should call from {@code onDisable}:
     * every queued {@code Runnable} closes over the consumer plugin's classes, so a queue that
     * outlives the plugin pins its classloader — a classloader leak on every reload. Blocking
     * briefly here is safe because the database thread never depends on the Bukkit scheduler.
     * Unlike {@code shutdown()} (which closes the connection immediately and lets stragglers
     * reopen it), this keeps the connection open until the queue drains, then closes it.
     * <p>
     * A {@code false} return means tasks were still running when the timeout elapsed; the
     * thread is a daemon, so it can never keep the JVM alive, but the classes it references
     * stay reachable until it finishes.
     *
     * @param timeout how long to wait for the queued work to finish
     * @return {@code true} if the queue drained and the thread terminated within the timeout
     * @throws NullPointerException if {@code timeout} is {@code null}
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean awaitShutdown(@NotNull Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException _) {
            // The duration overflows a long of nanoseconds; saturate to "wait forever".
            timeoutNanos = Long.MAX_VALUE;
        }
        databaseExecutor.shutdown();
        boolean drained = databaseExecutor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS);
        disconnect();
        return drained;
    }

    /**
     * Rejects values that would change the meaning of the JDBC URL or of a DDL statement
     * instead of merely filling a slot in it.
     *
     * @param value   the configured value
     * @param pattern the shape the value has to match entirely
     * @param what    the name of the setting, for the error message
     * @return the value itself, once accepted
     * @throws IllegalArgumentException if the value does not match
     */
    private static String requireUrlSafe(String value, Pattern pattern, String what) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Database " + what + " '" + value
                    + "' is not valid; it must match " + pattern.pattern());
        }
        return value;
    }

    /**
     * Validates that a port is within the range a TCP service can occupy.
     *
     * @param port the configured port
     * @return the port itself, once accepted
     * @throws IllegalArgumentException if the port is out of range
     */
    private static int requireValidPort(int port) {
        if (port < MINIMUM_PORT || port > MAXIMUM_PORT) {
            throw new IllegalArgumentException("Database port " + port + " is out of range ("
                    + MINIMUM_PORT + ".." + MAXIMUM_PORT + ")");
        }
        return port;
    }

    /**
     * Builds the JDBC URL pointing at the server itself, without selecting a database.
     *
     * @return the server level JDBC URL
     */
    private String buildServerUrl() {
        return JDBC_URL_PREFIX + databaseHost + ':' + databasePort
                + "/?useSSL=" + useSslConnection + '&' + JDBC_URL_PARAMETERS;
    }

    /**
     * Builds the JDBC URL pointing at the configured database.
     *
     * @return the database level JDBC URL
     */
    private String buildDatabaseUrl() {
        return JDBC_URL_PREFIX + databaseHost + ':' + databasePort + '/' + databaseName
                + "?useSSL=" + useSslConnection + '&' + JDBC_URL_PARAMETERS;
    }

    /**
     * Ensures that the target database exists; creates it if missing.
     * <p>
     * This connects to the MySQL server without specifying a database and creates
     * the configured database if it does not exist. The name is interpolated into the
     * statement because DDL takes no parameters, which is why it is validated on
     * construction.
     * <p>
     * This method is thread-safe.
     */
    public void ensureDatabaseExists() {
        try (Connection serverConnection = DriverManager.getConnection(buildServerUrl(),
                databaseUsername, databasePassword);
             Statement createStatement = serverConnection.createStatement()) {
            createStatement.execute("CREATE DATABASE IF NOT EXISTS `" + databaseName
                    + "` DEFAULT CHARACTER SET " + DATABASE_CHARACTER_SET
                    + " COLLATE " + DATABASE_COLLATION);
            getLogger().info("Database '" + databaseName + "' ensured to exist.");
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Failed to create database '" + databaseName +
                    "'. Check credentials and privileges.", exception);
        }
    }

    /**
     * Opens a connection to the database if none exists or the current one is closed.
     * <p>
     * This method is thread-safe. Multiple threads calling this method simultaneously
     * will not create duplicate connections.
     */
    public void connect() {
        synchronized (connectionLock) {
            // Double-check inside the synchronized block.
            try {
                if (activeConnection != null && !activeConnection.isClosed()) {
                    return;
                }
            } catch (SQLException _) {
                // Connection check failed; fall through and reconnect.
            }
            connectInternal();
        }
    }

    /**
     * Sets up the database by ensuring it exists and establishing a connection.
     * <p>
     * This is the recommended method to call during plugin initialization.
     * This method is thread-safe.
     */
    public void setupDatabase() {
        ensureDatabaseExists();
        connect();
    }

    /**
     * Sets up the database asynchronously.
     *
     * @return a CompletableFuture that completes when setup is done
     */
    public CompletableFuture<Void> setupDatabaseAsync() {
        return runAsync(this::setupDatabase);
    }

    /**
     * Closes the active connection, if any.
     * <p>
     * This method is thread-safe. The connection is dropped even when closing it fails, so
     * the next call reconnects instead of reusing a broken handle.
     */
    public void disconnect() {
        synchronized (connectionLock) {
            if (activeConnection == null) {
                return;
            }
            try {
                activeConnection.close();
                getLogger().info("Disconnected from MySQL database: " + databaseName);
            } catch (SQLException exception) {
                getLogger().log(Level.SEVERE, "Failed to disconnect from MySQL", exception);
            } finally {
                activeConnection = null;
            }
        }
    }

    /**
     * Guaranteed to return a live connection; reconnects as needed and throws if unavailable.
     * <p>
     * This method is thread-safe.
     *
     * @return an active database connection
     * @throws IllegalStateException if connection cannot be established
     */
    public Connection getConnection() {
        synchronized (connectionLock) {
            return getConnectionInternal();
        }
    }

    /**
     * Internal method to get connection - must be called within synchronized(connectionLock) block.
     *
     * @return an active database connection
     * @throws IllegalStateException if connection cannot be established
     */
    private Connection getConnectionInternal() {
        try {
            if (activeConnection == null || activeConnection.isClosed()) {
                connectInternal();
            }
        } catch (SQLException _) {
            connectInternal();
        }
        if (activeConnection == null) {
            throw new IllegalStateException("MySQL connection is not available. Verify the configured "
                    + "host/port/database/user/password, server reachability, and privileges.");
        }
        return activeConnection;
    }

    /**
     * Internal connect method - must be called within synchronized(connectionLock) block.
     * <p>
     * Any previous handle is closed first, so a failed liveness check cannot leak a socket.
     */
    private void connectInternal() {
        closeQuietly(activeConnection);
        activeConnection = null;

        final String connectionUrl = buildDatabaseUrl();
        try {
            activeConnection = DriverManager.getConnection(connectionUrl, databaseUsername, databasePassword);
            getLogger().info("Connected to MySQL database: " + databaseName);
        } catch (SQLException exception) {
            activeConnection = null;
            getLogger().log(Level.SEVERE, "Failed to connect to MySQL (" + connectionUrl +
                    ") as '" + databaseUsername + "'.", exception);
        }
    }

    /**
     * Closes a connection while ignoring any failure, used on paths that are already
     * recovering from an error.
     *
     * @param connection the connection to close, may be {@code null}
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException _) {
            // Nothing useful can be done with a connection that refuses to close.
        }
    }

    /**
     * Thread-safe execution with a JDBC connection, returning a value.
     * <p>
     * <strong>WARNING:</strong> This method is synchronous and should only be called
     * from an async context (e.g., within supplyAsync or runAsync callbacks).
     *
     * @param databaseOperation the operation to execute with the connection
     * @param <R> the return type
     * @return the result of the operation
     */
    public <R> R executeWithConnection(ConnectionFunction<R> databaseOperation) {
        synchronized (connectionLock) {
            Connection connection = getConnectionInternal();
            try {
                return databaseOperation.apply(connection);
            } catch (Exception exception) {
                throw new RuntimeException("Database operation failed", exception);
            }
        }
    }

    /**
     * Executes the provided function inside a transaction with connection-level synchronization.
     * Auto-commits are disabled for the duration; on any exception, a rollback is issued.
     * <p>
     * <strong>WARNING:</strong> This method is synchronous and should only be called
     * from an async context (e.g., within supplyAsync or runAsync callbacks).
     *
     * @param transactionOperation work to execute within a single transaction
     * @param <R> the return type
     * @return function result
     */
    public <R> R executeWithTransaction(ConnectionFunction<R> transactionOperation) {
        synchronized (connectionLock) {
            Connection connection = getConnectionInternal();
            boolean previousAutoCommit;
            try {
                previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    R result = transactionOperation.apply(connection);
                    connection.commit();
                    return result;
                } catch (Exception exception) {
                    rollbackQuietly(connection);
                    throw new RuntimeException("Transaction failed and was rolled back", exception);
                } finally {
                    restoreAutoCommitQuietly(connection, previousAutoCommit);
                }
            } catch (SQLException exception) {
                throw new RuntimeException("Failed to manage transaction state", exception);
            }
        }
    }

    /**
     * Rolls a transaction back, ignoring any failure: the caller is already propagating the
     * original exception, which is the more useful one.
     *
     * @param connection the connection holding the failed transaction
     */
    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException _) {
            // Reported through the exception the caller is about to throw.
        }
    }

    /**
     * Restores the auto-commit mode a connection had before a transaction, ignoring any failure.
     *
     * @param connection         the connection to restore
     * @param previousAutoCommit the mode to restore it to
     */
    private static void restoreAutoCommitQuietly(Connection connection, boolean previousAutoCommit) {
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException _) {
            // The connection is unusable anyway; the next call will reconnect.
        }
    }

    // ================= Async Utilities =================

    /**
     * Runs a task on this manager's dedicated database thread.
     *
     * <h4>Why not the Bukkit async scheduler</h4>
     * Bukkit dispatches async tasks from the main-thread heartbeat, which only runs once the
     * server starts ticking — and plugins are enabled <em>before</em> the first tick. A repository
     * call made during {@code onEnable} through the scheduler therefore cannot start until every
     * plugin has finished enabling; a plugin that blocks its {@code onEnable} waiting for it
     * deadlocks until the timeout. A dedicated thread has no such dependency: database futures
     * complete during startup, so schema setup in {@code onEnable} may safely {@code join()}.
     * <p>
     * A single thread is also the honest concurrency level — every operation serialises on the
     * one lock-guarded {@link Connection} anyway, so more threads would only queue on the lock.
     * The thread is a daemon named {@code pandastic-db-<database>}, so a missed shutdown can never keep
     * the JVM alive.
     *
     * @param task the task to run
     */
    public void runAsyncTask(Runnable task) {
        databaseExecutor.execute(task);
    }

    /**
     * Runs a task asynchronously and returns a CompletableFuture.
     *
     * @param task the task to run
     * @return a CompletableFuture that completes when the task finishes
     */
    public CompletableFuture<Void> runAsync(Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        runAsyncTask(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Runs a supplier asynchronously and returns a CompletableFuture with the result.
     *
     * @param supplier the supplier to run
     * @param <T> the return type
     * @return a CompletableFuture that completes with the result
     */
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runAsyncTask(() -> {
            try {
                T result = supplier.get();
                future.complete(result);
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Returns an Executor that runs tasks on the dedicated database thread.
     *
     * @return an async executor
     */
    public Executor getAsyncExecutor() {
        return this::runAsyncTask;
    }

    /**
     * Simple ping to validate the connection is healthy asynchronously.
     *
     * @return CompletableFuture with true if the connection responds to a small query
     */
    public CompletableFuture<Boolean> pingAsync() {
        return supplyAsync(() -> {
            try {
                return executeWithConnection(connection -> {
                    try (Statement statement = connection.createStatement();
                         ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                        return resultSet.next();
                    }
                });
            } catch (Exception exception) {
                return false;
            }
        });
    }

    /**
     * Functional interface for database operations that may throw exceptions.
     *
     * @param <R> the return type
     */
    @FunctionalInterface
    public interface ConnectionFunction<R> {

        /**
         * Runs the operation against the manager's connection.
         *
         * @param connection the live connection, owned by the manager and not to be closed
         * @return the result of the operation
         * @throws Exception if the operation fails, for the manager to wrap and report
         */
        R apply(Connection connection) throws Exception;
    }

    /**
     * Returns the logger of the owning plugin, so database messages stay in its channel.
     *
     * @return the plugin logger
     */
    private Logger getLogger() {
        return ownerPlugin.getLogger();
    }

    /**
     * Gets the database name this manager is configured for.
     *
     * @return the database name
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * Gets the owning plugin.
     *
     * @return the plugin
     */
    public Plugin getOwnerPlugin() {
        return ownerPlugin;
    }

    /**
     * Describes the target without ever revealing the credentials, so the value is safe to log.
     *
     * @return a short description of the configured server and database
     */
    @Override
    public String toString() {
        return "MySQLManager{" + databaseHost + ':' + databasePort + '/' + databaseName + "}";
    }
}
