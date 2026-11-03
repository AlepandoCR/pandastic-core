package pandastic.studio.core.database.internal;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.database.api.Aggregate;
import pandastic.studio.core.database.api.DaoQuery;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

// java.sql.Date and java.util.Date are both used below; neither is imported, so each one
// stays fully qualified and unambiguous at every use site.

/**
 * JDBC base implementation for annotated DAO interfaces.
 * <p>
 * This abstract class provides all the SQL generation and execution logic.
 * Generated repository classes will extend this class.
 * <p>
 * <strong>All database operations are asynchronous and return CompletableFuture.</strong>
 * <p>
 * Features:
 * <ul>
 *   <li>Automatic table creation and additive schema migration ({@link #migrateAsync()})</li>
 *   <li>Async CRUD operations (insertAsync, updateAsync, deleteAsync, findAsync)</li>
 *   <li>Query by column support, and composable filtered queries via {@link DaoQuery}
 *       ({@link #findWhereAsync}, {@link #countWhereAsync}, {@link #deleteWhereAsync},
 *       {@link #aggregateAsync})</li>
 *   <li>Transaction support</li>
 * </ul>
 * <p>
 * <strong>Statement safety.</strong> Every value travels as a {@link PreparedStatement}
 * parameter. Table, column and index names cannot: SQL has no parameter slot for an
 * identifier, so they are interpolated into the statements. They are therefore validated
 * against {@link #VALID_IDENTIFIER} when the repository is constructed, which keeps a
 * mistyped - or malicious - annotation value from ever reaching the server. The only
 * fragments passed through verbatim are the type and default expression of a column, both
 * of which are SQL by design and come from the developer's own source code.
 *
 * @param <T> The DAO interface type
 * @param <K> The primary key type
 */
public abstract class DaoCrud<T, K> {

    /**
     * Shape every table, column and index name has to match: an unquoted MySQL identifier
     * of at most 64 characters, with nothing in it that could close the surrounding
     * backticks and continue the statement.
     */
    protected static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]{1,64}");

    /** Connection manager every statement of this repository runs through. */
    protected final MySQLManager mysqlManager;

    /** The annotated DAO interface this repository reads and writes. */
    protected final Class<T> daoInterfaceClass;

    /** Validated name of the backing table. */
    protected final String tableName;

    /** Immutable metadata of every mapped column, in declaration order. */
    protected final List<ColumnMetadata> columnMetadataList;

    /** Metadata of the primary key column, or {@code null} when the DAO declares none. */
    protected final ColumnMetadata primaryKeyColumnMetadata;

    /** Column metadata indexed by column name, for lookups by key. */
    protected final Map<String, ColumnMetadata> columnMetadataByName;

    private volatile boolean tableCreated = false;

    /**
     * Metadata for a column derived from DAO method annotations.
     */
    public static class ColumnMetadata {

        /** Physical column name. */
        public final String columnName;

        /** Column type as it appears in the DDL, for example {@code VARCHAR(36)}. */
        public final String sqlTypeDefinition;

        /** Whether the column accepts {@code NULL}. */
        public final boolean isNullable;

        /** Whether the column carries a unique constraint. */
        public final boolean isUnique;

        /** Whether the column is the primary key. */
        public final boolean isPrimaryKey;

        /** Whether the column is filled by {@code AUTO_INCREMENT}. */
        public final boolean isAutoIncrement;

        /** Default SQL expression for the column, empty or {@code null} when it has none. */
        public final String defaultExpression;

        /** Getter of the DAO interface this column is read from. */
        public final Method getterMethod;

        /** Name of {@link #getterMethod}, cached because proxies dispatch on it. */
        public final String getterMethodName;

        /** Java type the column is mapped to. */
        public final Class<?> javaType;

        /** Table referenced by this column's foreign key, or {@code null} when it has none. */
        public final String foreignKeyReferencesTable;

        /** Column referenced by this column's foreign key, or {@code null} when it has none. */
        public final String foreignKeyReferencesColumn;

        /** {@code ON DELETE} action of the foreign key, or {@code null} for the server default. */
        public final String foreignKeyOnDeleteAction;

        /** {@code ON UPDATE} action of the foreign key, or {@code null} for the server default. */
        public final String foreignKeyOnUpdateAction;

        /** Indexes declared on this column, never {@code null}. */
        public final List<IndexMetadata> indexMetadataList;

        /**
         * Captures everything the DDL and the CRUD statements need to know about one column.
         *
         * @param columnName                 physical column name
         * @param sqlTypeDefinition          column type as it appears in the DDL
         * @param isNullable                 whether the column accepts {@code NULL}
         * @param isUnique                   whether the column carries a unique constraint
         * @param isPrimaryKey               whether the column is the primary key
         * @param isAutoIncrement            whether the column is filled by {@code AUTO_INCREMENT}
         * @param defaultExpression          default SQL expression, or {@code null} for none
         * @param getterMethod               getter of the DAO interface backing this column
         * @param javaType                   Java type the column is mapped to
         * @param foreignKeyReferencesTable  referenced table, or {@code null} for no foreign key
         * @param foreignKeyReferencesColumn referenced column, or {@code null} for no foreign key
         * @param foreignKeyOnDeleteAction   {@code ON DELETE} action, or {@code null} for the default
         * @param foreignKeyOnUpdateAction   {@code ON UPDATE} action, or {@code null} for the default
         * @param indexMetadataList          indexes declared on the column, may be {@code null}
         */
        public ColumnMetadata(String columnName, String sqlTypeDefinition, boolean isNullable, boolean isUnique,
                              boolean isPrimaryKey, boolean isAutoIncrement, String defaultExpression,
                              Method getterMethod, Class<?> javaType,
                              String foreignKeyReferencesTable, String foreignKeyReferencesColumn,
                              String foreignKeyOnDeleteAction, String foreignKeyOnUpdateAction,
                              List<IndexMetadata> indexMetadataList) {
            this.columnName = columnName;
            this.sqlTypeDefinition = sqlTypeDefinition;
            this.isNullable = isNullable;
            this.isUnique = isUnique;
            this.isPrimaryKey = isPrimaryKey;
            this.isAutoIncrement = isAutoIncrement;
            this.defaultExpression = defaultExpression;
            this.getterMethod = getterMethod;
            this.getterMethodName = getterMethod.getName();
            this.javaType = javaType;
            this.foreignKeyReferencesTable = foreignKeyReferencesTable;
            this.foreignKeyReferencesColumn = foreignKeyReferencesColumn;
            this.foreignKeyOnDeleteAction = foreignKeyOnDeleteAction;
            this.foreignKeyOnUpdateAction = foreignKeyOnUpdateAction;
            this.indexMetadataList = indexMetadataList != null ? indexMetadataList : Collections.emptyList();
        }
    }

    /**
     * Metadata for an index on a column.
     */
    public static class IndexMetadata {

        /** Index name, empty to let the repository derive one from the table and column. */
        public final String indexName;

        /** Whether the index enforces uniqueness. */
        public final boolean isUnique;

        /**
         * Describes one index declared on a column.
         *
         * @param indexName index name, or an empty string to derive it
         * @param isUnique  whether the index enforces uniqueness
         */
        public IndexMetadata(String indexName, boolean isUnique) {
            this.indexName = indexName;
            this.isUnique = isUnique;
        }
    }

    /**
     * Alias of {@link ColumnMetadata} kept for the shape the annotation processor emits,
     * which builds columns as {@code ColumnMeta} with {@link IndexInfo} indexes.
     */
    public static class ColumnMeta extends ColumnMetadata {

        /**
         * Builds column metadata from the processor's {@link IndexInfo} index list.
         *
         * @param columnName                 physical column name
         * @param sqlTypeDefinition          column type as it appears in the DDL
         * @param isNullable                 whether the column accepts {@code NULL}
         * @param isUnique                   whether the column carries a unique constraint
         * @param isPrimaryKey               whether the column is the primary key
         * @param isAutoIncrement            whether the column is filled by {@code AUTO_INCREMENT}
         * @param defaultExpression          default SQL expression, or {@code null} for none
         * @param getterMethod               getter of the DAO interface backing this column
         * @param javaType                   Java type the column is mapped to
         * @param foreignKeyReferencesTable  referenced table, or {@code null} for no foreign key
         * @param foreignKeyReferencesColumn referenced column, or {@code null} for no foreign key
         * @param foreignKeyOnDeleteAction   {@code ON DELETE} action, or {@code null} for the default
         * @param foreignKeyOnUpdateAction   {@code ON UPDATE} action, or {@code null} for the default
         * @param indexInfoList              indexes declared on the column, may be {@code null}
         */
        public ColumnMeta(String columnName, String sqlTypeDefinition, boolean isNullable, boolean isUnique,
                          boolean isPrimaryKey, boolean isAutoIncrement, String defaultExpression,
                          Method getterMethod, Class<?> javaType,
                          String foreignKeyReferencesTable, String foreignKeyReferencesColumn,
                          String foreignKeyOnDeleteAction, String foreignKeyOnUpdateAction,
                          List<IndexInfo> indexInfoList) {
            super(columnName, sqlTypeDefinition, isNullable, isUnique, isPrimaryKey, isAutoIncrement,
                  defaultExpression, getterMethod, javaType, foreignKeyReferencesTable,
                  foreignKeyReferencesColumn, foreignKeyOnDeleteAction, foreignKeyOnUpdateAction,
                  indexInfoList != null ? indexInfoList.stream()
                      .map(info -> new IndexMetadata(info.name, info.unique))
                      .toList() : null);
        }
    }

    /**
     * Alias of {@link IndexMetadata} kept for the shape the annotation processor emits.
     */
    public static class IndexInfo {

        /** Index name, empty to let the repository derive one. */
        public final String name;

        /** Whether the index enforces uniqueness. */
        public final boolean unique;

        /**
         * Describes one index declared on a column.
         *
         * @param name   index name, or an empty string to derive it
         * @param unique whether the index enforces uniqueness
         */
        public IndexInfo(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }

    /**
     * Binds a repository to its table and column metadata.
     * <p>
     * Every identifier that will be interpolated into a statement is validated here, so a
     * repository either refuses to exist or is safe to run statements for.
     *
     * @param mysqlManager       connection manager to run statements through
     * @param daoInterfaceClass  the annotated DAO interface this repository serves
     * @param tableName          name of the backing table
     * @param columnMetadataList metadata of every mapped column, in declaration order
     * @throws NullPointerException     if any argument or column metadata entry is {@code null}
     * @throws IllegalArgumentException if a table, column or index name is not a plain SQL identifier
     */
    protected DaoCrud(MySQLManager mysqlManager, Class<T> daoInterfaceClass, String tableName,
                      List<? extends ColumnMetadata> columnMetadataList) {
        this.mysqlManager = Objects.requireNonNull(mysqlManager, "mysqlManager cannot be null");
        this.daoInterfaceClass = Objects.requireNonNull(daoInterfaceClass, "daoInterfaceClass cannot be null");
        this.tableName = requireValidIdentifier(tableName, "table name");
        this.columnMetadataList = List.copyOf(columnMetadataList);

        Map<String, ColumnMetadata> metadataByName = new LinkedHashMap<>();
        ColumnMetadata primaryKey = null;
        for (ColumnMetadata columnMetadata : this.columnMetadataList) {
            validateIdentifiers(columnMetadata);
            metadataByName.put(columnMetadata.columnName, columnMetadata);
            if (columnMetadata.isPrimaryKey) {
                primaryKey = columnMetadata;
            }
        }
        this.columnMetadataByName = Collections.unmodifiableMap(metadataByName);
        this.primaryKeyColumnMetadata = primaryKey;
    }

    /**
     * Validates every identifier a single column contributes to the generated DDL.
     *
     * @param columnMetadata the column to check
     * @throws IllegalArgumentException if one of its identifiers is not a plain SQL identifier
     */
    private static void validateIdentifiers(ColumnMetadata columnMetadata) {
        requireValidIdentifier(columnMetadata.columnName, "column name");
        if (columnMetadata.foreignKeyReferencesTable != null
                && !columnMetadata.foreignKeyReferencesTable.isEmpty()) {
            requireValidIdentifier(columnMetadata.foreignKeyReferencesTable, "foreign key table");
            requireValidIdentifier(columnMetadata.foreignKeyReferencesColumn, "foreign key column");
        }
        for (IndexMetadata indexMetadata : columnMetadata.indexMetadataList) {
            if (!indexMetadata.indexName.isEmpty()) {
                requireValidIdentifier(indexMetadata.indexName, "index name");
            }
        }
    }

    /**
     * Rejects identifiers that could not be interpolated into a statement safely.
     *
     * @param identifier the name to check
     * @param what       the kind of name, for the error message
     * @return the identifier itself, once accepted
     * @throws NullPointerException     if {@code identifier} is {@code null}
     * @throws IllegalArgumentException if it does not match {@link #VALID_IDENTIFIER}
     */
    protected static String requireValidIdentifier(String identifier, String what) {
        Objects.requireNonNull(identifier, what + " cannot be null");
        if (!VALID_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " '" + identifier
                    + "'; it must match " + VALID_IDENTIFIER.pattern());
        }
        return identifier;
    }

    // ================= Table Creation =================

    /**
     * Creates the table asynchronously if it does not exist.
     * This method is idempotent and thread-safe.
     *
     * @return a CompletableFuture that completes when the table is created
     */
    public CompletableFuture<Void> createTableIfNotExistsAsync() {
        if (tableCreated) {
            return CompletableFuture.completedFuture(null);
        }

        return mysqlManager.runAsync(() -> {
            if (tableCreated) return;

            synchronized (this) {
                if (tableCreated) return;

                mysqlManager.executeWithConnection(connection -> {
                    String ddlStatement = generateCreateTableDDL();
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(ddlStatement);
                    }
                    return null;
                });
                tableCreated = true;
            }
        });
    }

    /**
     * Generates CREATE TABLE IF NOT EXISTS statement.
     * <p>
     * Identifiers are interpolated because DDL takes no parameters; all of them were
     * validated when this repository was constructed.
     *
     * @return the DDL creating the backing table with its constraints and indexes
     */
    protected String generateCreateTableDDL() {
        StringBuilder ddlBuilder = new StringBuilder();
        ddlBuilder.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("` (\n");

        List<String> columnDefinitions = new ArrayList<>();
        List<String> constraintDefinitions = new ArrayList<>();

        for (ColumnMetadata columnMetadata : columnMetadataList) {
            columnDefinitions.add("  " + buildColumnDefinitionSql(columnMetadata));

            // Primary key constraint
            if (columnMetadata.isPrimaryKey) {
                constraintDefinitions.add("  PRIMARY KEY (`" + columnMetadata.columnName + "`)");
            }

            // Unique constraint from Column annotation
            if (columnMetadata.isUnique && !columnMetadata.isPrimaryKey) {
                constraintDefinitions.add("  UNIQUE KEY `uk_" + tableName + "_" + columnMetadata.columnName +
                        "` (`" + columnMetadata.columnName + "`)");
            }

            // Foreign key constraint
            if (columnMetadata.foreignKeyReferencesTable != null &&
                    !columnMetadata.foreignKeyReferencesTable.isEmpty()) {
                String foreignKeyName = "fk_" + tableName + "_" + columnMetadata.columnName;
                StringBuilder foreignKeyDefinition = new StringBuilder();
                foreignKeyDefinition.append("  CONSTRAINT `").append(foreignKeyName).append("` FOREIGN KEY (`")
                        .append(columnMetadata.columnName).append("`) REFERENCES `")
                        .append(columnMetadata.foreignKeyReferencesTable).append("` (`")
                        .append(columnMetadata.foreignKeyReferencesColumn).append("`)");
                if (columnMetadata.foreignKeyOnDeleteAction != null &&
                        !columnMetadata.foreignKeyOnDeleteAction.isEmpty()) {
                    foreignKeyDefinition.append(" ON DELETE ").append(columnMetadata.foreignKeyOnDeleteAction);
                }
                if (columnMetadata.foreignKeyOnUpdateAction != null &&
                        !columnMetadata.foreignKeyOnUpdateAction.isEmpty()) {
                    foreignKeyDefinition.append(" ON UPDATE ").append(columnMetadata.foreignKeyOnUpdateAction);
                }
                constraintDefinitions.add(foreignKeyDefinition.toString());
            }

            // Index constraints
            for (IndexMetadata indexMetadata : columnMetadata.indexMetadataList) {
                String indexName = indexMetadata.indexName.isEmpty()
                        ? "idx_" + tableName + "_" + columnMetadata.columnName
                        : indexMetadata.indexName;
                String indexType = indexMetadata.isUnique ? "UNIQUE KEY" : "KEY";
                constraintDefinitions.add("  " + indexType + " `" + indexName + "` (`" +
                        columnMetadata.columnName + "`)");
            }
        }

        ddlBuilder.append(String.join(",\n", columnDefinitions));
        if (!constraintDefinitions.isEmpty()) {
            ddlBuilder.append(",\n").append(String.join(",\n", constraintDefinitions));
        }

        ddlBuilder.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        return ddlBuilder.toString();
    }

    /**
     * Builds the DDL fragment defining a single column, for example
     * {@code `uuid` VARCHAR(36) NOT NULL}.
     * <p>
     * This is the <em>single</em> source of a column's definition text: both
     * {@link #generateCreateTableDDL()} and {@link #migrateAsync()}'s {@code ADD COLUMN}
     * statements go through it, so a column created by migration can never drift from the one
     * {@code CREATE TABLE} would have produced.
     *
     * @param columnMetadata the column to render
     * @return the column definition fragment, without any leading indentation
     */
    protected String buildColumnDefinitionSql(ColumnMetadata columnMetadata) {
        StringBuilder columnDefinition = new StringBuilder();
        columnDefinition.append('`').append(columnMetadata.columnName).append("` ")
                        .append(columnMetadata.sqlTypeDefinition);

        if (!columnMetadata.isNullable) {
            columnDefinition.append(" NOT NULL");
        }

        if (columnMetadata.isAutoIncrement) {
            columnDefinition.append(" AUTO_INCREMENT");
        }

        if (columnMetadata.defaultExpression != null && !columnMetadata.defaultExpression.isEmpty()) {
            columnDefinition.append(" DEFAULT ").append(columnMetadata.defaultExpression);
        }

        return columnDefinition.toString();
    }

    // ================= Additive Schema Migration =================

    /**
     * Query listing the existing columns of this table. The schema and table names are
     * <em>bound parameters</em> — nothing from the runtime environment is interpolated into
     * this statement.
     */
    static final String SELECT_EXISTING_COLUMNS_SQL =
            "SELECT COLUMN_NAME, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                    + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

    /**
     * Diffs the declared columns against the live table and adds the missing ones.
     * <p>
     * The diff reads {@code INFORMATION_SCHEMA.COLUMNS} for this manager's database and this
     * table (both as bound parameters) and emits one
     * {@code ALTER TABLE ... ADD COLUMN ...} per declared-but-absent column, reusing the exact
     * DDL fragment {@code CREATE TABLE} uses ({@link #buildColumnDefinitionSql}). The executed
     * statements are returned, in declaration order; an empty list means the table already
     * matched.
     * <p>
     * <strong>Additive only — everything else is out of scope by design.</strong> A declared
     * column that exists with a <em>different</em> type is logged as a WARNING (naming table,
     * column, declared and actual type) and left untouched; columns present in the table but
     * not declared are ignored; drops, type changes, nullability changes and reordering are
     * never performed. Reconcile those manually.
     * <p>
     * The table must already exist — the future fails with {@link IllegalStateException}
     * otherwise; use {@link #createOrMigrateAsync()} for the combined entry point.
     *
     * @return a future completing with the ALTER statements that were executed
     */
    public CompletableFuture<List<String>> migrateAsync() {
        return mysqlManager.supplyAsync(this::executeMigrate);
    }

    /**
     * Creates the table if missing, then applies the additive migration of
     * {@link #migrateAsync()} — the one call a plugin needs in {@code onEnable} to make the
     * live table match its declared DAO.
     *
     * @return a future that completes when the table exists and every declared column is present
     */
    public CompletableFuture<Void> createOrMigrateAsync() {
        return createTableIfNotExistsAsync()
                .thenCompose(ignored -> migrateAsync())
                .thenApply(statements -> null);
    }

    /**
     * Runs the migration on the database thread: reads the live columns, computes the diff,
     * executes the resulting ALTER statements.
     *
     * @return the executed ALTER statements, in declaration order
     */
    private List<String> executeMigrate() {
        return mysqlManager.executeWithConnection(connection -> {
            Map<String, String> actualColumnTypesByLowerName = new LinkedHashMap<>();
            try (PreparedStatement preparedStatement =
                         connection.prepareStatement(SELECT_EXISTING_COLUMNS_SQL)) {
                preparedStatement.setString(1, mysqlManager.getDatabaseName());
                preparedStatement.setString(2, tableName);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    while (resultSet.next()) {
                        actualColumnTypesByLowerName.put(
                                resultSet.getString(1).toLowerCase(Locale.ROOT),
                                resultSet.getString(2));
                    }
                }
            }

            if (actualColumnTypesByLowerName.isEmpty()) {
                throw new IllegalStateException("Table '" + tableName + "' does not exist in database '"
                        + mysqlManager.getDatabaseName() + "'; call createTableIfNotExistsAsync() first "
                        + "or use createOrMigrateAsync()");
            }

            List<String> alterStatements = computeMigrationStatements(actualColumnTypesByLowerName);
            try (Statement ddlStatement = connection.createStatement()) {
                for (String alterStatement : alterStatements) {
                    ddlStatement.execute(alterStatement);
                }
            }
            return alterStatements;
        });
    }

    /**
     * Pure diff step of {@link #migrateAsync()}, separated from execution so the generated
     * statements can be pinned by tests.
     * <p>
     * MySQL column names are case-insensitive, so the lookup is by lower-cased name.
     *
     * @param actualColumnTypesByLowerName live column types ({@code COLUMN_TYPE}) keyed by
     *                                     lower-cased column name
     * @return the {@code ALTER TABLE ... ADD COLUMN ...} statements to execute, in declaration order
     */
    List<String> computeMigrationStatements(Map<String, String> actualColumnTypesByLowerName) {
        List<String> alterStatements = new ArrayList<>();
        for (ColumnMetadata columnMetadata : columnMetadataList) {
            String actualType = actualColumnTypesByLowerName
                    .get(columnMetadata.columnName.toLowerCase(Locale.ROOT));
            if (actualType == null) {
                alterStatements.add("ALTER TABLE `" + tableName + "` ADD COLUMN "
                        + buildColumnDefinitionSql(columnMetadata));
            } else if (!sqlTypesLookEquivalent(columnMetadata.sqlTypeDefinition, actualType)) {
                mysqlManager.getOwnerPlugin().getLogger().warning(
                        "Column type drift on " + tableName + "." + columnMetadata.columnName
                                + ": declared '" + columnMetadata.sqlTypeDefinition
                                + "' but the table has '" + actualType + "'. migrateAsync() only adds "
                                + "missing columns and never alters an existing one; reconcile this "
                                + "column manually.");
            }
        }
        return alterStatements;
    }

    /**
     * Best-effort equivalence between a declared SQL type and the {@code COLUMN_TYPE} the
     * server reports, used only to decide whether to <em>warn</em> — never to alter.
     * <p>
     * Both sides are lower-cased and {@code BOOLEAN}/{@code BOOL} is normalised to
     * {@code tinyint(1)} (what MySQL actually stores). A parameter the declaration left
     * implicit is tolerated ({@code INT} matches {@code int(11)}), but parameters the
     * declaration spells out must match ({@code VARCHAR(16)} does not match
     * {@code varchar(32)}). A false-positive warning is harmless by construction, since the
     * migration never touches an existing column.
     *
     * @param declaredType the type as declared in the DAO annotation
     * @param actualType   the type as reported by {@code INFORMATION_SCHEMA.COLUMNS.COLUMN_TYPE}
     * @return whether the two look like the same column type
     */
    static boolean sqlTypesLookEquivalent(String declaredType, String actualType) {
        String declared = normalizeSqlType(declaredType);
        String actual = normalizeSqlType(actualType);
        if (declared.equals(actual)) {
            return true;
        }
        if (!baseSqlType(declared).equals(baseSqlType(actual))) {
            return false;
        }
        // Same base type: tolerate a parameter only when the declaration did not spell one out.
        return !declared.contains("(");
    }

    /**
     * Normalises a SQL type for comparison: lower case, collapsed whitespace, and the
     * {@code BOOLEAN} alias folded to the {@code tinyint(1)} MySQL reports for it.
     *
     * @param sqlType the type text to normalise
     * @return the normalised form
     */
    private static String normalizeSqlType(String sqlType) {
        String normalized = sqlType.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.equals("boolean") || normalized.equals("bool")) {
            return "tinyint(1)";
        }
        return normalized;
    }

    /**
     * Strips the parameter list off a normalised SQL type ({@code varchar(36)} → {@code varchar}).
     *
     * @param normalizedType a type already passed through {@link #normalizeSqlType}
     * @return the base type name
     */
    private static String baseSqlType(String normalizedType) {
        int parenthesisIndex = normalizedType.indexOf('(');
        return parenthesisIndex < 0 ? normalizedType : normalizedType.substring(0, parenthesisIndex).trim();
    }

    // ================= Async CRUD Operations =================

    /**
     * Insert a new record asynchronously.
     *
     * @param entity the DAO instance to insert
     * @return a CompletableFuture with the generated key (if auto-increment), or null
     */
    public CompletableFuture<K> insertAsync(T entity) {
        return mysqlManager.supplyAsync(() -> executeInsert(entity));
    }

    /**
     * Update an existing record by primary key asynchronously.
     *
     * @param entity the DAO instance with updated values
     * @return a CompletableFuture with true if a row was updated
     */
    public CompletableFuture<Boolean> updateAsync(T entity) {
        if (primaryKeyColumnMetadata == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Cannot update without a primary key defined"));
        }
        return mysqlManager.supplyAsync(() -> executeUpdate(entity));
    }

    /**
     * Save (insert or update) a record asynchronously.
     * If the primary key is auto-increment and null/0, inserts. Otherwise updates or inserts based on existence.
     *
     * @param entity the DAO instance
     * @return a CompletableFuture with the primary key value
     */
    public CompletableFuture<K> saveAsync(T entity) {
        if (primaryKeyColumnMetadata == null) {
            return insertAsync(entity);
        }

        Object primaryKeyValue = getValueFromEntity(entity, primaryKeyColumnMetadata);
        boolean isNewEntity = primaryKeyValue == null ||
                (primaryKeyValue instanceof Number && ((Number) primaryKeyValue).longValue() == 0);

        if (isNewEntity && primaryKeyColumnMetadata.isAutoIncrement) {
            return insertAsync(entity);
        } else {
            @SuppressWarnings("unchecked")
            K typedPrimaryKey = (K) primaryKeyValue;
            return existsAsync(typedPrimaryKey).thenCompose(exists -> {
                if (exists) {
                    return updateAsync(entity).thenApply(updated -> typedPrimaryKey);
                } else {
                    return insertAsync(entity).thenApply(insertedKey ->
                        insertedKey != null ? insertedKey : typedPrimaryKey);
                }
            });
        }
    }

    /**
     * Delete a record by primary key asynchronously.
     *
     * @param primaryKey the primary key value
     * @return a CompletableFuture with true if a row was deleted
     */
    public CompletableFuture<Boolean> deleteByKeyAsync(K primaryKey) {
        if (primaryKeyColumnMetadata == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Cannot delete by key without a primary key defined"));
        }
        return mysqlManager.supplyAsync(() -> executeDeleteByKey(primaryKey));
    }

    /**
     * Delete a record using the entity's primary key asynchronously.
     *
     * @param entity the DAO instance
     * @return a CompletableFuture with true if a row was deleted
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> deleteAsync(T entity) {
        if (primaryKeyColumnMetadata == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Cannot delete without a primary key defined"));
        }
        K primaryKey = (K) getValueFromEntity(entity, primaryKeyColumnMetadata);
        return deleteByKeyAsync(primaryKey);
    }

    /**
     * Load (find) a record by primary key asynchronously.
     *
     * @param primaryKey the primary key value
     * @return a CompletableFuture with Optional containing the entity, or empty if not found
     */
    public CompletableFuture<Optional<T>> loadAsync(K primaryKey) {
        if (primaryKeyColumnMetadata == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Cannot find by key without a primary key defined"));
        }
        return mysqlManager.supplyAsync(() -> executeFindByKey(primaryKey));
    }

    /**
     * Check if a record exists by primary key asynchronously.
     *
     * @param primaryKey the primary key value
     * @return a CompletableFuture with true if exists
     */
    public CompletableFuture<Boolean> existsAsync(K primaryKey) {
        if (primaryKeyColumnMetadata == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Cannot check existence without a primary key defined"));
        }
        return mysqlManager.supplyAsync(() -> executeExists(primaryKey));
    }

    /**
     * Load all records from the table asynchronously.
     *
     * @return a CompletableFuture with list of all entities
     */
    public CompletableFuture<List<T>> loadAllAsync() {
        return mysqlManager.supplyAsync(this::executeFindAll);
    }

    /**
     * Count all records in the table asynchronously.
     *
     * @return a CompletableFuture with the count, which always completes
     *         with a non-null {@link Long} value (the count is never {@code null})
     */
    public CompletableFuture<Long> countAsync() {
        return mysqlManager.supplyAsync(this::executeCount);
    }

    /**
     * Delete all records in the table asynchronously.
     *
     * @return a CompletableFuture with the number of deleted rows
     */
    public CompletableFuture<Integer> deleteAllAsync() {
        return mysqlManager.supplyAsync(this::executeDeleteAll);
    }

    /**
     * Find records matching a column value asynchronously.
     *
     * @param columnName the column to filter
     * @param value the value to match
     * @return a CompletableFuture with list of matching entities
     */
    public CompletableFuture<List<T>> findByColumnAsync(String columnName, Object value) {
        ColumnMetadata columnMetadata = columnMetadataByName.get(columnName);
        if (columnMetadata == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown column: " + columnName));
        }
        return mysqlManager.supplyAsync(() -> executeFindByColumn(columnName, value, columnMetadata));
    }

    /**
     * Find a single record matching a column value asynchronously.
     *
     * @param columnName the column to filter
     * @param value the value to match
     * @return a CompletableFuture with Optional containing the first match, or empty
     */
    public CompletableFuture<Optional<T>> findOneByColumnAsync(String columnName, Object value) {
        return findByColumnAsync(columnName, value)
            .thenApply(results -> results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst()));
    }

    // ================= DaoQuery Operations =================

    /**
     * Finds the records matching a {@link DaoQuery} asynchronously, honouring its ORDER BY,
     * LIMIT and OFFSET.
     * <p>
     * Every column the query names is resolved against this repository's declared columns and
     * re-validated as a plain identifier; an unknown name fails the future with
     * {@code IllegalArgumentException("Unknown column: ...")}, exactly like
     * {@link #findByColumnAsync}. Every value is bound as a statement parameter. Rows map
     * through the same proxy mapping as every other finder — including its identity-based
     * {@code equals}, so two loads of the same row are never equal.
     *
     * @param query the query to run; {@code DaoQuery.builder().build()} matches every row
     * @return a future with the matching entities, in query order
     */
    public CompletableFuture<List<T>> findWhereAsync(@NotNull DaoQuery query) {
        final CompiledDaoQuery compiled;
        final String sqlStatement;
        try {
            Objects.requireNonNull(query, "query cannot be null");
            compiled = compileQuery(query);
            sqlStatement = buildSelectSql(compiled);
        } catch (RuntimeException invalidQuery) {
            return CompletableFuture.failedFuture(invalidQuery);
        }
        return mysqlManager.supplyAsync(() -> executeFindWhere(sqlStatement, compiled));
    }

    /**
     * Deletes the records matching a {@link DaoQuery} asynchronously and reports how many rows
     * went away.
     * <p>
     * The query <em>must</em> carry at least one predicate — a predicate-free delete fails the
     * future with an {@link IllegalArgumentException} naming {@link #deleteAllAsync()} as the
     * intended method, so "delete everything" is always a deliberate call. MySQL's
     * {@code DELETE ... ORDER BY ... LIMIT} is supported, which makes capped pruning ("delete
     * the 1000 oldest rows") a single statement; {@code OFFSET} is not valid in a MySQL DELETE
     * and is rejected with a clear message.
     *
     * @param query the query selecting the rows to delete
     * @return a future with the number of deleted rows
     */
    public CompletableFuture<Integer> deleteWhereAsync(@NotNull DaoQuery query) {
        final CompiledDaoQuery compiled;
        final String sqlStatement;
        try {
            Objects.requireNonNull(query, "query cannot be null");
            compiled = compileQuery(query);
            sqlStatement = buildDeleteSql(compiled);
        } catch (RuntimeException invalidQuery) {
            return CompletableFuture.failedFuture(invalidQuery);
        }
        return mysqlManager.supplyAsync(() -> executeDeleteWhere(sqlStatement, compiled));
    }

    /**
     * Counts the records matching a {@link DaoQuery} asynchronously.
     * <p>
     * The predicate is optional — an empty query counts the whole table. ORDER BY, LIMIT and
     * OFFSET are deliberately ignored: a single-row aggregate has no use for them, and ignoring
     * them lets the <em>same</em> query instance drive both a page fetch
     * ({@link #findWhereAsync}) and its total count.
     *
     * @param query the query whose predicate to count under
     * @return a future with the matching row count, never {@code null}
     */
    public CompletableFuture<Long> countWhereAsync(@NotNull DaoQuery query) {
        final CompiledDaoQuery compiled;
        final String sqlStatement;
        try {
            Objects.requireNonNull(query, "query cannot be null");
            compiled = compileQuery(query);
            sqlStatement = buildCountSql(compiled);
        } catch (RuntimeException invalidQuery) {
            return CompletableFuture.failedFuture(invalidQuery);
        }
        return mysqlManager.supplyAsync(() -> executeCountWhere(sqlStatement, compiled));
    }

    /**
     * Evaluates one SQL aggregate over a declared column, filtered by a {@link DaoQuery},
     * asynchronously.
     * <p>
     * SQL defines every one of these aggregates over zero rows as {@code NULL} — including
     * {@code SUM}, which is <em>not</em> zero over an empty set — and that is surfaced here as
     * {@link Optional#empty()}, never a silent {@code 0.0}. The column is validated like every
     * other column reference; the result is read as a {@code double}, so exact
     * {@code DECIMAL} semantics do not survive this method — aggregate in Java over
     * {@link #findWhereAsync} when exactness matters. ORDER BY, LIMIT and OFFSET on the query
     * are ignored for the same reason as in {@link #countWhereAsync}. GROUP BY is deliberately
     * out of scope — group in Java until a real need appears.
     *
     * @param function   the aggregate function to apply
     * @param columnName the declared column to aggregate over
     * @param query      the query whose predicate filters the aggregated rows
     * @return a future with the aggregate value, or empty when the row set is empty or the
     *         aggregate is SQL {@code NULL}
     */
    public CompletableFuture<Optional<Double>> aggregateAsync(@NotNull Aggregate function,
                                                              @NotNull String columnName,
                                                              @NotNull DaoQuery query) {
        final CompiledDaoQuery compiled;
        final String sqlStatement;
        try {
            Objects.requireNonNull(function, "function cannot be null");
            Objects.requireNonNull(columnName, "columnName cannot be null");
            Objects.requireNonNull(query, "query cannot be null");
            compiled = compileQuery(query);
            sqlStatement = buildAggregateSql(function, columnName, compiled);
        } catch (RuntimeException invalidQuery) {
            return CompletableFuture.failedFuture(invalidQuery);
        }
        return mysqlManager.supplyAsync(() -> executeAggregate(sqlStatement, compiled));
    }

    // ================= DaoQuery Compilation =================

    /**
     * One value waiting to be bound to a statement parameter, paired with the declared Java
     * type of the column it filters, so {@link #setStatementParameter} binds it exactly like
     * the CRUD paths do.
     *
     * @param value    the value to bind, never {@code null} ({@link DaoQuery} forbids null values)
     * @param javaType the declared Java type of the column the value belongs to
     */
    record BoundParameter(Object value, Class<?> javaType) {
    }

    /**
     * A {@link DaoQuery} translated into SQL clause fragments plus the parameters they bind,
     * with every column name resolved and validated. Package-private so tests can pin the
     * generated SQL without a live database.
     */
    static final class CompiledDaoQuery {

        /** {@code " WHERE ..."} with one {@code ?} per parameter, or empty for no predicate. */
        final String whereClause;

        /** {@code " ORDER BY ..."}, or empty when the query does not sort. */
        final String orderByClause;

        /**
         * {@code " LIMIT n"} or {@code " LIMIT n OFFSET m"}, or empty. The numbers are
         * interpolated rather than bound: both were validated non-negative {@code int}s by
         * {@link DaoQuery.Builder}, and an {@code int} cannot carry SQL.
         */
        final String limitClause;

        /** The predicate parameters, in the order their {@code ?} slots appear. */
        final List<BoundParameter> parameters;

        /** Whether the query carries at least one predicate. */
        final boolean hasPredicate;

        /** Whether the query carries an offset (relevant for DELETE, which rejects it). */
        final boolean hasOffset;

        private CompiledDaoQuery(String whereClause, String orderByClause, String limitClause,
                                 List<BoundParameter> parameters, boolean hasPredicate,
                                 boolean hasOffset) {
            this.whereClause = whereClause;
            this.orderByClause = orderByClause;
            this.limitClause = limitClause;
            this.parameters = parameters;
            this.hasPredicate = hasPredicate;
            this.hasOffset = hasOffset;
        }
    }

    /**
     * Resolves and validates every column a query names and renders the query into SQL
     * fragments with bound parameters.
     *
     * @param query the query to compile
     * @return the compiled clauses and parameters
     * @throws IllegalArgumentException if the query names a column this DAO does not declare
     */
    CompiledDaoQuery compileQuery(DaoQuery query) {
        List<BoundParameter> parameters = new ArrayList<>();
        StringBuilder whereClause = new StringBuilder();
        for (DaoQuery.Condition condition : query.conditions()) {
            ColumnMetadata columnMetadata = requireDeclaredColumn(condition.column());
            whereClause.append(whereClause.isEmpty() ? " WHERE " : " AND ");
            appendConditionSql(whereClause, parameters, condition, columnMetadata);
        }

        StringBuilder orderByClause = new StringBuilder();
        for (DaoQuery.Order order : query.orders()) {
            requireDeclaredColumn(order.column());
            orderByClause.append(orderByClause.isEmpty() ? " ORDER BY " : ", ")
                         .append('`').append(order.column()).append("` ")
                         .append(order.direction().name());
        }

        StringBuilder limitClause = new StringBuilder();
        query.limit().ifPresent(limit -> limitClause.append(" LIMIT ").append(limit));
        query.offset().ifPresent(offset -> limitClause.append(" OFFSET ").append(offset));

        return new CompiledDaoQuery(whereClause.toString(), orderByClause.toString(),
                limitClause.toString(), List.copyOf(parameters), query.hasPredicate(),
                query.offset().isPresent());
    }

    /**
     * Renders one condition into the WHERE clause and queues its parameters.
     *
     * @param sql            the clause being built
     * @param parameters     the parameter list being built
     * @param condition      the condition to render
     * @param columnMetadata the resolved column, source of the binding type
     */
    private static void appendConditionSql(StringBuilder sql, List<BoundParameter> parameters,
                                           DaoQuery.Condition condition,
                                           ColumnMetadata columnMetadata) {
        String quotedColumn = "`" + condition.column() + "`";
        switch (condition.operator()) {
            case EQUALS, NOT_EQUALS, LESS_THAN, LESS_OR_EQUAL, GREATER_THAN, GREATER_OR_EQUAL, LIKE -> {
                sql.append(quotedColumn).append(' ')
                   .append(comparisonOperatorSql(condition.operator())).append(" ?");
                parameters.add(new BoundParameter(condition.values().getFirst(), columnMetadata.javaType));
            }
            case BETWEEN -> {
                sql.append(quotedColumn).append(" BETWEEN ? AND ?");
                parameters.add(new BoundParameter(condition.values().get(0), columnMetadata.javaType));
                parameters.add(new BoundParameter(condition.values().get(1), columnMetadata.javaType));
            }
            case IN -> {
                sql.append(quotedColumn).append(" IN (");
                for (int valueIndex = 0; valueIndex < condition.values().size(); valueIndex++) {
                    sql.append(valueIndex == 0 ? "?" : ", ?");
                    parameters.add(new BoundParameter(condition.values().get(valueIndex),
                            columnMetadata.javaType));
                }
                sql.append(')');
            }
            case IS_NULL -> sql.append(quotedColumn).append(" IS NULL");
            case IS_NOT_NULL -> sql.append(quotedColumn).append(" IS NOT NULL");
        }
    }

    /**
     * Maps a binary comparison operator to its SQL token. The multi-value and no-value
     * operators are rendered by {@link #appendConditionSql} directly and must never reach this.
     *
     * @param operator a binary comparison operator
     * @return the SQL token for it
     */
    private static String comparisonOperatorSql(DaoQuery.Operator operator) {
        return switch (operator) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "<>";
            case LESS_THAN -> "<";
            case LESS_OR_EQUAL -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_OR_EQUAL -> ">=";
            case LIKE -> "LIKE";
            case BETWEEN, IN, IS_NULL, IS_NOT_NULL -> throw new IllegalStateException(
                    operator + " is not a binary comparison operator");
        };
    }

    /**
     * Resolves a caller-supplied column name against the declared columns and re-validates it
     * as a plain identifier right before it is interpolated.
     *
     * @param columnName the column name the query uses
     * @return the resolved column metadata
     * @throws IllegalArgumentException if the DAO does not declare such a column
     */
    private ColumnMetadata requireDeclaredColumn(String columnName) {
        ColumnMetadata columnMetadata = columnMetadataByName.get(columnName);
        if (columnMetadata == null) {
            throw new IllegalArgumentException("Unknown column: " + columnName);
        }
        // Defence in depth: declared names were validated at construction, but this one is about
        // to be interpolated into a statement, so it is checked again at the point of use.
        requireValidIdentifier(columnName, "column name");
        return columnMetadata;
    }

    /**
     * Assembles the SELECT statement for a compiled query.
     *
     * @param compiled the compiled query
     * @return the full statement text
     */
    String buildSelectSql(CompiledDaoQuery compiled) {
        return "SELECT * FROM `" + tableName + "`" + compiled.whereClause
                + compiled.orderByClause + compiled.limitClause;
    }

    /**
     * Assembles the DELETE statement for a compiled query, enforcing the delete-specific rules.
     *
     * @param compiled the compiled query
     * @return the full statement text
     * @throws IllegalArgumentException if the query has no predicate (use
     *                                  {@link #deleteAllAsync()}) or carries an offset, which
     *                                  MySQL DELETE does not support
     */
    String buildDeleteSql(CompiledDaoQuery compiled) {
        if (!compiled.hasPredicate) {
            throw new IllegalArgumentException("deleteWhereAsync requires at least one predicate; "
                    + "call deleteAllAsync() to intentionally delete every row");
        }
        if (compiled.hasOffset) {
            throw new IllegalArgumentException("MySQL DELETE supports LIMIT but not OFFSET; "
                    + "remove offset() from the query");
        }
        return "DELETE FROM `" + tableName + "`" + compiled.whereClause
                + compiled.orderByClause + compiled.limitClause;
    }

    /**
     * Assembles the COUNT statement for a compiled query. ORDER BY, LIMIT and OFFSET are
     * ignored — see {@link #countWhereAsync} for why that is deliberate.
     *
     * @param compiled the compiled query
     * @return the full statement text
     */
    String buildCountSql(CompiledDaoQuery compiled) {
        return "SELECT COUNT(*) FROM `" + tableName + "`" + compiled.whereClause;
    }

    /**
     * Assembles the aggregate statement for a compiled query. The function name comes from a
     * closed enum and the column is resolved against the declared columns, so neither can
     * carry SQL.
     *
     * @param function   the aggregate function
     * @param columnName the declared column to aggregate over
     * @param compiled   the compiled query
     * @return the full statement text
     * @throws IllegalArgumentException if the DAO does not declare the column
     */
    String buildAggregateSql(Aggregate function, String columnName, CompiledDaoQuery compiled) {
        requireDeclaredColumn(columnName);
        return "SELECT " + function.name() + "(`" + columnName + "`) FROM `" + tableName + "`"
                + compiled.whereClause;
    }

    /**
     * Binds every compiled parameter through {@link #setStatementParameter}, in slot order.
     *
     * @param preparedStatement the statement to bind on
     * @param compiled          the compiled query carrying the parameters
     * @throws SQLException if the driver rejects a value
     */
    private void bindCompiledParameters(PreparedStatement preparedStatement,
                                        CompiledDaoQuery compiled) throws SQLException {
        int parameterIndex = 1;
        for (BoundParameter parameter : compiled.parameters) {
            setStatementParameter(preparedStatement, parameterIndex++,
                    parameter.value(), parameter.javaType());
        }
    }

    // ================= Internal Execution Methods (run on async thread) =================

    private K executeInsert(T entity) {
        return mysqlManager.executeWithConnection(connection -> {
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("INSERT INTO `").append(tableName).append("` (");

            List<ColumnMetadata> columnsToInsert = new ArrayList<>();
            for (ColumnMetadata columnMetadata : columnMetadataList) {
                if (!columnMetadata.isAutoIncrement) {
                    columnsToInsert.add(columnMetadata);
                }
            }

            sqlBuilder.append(String.join(", ", columnsToInsert.stream()
                    .map(column -> "`" + column.columnName + "`")
                    .toArray(String[]::new)));
            sqlBuilder.append(") VALUES (");
            sqlBuilder.append(String.join(", ", columnsToInsert.stream()
                    .map(column -> "?")
                    .toArray(String[]::new)));
            sqlBuilder.append(")");

            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlBuilder.toString(),
                    primaryKeyColumnMetadata != null && primaryKeyColumnMetadata.isAutoIncrement
                            ? Statement.RETURN_GENERATED_KEYS
                            : Statement.NO_GENERATED_KEYS)) {

                int parameterIndex = 1;
                for (ColumnMetadata columnMetadata : columnsToInsert) {
                    Object value = getValueFromEntity(entity, columnMetadata);
                    setStatementParameter(preparedStatement, parameterIndex++, value, columnMetadata.javaType);
                }

                preparedStatement.executeUpdate();

                if (primaryKeyColumnMetadata != null && primaryKeyColumnMetadata.isAutoIncrement) {
                    try (ResultSet generatedKeys = preparedStatement.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            @SuppressWarnings("unchecked")
                            K generatedKey = (K) getResultSetValue(generatedKeys, 1,
                                    primaryKeyColumnMetadata.javaType);
                            return generatedKey;
                        }
                    }
                }
                return null;
            }
        });
    }

    private boolean executeUpdate(T entity) {
        return mysqlManager.executeWithConnection(connection -> {
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("UPDATE `").append(tableName).append("` SET ");

            List<ColumnMetadata> columnsToUpdate = new ArrayList<>();
            for (ColumnMetadata columnMetadata : columnMetadataList) {
                if (!columnMetadata.isPrimaryKey) {
                    columnsToUpdate.add(columnMetadata);
                }
            }

            sqlBuilder.append(String.join(", ", columnsToUpdate.stream()
                    .map(column -> "`" + column.columnName + "` = ?")
                    .toArray(String[]::new)));
            sqlBuilder.append(" WHERE `").append(primaryKeyColumnMetadata.columnName).append("` = ?");

            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlBuilder.toString())) {
                int parameterIndex = 1;
                for (ColumnMetadata columnMetadata : columnsToUpdate) {
                    Object value = getValueFromEntity(entity, columnMetadata);
                    setStatementParameter(preparedStatement, parameterIndex++, value, columnMetadata.javaType);
                }

                Object primaryKeyValue = getValueFromEntity(entity, primaryKeyColumnMetadata);
                setStatementParameter(preparedStatement, parameterIndex, primaryKeyValue,
                        primaryKeyColumnMetadata.javaType);

                return preparedStatement.executeUpdate() > 0;
            }
        });
    }

    private boolean executeDeleteByKey(K primaryKey) {
        return mysqlManager.executeWithConnection(connection -> {
            String sqlStatement = "DELETE FROM `" + tableName + "` WHERE `" +
                    primaryKeyColumnMetadata.columnName + "` = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                setStatementParameter(preparedStatement, 1, primaryKey, primaryKeyColumnMetadata.javaType);
                return preparedStatement.executeUpdate() > 0;
            }
        });
    }

    private Optional<T> executeFindByKey(K primaryKey) {
        return mysqlManager.executeWithConnection(connection -> {
            String sqlStatement = "SELECT * FROM `" + tableName + "` WHERE `" +
                    primaryKeyColumnMetadata.columnName + "` = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                setStatementParameter(preparedStatement, 1, primaryKey, primaryKeyColumnMetadata.javaType);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(mapResultSetToEntity(resultSet));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    private boolean executeExists(K primaryKey) {
        return mysqlManager.executeWithConnection(connection -> {
            String sqlStatement = "SELECT 1 FROM `" + tableName + "` WHERE `" +
                    primaryKeyColumnMetadata.columnName + "` = ? LIMIT 1";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                setStatementParameter(preparedStatement, 1, primaryKey, primaryKeyColumnMetadata.javaType);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    private List<T> executeFindAll() {
        return mysqlManager.executeWithConnection(connection -> {
            String sqlStatement = "SELECT * FROM `" + tableName + "`";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement);
                 ResultSet resultSet = preparedStatement.executeQuery()) {
                List<T> resultList = new ArrayList<>();
                while (resultSet.next()) {
                    resultList.add(mapResultSetToEntity(resultSet));
                }
                return resultList;
            }
        });
    }

    private long executeCount() {
        return mysqlManager.executeWithConnection(connection -> {
            String sqlStatement = "SELECT COUNT(*) FROM `" + tableName + "`";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement);
                 ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
                return 0L;
            }
        });
    }

    private int executeDeleteAll() {
        return mysqlManager.executeWithConnection(connection -> {
            String sqlStatement = "DELETE FROM `" + tableName + "`";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                return preparedStatement.executeUpdate();
            }
        });
    }

    private List<T> executeFindByColumn(String columnName, Object value, ColumnMetadata columnMetadata) {
        return mysqlManager.executeWithConnection(connection -> {
            String sqlStatement = "SELECT * FROM `" + tableName + "` WHERE `" + columnName + "` = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                setStatementParameter(preparedStatement, 1, value, columnMetadata.javaType);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    List<T> resultList = new ArrayList<>();
                    while (resultSet.next()) {
                        resultList.add(mapResultSetToEntity(resultSet));
                    }
                    return resultList;
                }
            }
        });
    }

    private List<T> executeFindWhere(String sqlStatement, CompiledDaoQuery compiled) {
        return mysqlManager.executeWithConnection(connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                bindCompiledParameters(preparedStatement, compiled);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    List<T> resultList = new ArrayList<>();
                    while (resultSet.next()) {
                        resultList.add(mapResultSetToEntity(resultSet));
                    }
                    return resultList;
                }
            }
        });
    }

    private int executeDeleteWhere(String sqlStatement, CompiledDaoQuery compiled) {
        return mysqlManager.executeWithConnection(connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                bindCompiledParameters(preparedStatement, compiled);
                return preparedStatement.executeUpdate();
            }
        });
    }

    private long executeCountWhere(String sqlStatement, CompiledDaoQuery compiled) {
        return mysqlManager.executeWithConnection(connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                bindCompiledParameters(preparedStatement, compiled);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong(1) : 0L;
                }
            }
        });
    }

    private Optional<Double> executeAggregate(String sqlStatement, CompiledDaoQuery compiled) {
        return mysqlManager.executeWithConnection(connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlStatement)) {
                bindCompiledParameters(preparedStatement, compiled);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    double aggregateValue = resultSet.getDouble(1);
                    if (resultSet.wasNull()) {
                        // SQL aggregates over zero rows are NULL — surfaced as empty, never 0.0.
                        return Optional.empty();
                    }
                    return Optional.of(aggregateValue);
                }
            }
        });
    }

    // ================= Entity Mapping =================

    /**
     * Maps a ResultSet row to a DAO proxy instance.
     *
     * @param resultSet a result set positioned on the row to map
     * @return a DAO instance backed by the values of that row
     * @throws SQLException if a column cannot be read
     */
    protected T mapResultSetToEntity(ResultSet resultSet) throws SQLException {
        Map<String, Object> valuesByGetterName = new LinkedHashMap<>();

        for (ColumnMetadata columnMetadata : columnMetadataList) {
            Object value = getResultSetValue(resultSet, columnMetadata.columnName, columnMetadata.javaType);
            valuesByGetterName.put(columnMetadata.getterMethodName, value);
        }

        return createEntityProxy(valuesByGetterName);
    }

    /**
     * Creates a proxy implementing the DAO interface with the given values.
     *
     * @param valuesByGetterName column values keyed by the name of the getter returning them
     * @return a DAO instance serving those values
     */
    @SuppressWarnings("unchecked")
    protected T createEntityProxy(Map<String, Object> valuesByGetterName) {
        return (T) Proxy.newProxyInstance(
                daoInterfaceClass.getClassLoader(),
                new Class<?>[] { daoInterfaceClass },
                new DaoInvocationHandler(valuesByGetterName)
        );
    }

    /**
     * Creates a mutable builder for the DAO.
     *
     * @return a builder for creating DAO instances
     */
    public EntityBuilder<T> builder() {
        return new EntityBuilder<>(this);
    }

    /**
     * Builder for creating DAO instances.
     *
     * @param <T> the DAO interface type being built
     */
    public static class EntityBuilder<T> {
        private final DaoCrud<T, ?> repositoryCrud;
        private final Map<String, Object> valuesByGetterName = new LinkedHashMap<>();

        EntityBuilder(DaoCrud<T, ?> repositoryCrud) {
            this.repositoryCrud = repositoryCrud;
        }

        /**
         * Set a column value by getter method name.
         *
         * @param getterMethodName name of the DAO getter the value belongs to
         * @param value            the value to serve from that getter
         * @return this builder, for chaining
         */
        public EntityBuilder<T> set(String getterMethodName, Object value) {
            valuesByGetterName.put(getterMethodName, value);
            return this;
        }

        /**
         * Build the DAO instance.
         *
         * @return a DAO instance serving the values collected so far
         */
        public T build() {
            return repositoryCrud.createEntityProxy(new LinkedHashMap<>(valuesByGetterName));
        }
    }

    /**
     * InvocationHandler for DAO proxies.
     */
    private class DaoInvocationHandler implements InvocationHandler {
        private final Map<String, Object> valuesByGetterName;

        DaoInvocationHandler(Map<String, Object> valuesByGetterName) {
            this.valuesByGetterName = new LinkedHashMap<>(valuesByGetterName);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String methodName = method.getName();

            // Handle Object methods
            if ("equals".equals(methodName) && arguments != null && arguments.length == 1) {
                return proxy == arguments[0];
            }
            if ("hashCode".equals(methodName)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(methodName)) {
                return daoInterfaceClass.getSimpleName() + valuesByGetterName;
            }

            // Handle getter
            if (valuesByGetterName.containsKey(methodName)) {
                return valuesByGetterName.get(methodName);
            }

            // Handle setter (setXxx)
            if (methodName.startsWith("set") && arguments != null && arguments.length == 1) {
                String getterMethodName = "get" + methodName.substring(3);
                valuesByGetterName.put(getterMethodName, arguments[0]);
                return null;
            }

            return null;
        }
    }

    // ================= JDBC Utility Methods =================

    /**
     * Gets a value from the DAO entity using the column's getter.
     *
     * @param entity         the DAO instance to read
     * @param columnMetadata the column whose getter to invoke
     * @return the value held by the entity for that column, possibly {@code null}
     */
    protected Object getValueFromEntity(T entity, ColumnMetadata columnMetadata) {
        try {
            return columnMetadata.getterMethod.invoke(entity);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to get value for column " + columnMetadata.columnName, exception);
        }
    }

    /**
     * Sets a parameter on a PreparedStatement, mapping the value to the closest JDBC type
     * and falling back to a JSON string for anything exotic.
     * <p>
     * Native bindings exist for every Java type the DDL inference has a sensible column type
     * for: the numeric primitives and their boxes ({@code short} → SMALLINT, {@code byte} →
     * TINYINT), {@code char}/{@link Character} as a single-character string (DDL: CHAR(1)),
     * {@link BigDecimal} via {@code setBigDecimal} — the money type, where decimal exactness
     * must survive the round trip (note the DDL inference has no DECIMAL default; declare
     * {@code sqlType = "DECIMAL(p,s)"} explicitly, since a {@code DOUBLE} column would
     * round-trip through binary floating point) — and {@link LocalTime} through the JDBC 4.2
     * {@code setObject} mapping (DDL: TIME). None of these may ever reach the JSON fallback.
     *
     * @param preparedStatement the statement to bind on
     * @param parameterIndex    the one-based index of the parameter
     * @param value             the value to bind, may be {@code null}
     * @param javaType          the declared Java type of the column
     * @throws SQLException if the driver rejects the value
     */
    protected void setStatementParameter(PreparedStatement preparedStatement, int parameterIndex,
                                         Object value, Class<?> javaType) throws SQLException {
        if (value == null) {
            preparedStatement.setNull(parameterIndex, Types.NULL);
            return;
        }

        if (value instanceof String stringValue) {
            preparedStatement.setString(parameterIndex, stringValue);
        } else if (value instanceof Integer integerValue) {
            preparedStatement.setInt(parameterIndex, integerValue);
        } else if (value instanceof Long longValue) {
            preparedStatement.setLong(parameterIndex, longValue);
        } else if (value instanceof Short shortValue) {
            preparedStatement.setShort(parameterIndex, shortValue);
        } else if (value instanceof Byte byteValue) {
            preparedStatement.setByte(parameterIndex, byteValue);
        } else if (value instanceof Double doubleValue) {
            preparedStatement.setDouble(parameterIndex, doubleValue);
        } else if (value instanceof Float floatValue) {
            preparedStatement.setFloat(parameterIndex, floatValue);
        } else if (value instanceof BigDecimal bigDecimalValue) {
            preparedStatement.setBigDecimal(parameterIndex, bigDecimalValue);
        } else if (value instanceof Boolean booleanValue) {
            preparedStatement.setBoolean(parameterIndex, booleanValue);
        } else if (value instanceof Character characterValue) {
            // The DDL inference maps char/Character to CHAR(1); a one-character string is the
            // JDBC representation of that.
            preparedStatement.setString(parameterIndex, characterValue.toString());
        } else if (value instanceof UUID uuidValue) {
            preparedStatement.setString(parameterIndex, uuidValue.toString());
        } else if (value instanceof java.util.Date dateValue) {
            preparedStatement.setTimestamp(parameterIndex, new Timestamp(dateValue.getTime()));
        } else if (value instanceof Instant instantValue) {
            preparedStatement.setTimestamp(parameterIndex, Timestamp.from(instantValue));
        } else if (value instanceof LocalDateTime localDateTimeValue) {
            preparedStatement.setTimestamp(parameterIndex, Timestamp.valueOf(localDateTimeValue));
        } else if (value instanceof LocalDate localDateValue) {
            preparedStatement.setDate(parameterIndex, java.sql.Date.valueOf(localDateValue));
        } else if (value instanceof LocalTime localTimeValue) {
            // JDBC 4.2 maps java.time.LocalTime to TIME through setObject.
            preparedStatement.setObject(parameterIndex, localTimeValue);
        } else if (value instanceof byte[] bytesValue) {
            preparedStatement.setBytes(parameterIndex, bytesValue);
        } else if (value instanceof Enum<?> enumValue) {
            preparedStatement.setString(parameterIndex, enumValue.name());
        } else {
            // Fallback: serialize as JSON
            preparedStatement.setString(parameterIndex, mysqlManager.gson.toJson(value));
        }
    }

    /**
     * Gets a value from a ResultSet by column name.
     *
     * @param resultSet  the result set positioned on the row to read
     * @param columnName the column to read
     * @param javaType   the declared Java type of the column
     * @return the converted value, or {@code null} when the column is SQL {@code NULL}
     * @throws SQLException if the column cannot be read
     */
    protected Object getResultSetValue(ResultSet resultSet, String columnName,
                                       Class<?> javaType) throws SQLException {
        return getResultSetValueInternal(resultSet, columnName, javaType);
    }

    /**
     * Gets a value from a ResultSet by column index.
     *
     * @param resultSet   the result set positioned on the row to read
     * @param columnIndex the one-based index of the column to read
     * @param javaType    the declared Java type of the column
     * @return the converted value, or {@code null} when the column is SQL {@code NULL}
     * @throws SQLException if the column cannot be read
     */
    protected Object getResultSetValue(ResultSet resultSet, int columnIndex,
                                       Class<?> javaType) throws SQLException {
        return getResultSetValueInternal(resultSet, columnIndex, javaType);
    }

    private Object getResultSetValueInternal(ResultSet resultSet, Object columnReference,
                                             Class<?> javaType) throws SQLException {
        if (javaType == String.class) {
            return columnReference instanceof String
                    ? resultSet.getString((String) columnReference)
                    : resultSet.getString((Integer) columnReference);
        } else if (javaType == int.class || javaType == Integer.class) {
            int value = columnReference instanceof String
                    ? resultSet.getInt((String) columnReference)
                    : resultSet.getInt((Integer) columnReference);
            return resultSet.wasNull() ? null : value;
        } else if (javaType == long.class || javaType == Long.class) {
            long value = columnReference instanceof String
                    ? resultSet.getLong((String) columnReference)
                    : resultSet.getLong((Integer) columnReference);
            return resultSet.wasNull() ? null : value;
        } else if (javaType == short.class || javaType == Short.class) {
            short value = columnReference instanceof String
                    ? resultSet.getShort((String) columnReference)
                    : resultSet.getShort((Integer) columnReference);
            return resultSet.wasNull() ? null : value;
        } else if (javaType == byte.class || javaType == Byte.class) {
            byte value = columnReference instanceof String
                    ? resultSet.getByte((String) columnReference)
                    : resultSet.getByte((Integer) columnReference);
            return resultSet.wasNull() ? null : value;
        } else if (javaType == char.class || javaType == Character.class) {
            String stringValue = columnReference instanceof String
                    ? resultSet.getString((String) columnReference)
                    : resultSet.getString((Integer) columnReference);
            return stringValue == null || stringValue.isEmpty() ? null : stringValue.charAt(0);
        } else if (javaType == BigDecimal.class) {
            return columnReference instanceof String
                    ? resultSet.getBigDecimal((String) columnReference)
                    : resultSet.getBigDecimal((Integer) columnReference);
        } else if (javaType == double.class || javaType == Double.class) {
            double value = columnReference instanceof String
                    ? resultSet.getDouble((String) columnReference)
                    : resultSet.getDouble((Integer) columnReference);
            return resultSet.wasNull() ? null : value;
        } else if (javaType == float.class || javaType == Float.class) {
            float value = columnReference instanceof String
                    ? resultSet.getFloat((String) columnReference)
                    : resultSet.getFloat((Integer) columnReference);
            return resultSet.wasNull() ? null : value;
        } else if (javaType == boolean.class || javaType == Boolean.class) {
            boolean value = columnReference instanceof String
                    ? resultSet.getBoolean((String) columnReference)
                    : resultSet.getBoolean((Integer) columnReference);
            return resultSet.wasNull() ? null : value;
        } else if (javaType == UUID.class) {
            String stringValue = columnReference instanceof String
                    ? resultSet.getString((String) columnReference)
                    : resultSet.getString((Integer) columnReference);
            return stringValue == null ? null : UUID.fromString(stringValue);
        } else if (javaType == java.util.Date.class) {
            Timestamp timestampValue = columnReference instanceof String
                    ? resultSet.getTimestamp((String) columnReference)
                    : resultSet.getTimestamp((Integer) columnReference);
            return timestampValue == null ? null : new java.util.Date(timestampValue.getTime());
        } else if (javaType == Instant.class) {
            Timestamp timestampValue = columnReference instanceof String
                    ? resultSet.getTimestamp((String) columnReference)
                    : resultSet.getTimestamp((Integer) columnReference);
            return timestampValue == null ? null : timestampValue.toInstant();
        } else if (javaType == LocalDateTime.class) {
            Timestamp timestampValue = columnReference instanceof String
                    ? resultSet.getTimestamp((String) columnReference)
                    : resultSet.getTimestamp((Integer) columnReference);
            return timestampValue == null ? null : timestampValue.toLocalDateTime();
        } else if (javaType == LocalDate.class) {
            java.sql.Date dateValue = columnReference instanceof String
                    ? resultSet.getDate((String) columnReference)
                    : resultSet.getDate((Integer) columnReference);
            return dateValue == null ? null : dateValue.toLocalDate();
        } else if (javaType == LocalTime.class) {
            // JDBC 4.2 maps TIME to java.time.LocalTime through getObject.
            return columnReference instanceof String
                    ? resultSet.getObject((String) columnReference, LocalTime.class)
                    : resultSet.getObject((Integer) columnReference, LocalTime.class);
        } else if (javaType == byte[].class) {
            return columnReference instanceof String
                    ? resultSet.getBytes((String) columnReference)
                    : resultSet.getBytes((Integer) columnReference);
        } else if (javaType.isEnum()) {
            String stringValue = columnReference instanceof String
                    ? resultSet.getString((String) columnReference)
                    : resultSet.getString((Integer) columnReference);
            if (stringValue == null) return null;
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<Enum>) javaType, stringValue);
            return enumValue;
        } else {
            // Fallback: deserialize from JSON
            String jsonValue = columnReference instanceof String
                    ? resultSet.getString((String) columnReference)
                    : resultSet.getString((Integer) columnReference);
            return jsonValue == null ? null : mysqlManager.gson.fromJson(jsonValue, javaType);
        }
    }

    // ================= Accessors =================

    /**
     * Gets the name of the table this repository reads and writes.
     *
     * @return the validated table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Gets the DAO interface the mapped entities implement.
     *
     * @return the DAO interface type
     */
    public Class<T> getDaoInterfaceClass() {
        return daoInterfaceClass;
    }

    /**
     * Gets the metadata of every mapped column, in declaration order.
     *
     * @return an immutable list of column metadata
     */
    public List<ColumnMetadata> getColumnMetadataList() {
        return columnMetadataList;
    }

    /**
     * Gets the metadata of the primary key column.
     *
     * @return the primary key metadata, or {@code null} when the DAO declares none
     */
    public ColumnMetadata getPrimaryKeyColumnMetadata() {
        return primaryKeyColumnMetadata;
    }

    /**
     * Gets the connection manager every statement of this repository runs through.
     *
     * @return the owning MySQL manager
     */
    public MySQLManager getMysqlManager() {
        return mysqlManager;
    }
}
