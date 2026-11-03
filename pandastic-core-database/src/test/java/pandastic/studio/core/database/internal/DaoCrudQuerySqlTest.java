package pandastic.studio.core.database.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.database.api.Aggregate;
import pandastic.studio.core.database.api.DaoQuery;
import pandastic.studio.core.database.internal.DaoCrud.CompiledDaoQuery;
import pandastic.studio.core.database.internal.DaoTestHarness.QueryRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the SQL that {@link DaoCrud} generates for {@link DaoQuery}-driven operations and for the
 * additive migration diff — entirely without a database, through the package-private compile
 * seams. The exact statement text is the contract here: every identifier is interpolated (SQL has
 * no parameter slot for one) and every value must appear as a {@code ?}, so a change in these
 * strings is either a deliberate SQL change or an injection regression.
 * <p>
 * What is deliberately NOT covered: the live execution paths (prepare/bind/execute against a real
 * MySQL). Faking a whole JDBC connection would test the fake, not the driver.
 */
class DaoCrudQuerySqlTest {

    private final List<LogRecord> capturedLogRecords = new CopyOnWriteArrayList<>();
    private QueryRepository repository;

    /** Builds a fresh repository whose plugin logger feeds {@link #capturedLogRecords}. */
    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                capturedLogRecords.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        repository = DaoTestHarness.newRepository(logger);
    }

    // ================= WHERE composition =================

    @Test
    @DisplayName("every binary comparison operator renders its exact SQL token")
    void binaryComparisonOperators() {
        Map<DaoQuery.Operator, String> expectedTokens = Map.of(
                DaoQuery.Operator.EQUALS, "=",
                DaoQuery.Operator.NOT_EQUALS, "<>",
                DaoQuery.Operator.LESS_THAN, "<",
                DaoQuery.Operator.LESS_OR_EQUAL, "<=",
                DaoQuery.Operator.GREATER_THAN, ">",
                DaoQuery.Operator.GREATER_OR_EQUAL, ">=");
        for (Map.Entry<DaoQuery.Operator, String> entry : expectedTokens.entrySet()) {
            CompiledDaoQuery compiled = repository.compileQuery(
                    DaoQuery.builder().where("score", entry.getKey(), 5L).build());
            assertEquals(" WHERE `score` " + entry.getValue() + " ?", compiled.whereClause,
                    entry.getKey().name());
            assertEquals(1, compiled.parameters.size());
            assertEquals(5L, compiled.parameters.getFirst().value());
        }
    }

    @Test
    @DisplayName("LIKE binds the pattern as one parameter")
    void likeOperator() {
        CompiledDaoQuery compiled = repository.compileQuery(
                DaoQuery.builder().where("name", DaoQuery.Operator.LIKE, "Ale%").build());
        assertEquals(" WHERE `name` LIKE ?", compiled.whereClause);
        assertEquals(List.of("Ale%"), compiled.parameters.stream()
                .map(DaoCrud.BoundParameter::value).toList());
    }

    @Test
    @DisplayName("BETWEEN renders two placeholders and binds two parameters in order")
    void betweenOperator() {
        CompiledDaoQuery compiled = repository.compileQuery(
                DaoQuery.builder().where("score", DaoQuery.Operator.BETWEEN, 10L, 20L).build());
        assertEquals(" WHERE `score` BETWEEN ? AND ?", compiled.whereClause);
        assertEquals(List.of(10L, 20L), compiled.parameters.stream()
                .map(DaoCrud.BoundParameter::value).toList());
    }

    @Test
    @DisplayName("IN renders one placeholder per element and binds them in order")
    void inOperator() {
        CompiledDaoQuery compiled = repository.compileQuery(
                DaoQuery.builder().whereIn("score", List.of(1L, 2L, 3L)).build());
        assertEquals(" WHERE `score` IN (?, ?, ?)", compiled.whereClause);
        assertEquals(List.of(1L, 2L, 3L), compiled.parameters.stream()
                .map(DaoCrud.BoundParameter::value).toList());
    }

    @Test
    @DisplayName("IS_NULL and IS_NOT_NULL render without any parameter")
    void nullOperators() {
        CompiledDaoQuery isNull = repository.compileQuery(
                DaoQuery.builder().where("name", DaoQuery.Operator.IS_NULL).build());
        assertEquals(" WHERE `name` IS NULL", isNull.whereClause);
        assertTrue(isNull.parameters.isEmpty());

        CompiledDaoQuery isNotNull = repository.compileQuery(
                DaoQuery.builder().where("name", DaoQuery.Operator.IS_NOT_NULL).build());
        assertEquals(" WHERE `name` IS NOT NULL", isNotNull.whereClause);
        assertTrue(isNotNull.parameters.isEmpty());
    }

    @Test
    @DisplayName("multiple conditions chain with AND, in the order they were added")
    void andChaining() {
        CompiledDaoQuery compiled = repository.compileQuery(DaoQuery.builder()
                .where("name", DaoQuery.Operator.EQUALS, "Ale")
                .where("score", DaoQuery.Operator.GREATER_THAN, 10L)
                .where("created_at", DaoQuery.Operator.IS_NOT_NULL)
                .build());
        assertEquals(" WHERE `name` = ? AND `score` > ? AND `created_at` IS NOT NULL",
                compiled.whereClause);
        assertEquals(List.of("Ale", 10L), compiled.parameters.stream()
                .map(DaoCrud.BoundParameter::value).toList());
    }

    @Test
    @DisplayName("parameters carry the column's declared Java type for binding")
    void parametersCarryColumnJavaType() {
        CompiledDaoQuery compiled = repository.compileQuery(DaoQuery.builder()
                .where("id", DaoQuery.Operator.EQUALS, UUID.randomUUID())
                .where("score", DaoQuery.Operator.LESS_THAN, 3L)
                .build());
        assertEquals(UUID.class, compiled.parameters.get(0).javaType());
        assertEquals(long.class, compiled.parameters.get(1).javaType());
    }

    // ================= ORDER BY / LIMIT / OFFSET =================

    @Test
    @DisplayName("orderBy is repeatable and preserves the declaration order of its terms")
    void orderByMultiColumn() {
        CompiledDaoQuery compiled = repository.compileQuery(DaoQuery.builder()
                .orderBy("score", DaoQuery.Direction.DESC)
                .orderBy("name", DaoQuery.Direction.ASC)
                .build());
        assertEquals(" ORDER BY `score` DESC, `name` ASC", compiled.orderByClause);
    }

    @Test
    @DisplayName("limit and offset render after ORDER BY, in LIMIT n OFFSET m form")
    void limitAndOffsetPlacement() {
        CompiledDaoQuery limitOnly = repository.compileQuery(
                DaoQuery.builder().limit(10).build());
        assertEquals(" LIMIT 10", limitOnly.limitClause);

        DaoQuery query = DaoQuery.builder()
                .where("score", DaoQuery.Operator.GREATER_OR_EQUAL, 1L)
                .orderBy("score", DaoQuery.Direction.DESC)
                .limit(10)
                .offset(20)
                .build();
        assertEquals("SELECT * FROM `query_rows` WHERE `score` >= ?"
                        + " ORDER BY `score` DESC LIMIT 10 OFFSET 20",
                repository.buildSelectSql(repository.compileQuery(query)));
    }

    @Test
    @DisplayName("an empty query selects the whole table")
    void emptyQuerySelectsAll() {
        assertEquals("SELECT * FROM `query_rows`",
                repository.buildSelectSql(repository.compileQuery(DaoQuery.builder().build())));
    }

    // ================= Column validation =================

    @Test
    @DisplayName("an undeclared column is rejected with the same message findByColumnAsync uses")
    void unknownColumnRejected() {
        IllegalArgumentException whereFailure = assertThrows(IllegalArgumentException.class,
                () -> repository.compileQuery(DaoQuery.builder()
                        .where("nope", DaoQuery.Operator.EQUALS, 1).build()));
        assertEquals("Unknown column: nope", whereFailure.getMessage());

        IllegalArgumentException orderFailure = assertThrows(IllegalArgumentException.class,
                () -> repository.compileQuery(DaoQuery.builder()
                        .orderBy("nope", DaoQuery.Direction.ASC).build()));
        assertEquals("Unknown column: nope", orderFailure.getMessage());
    }

    @Test
    @DisplayName("findWhereAsync fails its future fast on an unknown column, before any I/O")
    void findWhereFailsFastOnUnknownColumn() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> repository.findWhereAsync(DaoQuery.builder()
                                .where("nope", DaoQuery.Operator.EQUALS, 1).build())
                        .get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals("Unknown column: nope", failure.getCause().getMessage());
    }

    // ================= DELETE rules =================

    @Test
    @DisplayName("a predicate-free delete is rejected, naming deleteAllAsync as the intended method")
    void deleteWithoutPredicateRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.buildDeleteSql(
                        repository.compileQuery(DaoQuery.builder().build())));
        assertTrue(failure.getMessage().contains("deleteAllAsync"), failure.getMessage());

        ExecutionException futureFailure = assertThrows(ExecutionException.class,
                () -> repository.deleteWhereAsync(DaoQuery.builder().build())
                        .get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, futureFailure.getCause());
    }

    @Test
    @DisplayName("delete supports ORDER BY + LIMIT (capped pruning) but rejects OFFSET")
    void deleteOrderLimitOffsetRules() {
        DaoQuery pruning = DaoQuery.builder()
                .where("created_at", DaoQuery.Operator.LESS_THAN, Instant.EPOCH)
                .orderBy("created_at", DaoQuery.Direction.ASC)
                .limit(1000)
                .build();
        assertEquals("DELETE FROM `query_rows` WHERE `created_at` < ?"
                        + " ORDER BY `created_at` ASC LIMIT 1000",
                repository.buildDeleteSql(repository.compileQuery(pruning)));

        DaoQuery withOffset = DaoQuery.builder()
                .where("score", DaoQuery.Operator.EQUALS, 1L)
                .limit(10)
                .offset(5)
                .build();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.buildDeleteSql(repository.compileQuery(withOffset)));
        assertTrue(failure.getMessage().contains("OFFSET"), failure.getMessage());
    }

    // ================= COUNT and aggregates =================

    @Test
    @DisplayName("count keeps the predicate but ignores ORDER BY / LIMIT / OFFSET")
    void countIgnoresOrderingAndPaging() {
        DaoQuery pagedQuery = DaoQuery.builder()
                .where("score", DaoQuery.Operator.GREATER_OR_EQUAL, 100L)
                .orderBy("score", DaoQuery.Direction.DESC)
                .limit(10)
                .offset(20)
                .build();
        assertEquals("SELECT COUNT(*) FROM `query_rows` WHERE `score` >= ?",
                repository.buildCountSql(repository.compileQuery(pagedQuery)));
    }

    @Test
    @DisplayName("aggregates render FUNCTION(`column`) with the predicate, for all four functions")
    void aggregateSqlShape() {
        CompiledDaoQuery compiled = repository.compileQuery(DaoQuery.builder()
                .where("name", DaoQuery.Operator.EQUALS, "Ale").build());
        for (Aggregate function : Aggregate.values()) {
            assertEquals("SELECT " + function.name() + "(`score`) FROM `query_rows`"
                            + " WHERE `name` = ?",
                    repository.buildAggregateSql(function, "score", compiled));
        }

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.buildAggregateSql(Aggregate.SUM, "nope", compiled));
        assertEquals("Unknown column: nope", failure.getMessage());
    }

    // ================= Migration diff =================

    @Test
    @DisplayName("the INFORMATION_SCHEMA probe binds schema and table as parameters")
    void informationSchemaQueryIsParameterised() {
        assertEquals("SELECT COLUMN_NAME, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                DaoCrud.SELECT_EXISTING_COLUMNS_SQL);
    }

    @Test
    @DisplayName("missing declared columns become ADD COLUMN statements, in declaration order")
    void migrationAddsMissingColumns() {
        // 'score' and 'created_at' are absent from the live table.
        List<String> statements = repository.computeMigrationStatements(Map.of(
                "id", "varchar(36)",
                "name", "varchar(16)"));
        assertEquals(List.of(
                "ALTER TABLE `query_rows` ADD COLUMN `score` BIGINT NOT NULL",
                "ALTER TABLE `query_rows` ADD COLUMN `created_at` DATETIME NOT NULL"
                        + " DEFAULT CURRENT_TIMESTAMP"),
                statements);
        assertTrue(capturedLogRecords.isEmpty(), "no drift to warn about");
    }

    @Test
    @DisplayName("a matching table produces no statements and no warnings")
    void migrationNoOpWhenTableMatches() {
        List<String> statements = repository.computeMigrationStatements(Map.of(
                "id", "varchar(36)",
                "name", "varchar(16)",
                "score", "bigint",
                "created_at", "datetime"));
        assertTrue(statements.isEmpty(), () -> "unexpected statements: " + statements);
        assertTrue(capturedLogRecords.isEmpty());
    }

    @Test
    @DisplayName("a type-drifted column is warned about — naming everything — and never altered")
    void migrationWarnsOnTypeDrift() {
        List<String> statements = repository.computeMigrationStatements(Map.of(
                "id", "varchar(36)",
                "name", "text",                 // drift: declared VARCHAR(16)
                "score", "bigint",
                "created_at", "datetime"));
        assertTrue(statements.isEmpty(), "drift must never produce an ALTER");
        assertEquals(1, capturedLogRecords.size());
        String message = capturedLogRecords.getFirst().getMessage();
        assertTrue(message.contains("query_rows"), message);
        assertTrue(message.contains("name"), message);
        assertTrue(message.contains("VARCHAR(16)"), message);
        assertTrue(message.contains("text"), message);
    }

    @Test
    @DisplayName("type equivalence tolerates case, display widths and the BOOLEAN alias — nothing else")
    void typeEquivalenceHeuristic() {
        assertTrue(DaoCrud.sqlTypesLookEquivalent("VARCHAR(36)", "varchar(36)"));
        assertTrue(DaoCrud.sqlTypesLookEquivalent("INT", "int(11)"));
        assertTrue(DaoCrud.sqlTypesLookEquivalent("BOOLEAN", "tinyint(1)"));
        assertTrue(DaoCrud.sqlTypesLookEquivalent("BIGINT", "bigint"));
        assertFalse(DaoCrud.sqlTypesLookEquivalent("VARCHAR(36)", "varchar(16)"));
        assertFalse(DaoCrud.sqlTypesLookEquivalent("BIGINT", "varchar(32)"));
        assertFalse(DaoCrud.sqlTypesLookEquivalent("INT", "bigint"));
    }

    @Test
    @DisplayName("CREATE TABLE and ADD COLUMN share the exact same column fragment (drift guard)")
    void createTableAndMigrationShareColumnFragments() {
        String createTableDdl = repository.generateCreateTableDDL();
        for (DaoCrud.ColumnMetadata columnMetadata : repository.getColumnMetadataList()) {
            String fragment = repository.buildColumnDefinitionSql(columnMetadata);
            assertTrue(createTableDdl.contains("  " + fragment),
                    () -> "CREATE TABLE lost the shared fragment: " + fragment);
        }
    }
}
