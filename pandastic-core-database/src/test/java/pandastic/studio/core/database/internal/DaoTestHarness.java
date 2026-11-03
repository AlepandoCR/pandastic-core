package pandastic.studio.core.database.internal;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Shared fixtures for the DaoCrud tests that never touch a live database: a {@link Plugin}
 * proxy that throws on anything but identity and logging (so a reintroduced scheduler or server
 * dependency fails loudly), a {@link MySQLManager} built around it (construction only validates,
 * it never connects), and a hand-assembled repository over a small DAO interface whose SQL
 * generation and binding seams the tests pin.
 */
final class DaoTestHarness {

    private DaoTestHarness() {
    }

    /**
     * The DAO interface the SQL-generation and binding tests run against. Its getters only
     * exist to satisfy {@code ColumnMetadata}; no entity is ever loaded.
     */
    interface QueryDao {

        /** @return primary key column {@code id} (VARCHAR(36)). */
        UUID getId();

        /** @return nullable column {@code name} (VARCHAR(16)). */
        String getName();

        /** @return numeric column {@code score} (BIGINT). */
        long getScore();

        /** @return timestamp column {@code created_at} (DATETIME DEFAULT CURRENT_TIMESTAMP). */
        Instant getCreatedAt();
    }

    /**
     * Concrete repository binding {@link QueryDao} to the table {@code query_rows}, giving the
     * tests access to DaoCrud's protected and package-private seams.
     */
    static final class QueryRepository extends DaoCrud<QueryDao, UUID> {

        /**
         * Builds the repository over the given (never-connecting) manager.
         *
         * @param manager the manager to construct against
         */
        QueryRepository(MySQLManager manager) {
            super(manager, QueryDao.class, "query_rows", columns());
        }
    }

    /**
     * Builds a repository whose warnings land on the given logger, so tests can capture them.
     *
     * @param logger the logger the fake plugin reports as its own
     * @return a fresh repository over a never-connecting manager
     */
    static QueryRepository newRepository(Logger logger) {
        return new QueryRepository(MySQLManager.create(schedulerlessPlugin(logger),
                "localhost", 3306, "query_sql_test", "user", "password", false));
    }

    /**
     * Declares the four test columns, mirroring what the annotation processor would emit.
     *
     * @return the column metadata of {@link QueryDao}
     */
    private static List<DaoCrud.ColumnMetadata> columns() {
        return List.of(
                new DaoCrud.ColumnMetadata("id", "VARCHAR(36)", false, false, true, false, null,
                        getter("getId"), UUID.class, null, null, null, null, null),
                new DaoCrud.ColumnMetadata("name", "VARCHAR(16)", true, false, false, false, null,
                        getter("getName"), String.class, null, null, null, null, null),
                new DaoCrud.ColumnMetadata("score", "BIGINT", false, false, false, false, null,
                        getter("getScore"), long.class, null, null, null, null, null),
                new DaoCrud.ColumnMetadata("created_at", "DATETIME", false, false, false, false,
                        "CURRENT_TIMESTAMP", getter("getCreatedAt"), Instant.class,
                        null, null, null, null, null));
    }

    /**
     * Looks a {@link QueryDao} getter up by name.
     *
     * @param methodName the getter name
     * @return the reflected method
     */
    private static Method getter(String methodName) {
        try {
            return QueryDao.class.getMethod(methodName);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("test DAO getter vanished: " + methodName, missing);
        }
    }

    /**
     * A {@link Plugin} that supports exactly what the database layer is allowed to need —
     * identity and a logger. Everything else throws, which turns any new server dependency
     * into an immediate test failure instead of a hang.
     *
     * @param logger the logger to serve from {@code getLogger()}
     * @return the proxy plugin
     */
    static Plugin schedulerlessPlugin(Logger logger) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "DaoTestPlugin";
            case "getLogger" -> logger;
            case "isEnabled" -> true;
            case "toString" -> "SchedulerlessPlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                    "the database layer must not need Plugin#" + method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }
}
