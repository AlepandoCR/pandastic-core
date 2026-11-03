package pandastic.studio.core.processor.database;

import com.google.auto.service.AutoService;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Build-time generator for DAO interfaces annotated with {@code @Table}.
 * <p>
 * For every annotated interface the processor emits two sources next to it:
 * <ul>
 *   <li>{@code <Dao>Impl} - a mutable implementation with an all-args constructor, fluent setters
 *       and a builder, plus identity semantics based on the primary key;</li>
 *   <li>{@code <Dao>Repository} - a {@code DaoCrud} subclass carrying the column metadata derived
 *       from the annotations, together with asynchronous finders for every indexed column.</li>
 * </ul>
 * <p>
 * Generated declarations always use fully qualified type names, which keeps the output immune to
 * simple name collisions between the DAO package and the types its getters return.
 */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class TableProcessor extends AbstractProcessor {

    // ---------------------------------------------------------------------------------------------
    // Fully qualified names. Single source of truth.
    //
    // This module declares no dependency on pandastic-core-database on purpose: that module applies this
    // processor, so a compile dependency back onto it would close a Gradle task cycle. The
    // annotation types are therefore unavailable as classes and every name below has to stay a
    // literal, resolved at processing time through the javax.lang.model mirror API.
    //
    // The runtime names in the second group are only ever written into generated imports; the
    // processor never loads those classes.
    //
    // Declare each name exactly once here and derive everything else - the supported annotation
    // set, the per-annotation lookups, the generated imports - from these constants.
    // ---------------------------------------------------------------------------------------------

    /** Package holding the DAO mapping annotations. */
    private static final String ANNOTATIONS_PKG = "pandastic.studio.core.database.api.annotations";

    /** Declares the physical table name of a DAO interface. */
    private static final String TABLE_ANNOTATION = ANNOTATIONS_PKG + ".Table";

    /** Declares one column, placed on a no-arg getter. */
    private static final String COLUMN_ANNOTATION = ANNOTATIONS_PKG + ".Column";

    /** Marks a column as the primary key. */
    private static final String PRIMARY_KEY_ANNOTATION = ANNOTATIONS_PKG + ".PrimaryKey";

    /** Declares a foreign key constraint on a column. */
    private static final String FOREIGN_KEY_ANNOTATION = ANNOTATIONS_PKG + ".ForeignKey";

    /** Declares a single index on a column. */
    private static final String INDEX_ANNOTATION = ANNOTATIONS_PKG + ".Index";

    /** Container of the repeatable index annotation. */
    private static final String INDEXES_ANNOTATION = ANNOTATIONS_PKG + ".Indexes";

    /** Name of the {@code SqlType} constant selecting a free-form SQL type. */
    private static final String SQL_TYPE_CUSTOM = "CUSTOM";

    /** Name of the {@code SqlType} constant for a variable length string. */
    private static final String SQL_TYPE_VARCHAR = "VARCHAR";

    /** Name of the {@code SqlType} constant for a fixed length string. */
    private static final String SQL_TYPE_CHAR = "CHAR";

    /** JDBC base class every generated repository extends. Written into generated imports. */
    private static final String DAO_CRUD_CLASS = "pandastic.studio.core.database.internal.DaoCrud";

    /** Connection manager every generated repository is constructed with. Written into imports. */
    private static final String MYSQL_MANAGER_CLASS = "pandastic.studio.core.database.internal.MySQLManager";

    /** Read-only credential view a {@code MySQLManager} is handed. Referenced from generated Javadoc. */
    private static final String DATABASE_SETTINGS_CLASS = "pandastic.studio.core.service.api.config.defaults.DatabaseSettings";

    /** Suffix of the generated implementation class. */
    private static final String IMPL_SUFFIX = "Impl";

    /** Suffix of the generated repository class. */
    private static final String REPOSITORY_SUFFIX = "Repository";

    /** Trailing DAO naming noise stripped when deriving a table name from a type name. */
    private static final String DAO_NAME_SUFFIX_PATTERN = "(Table)?Dao$";

    private Types typeUtils;
    private Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        this.typeUtils = processingEnvironment.getTypeUtils();
        this.elementUtils = processingEnvironment.getElementUtils();
    }

    /**
     * Declares the annotations this processor claims.
     * <p>
     * Overriding the method instead of using {@code @SupportedAnnotationTypes} keeps
     * {@link #TABLE_ANNOTATION} the single place the name is spelled out; the annotation only
     * accepts literals, which would duplicate it.
     *
     * @return the single supported annotation name
     */
    @Contract(value = " -> new", pure = true)
    @Override
    public @NotNull @Unmodifiable Set<String> getSupportedAnnotationTypes() {
        return Set.of(TABLE_ANNOTATION);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, @NotNull RoundEnvironment roundEnvironment) {
        if (roundEnvironment.processingOver()) {
            return false;
        }

        boolean claimed = false;
        for (TypeElement annotation : annotations) {
            if (!annotation.getQualifiedName().contentEquals(TABLE_ANNOTATION)) {
                continue;
            }
            claimed = true;

            for (Element element : roundEnvironment.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != ElementKind.INTERFACE) {
                    error(element, "@Table can only be applied to interfaces.");
                    continue;
                }

                TypeElement interfaceElement = (TypeElement) element;
                try {
                    processTableInterface(interfaceElement);
                } catch (IOException exception) {
                    error(element, "Failed to generate sources for @Table "
                            + interfaceElement.getQualifiedName() + ": " + exception);
                }
            }
        }
        return claimed;
    }

    /**
     * Generates the implementation and the repository of a single DAO interface.
     *
     * @param interfaceElement the annotated interface
     * @throws IOException when a source file cannot be written
     */
    private void processTableInterface(TypeElement interfaceElement) throws IOException {
        String tableName = resolveTableName(interfaceElement);
        String packageName = this.elementUtils.getPackageOf(interfaceElement).getQualifiedName().toString();
        String interfaceSimpleName = interfaceElement.getSimpleName().toString();
        List<ColumnSpec> columns = collectColumns(interfaceElement);

        if (columns.isEmpty()) {
            error(interfaceElement, "@Table interface " + interfaceSimpleName
                    + " declares no @Column methods, so no table could be mapped.");
            return;
        }

        generateImplementation(packageName, interfaceSimpleName, interfaceElement, columns);
        generateRepository(packageName, interfaceSimpleName, interfaceElement, tableName, columns);
    }

    // =============================================================================================
    // Collection
    // =============================================================================================

    /**
     * Collects every column annotated method of an interface and its super interfaces.
     * <p>
     * Super interfaces are visited first, so a subinterface redeclaring a getter wins.
     *
     * @param interfaceElement the DAO interface
     * @return the columns in declaration order
     */
    private @NotNull @Unmodifiable List<ColumnSpec> collectColumns(TypeElement interfaceElement) {
        Map<String, ColumnSpec> columnsByMethod = new LinkedHashMap<>();
        collectColumnsRecursive(interfaceElement, columnsByMethod);
        return List.copyOf(columnsByMethod.values());
    }

    /**
     * Recursive worker of {@link #collectColumns(TypeElement)}.
     *
     * @param interfaceElement the interface to scan
     * @param columns          accumulator keyed by getter name
     */
    private void collectColumnsRecursive(@NotNull TypeElement interfaceElement, Map<String, ColumnSpec> columns) {
        for (TypeMirror superInterface : interfaceElement.getInterfaces()) {
            if (superInterface instanceof DeclaredType declaredType
                    && declaredType.asElement() instanceof TypeElement superElement) {
                collectColumnsRecursive(superElement, columns);
            }
        }

        for (Element enclosed : interfaceElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }

            ExecutableElement method = (ExecutableElement) enclosed;
            AnnotationMirror columnAnnotation = annotationMirror(method, COLUMN_ANNOTATION);
            if (columnAnnotation == null) {
                continue;
            }
            if (!method.getParameters().isEmpty()) {
                error(method, "@Column must be placed on a no-arg getter.");
                continue;
            }

            columns.put(method.getSimpleName().toString(), extractColumnSpec(method, columnAnnotation));
        }
    }

    /**
     * Reads every annotation on a getter into a {@link ColumnSpec}.
     *
     * @param method           the annotated getter
     * @param columnAnnotation the column mirror of that getter
     * @return the resolved column
     */
    @Contract("_, _ -> new")
    private @NotNull ColumnSpec extractColumnSpec(@NotNull ExecutableElement method, AnnotationMirror columnAnnotation) {
        String methodName = method.getSimpleName().toString();
        TypeMirror returnType = method.getReturnType();

        String columnName = annotationString(columnAnnotation, "name", "");
        if (columnName.isEmpty()) {
            columnName = camelToSnake(stripAccessorPrefix(methodName));
        }

        String sqlType = resolveSqlType(
                annotationEnumConstant(columnAnnotation, "type"),
                annotationString(columnAnnotation, "sqlType", ""),
                annotationInt(columnAnnotation, "length", -1),
                returnType);

        AnnotationMirror primaryKeyAnnotation = annotationMirror(method, PRIMARY_KEY_ANNOTATION);
        boolean primaryKey = primaryKeyAnnotation != null;

        return new ColumnSpec(
                methodName,
                returnType,
                this.typeUtils.erasure(returnType),
                columnName,
                sqlType,
                annotationBoolean(columnAnnotation, "nullable", true),
                annotationBoolean(columnAnnotation, "unique", false),
                annotationString(columnAnnotation, "defaultExpr", ""),
                primaryKey,
                primaryKey && annotationBoolean(primaryKeyAnnotation, "autoIncrement", false),
                extractForeignKey(method),
                extractIndexes(method));
    }

    /**
     * Reads the optional foreign key annotation of a getter.
     *
     * @param method the annotated getter
     * @return the foreign key, or {@code null} when the getter declares none
     */
    private @Nullable ForeignKeySpec extractForeignKey(ExecutableElement method) {
        AnnotationMirror foreignKeyAnnotation = annotationMirror(method, FOREIGN_KEY_ANNOTATION);
        if (foreignKeyAnnotation == null) {
            return null;
        }

        TypeMirror referencedDao = annotationValue(foreignKeyAnnotation, "referencesDao", TypeMirror.class);
        String referencedTable = "";
        if (referencedDao instanceof DeclaredType declaredType
                && declaredType.asElement() instanceof TypeElement daoElement) {
            referencedTable = resolveTableName(daoElement);
        } else {
            error(method, "@ForeignKey#referencesDao must point at a DAO interface.");
        }

        return new ForeignKeySpec(
                referencedTable,
                annotationString(foreignKeyAnnotation, "referencesColumn", "id"),
                sqlAction(annotationEnumConstant(foreignKeyAnnotation, "onDelete")),
                sqlAction(annotationEnumConstant(foreignKeyAnnotation, "onUpdate")));
    }

    /**
     * Reads the repeatable index annotations of a getter, in both their single and their container
     * form.
     *
     * @param method the annotated getter
     * @return the declared indexes, possibly empty
     */
    private @NotNull @Unmodifiable List<IndexSpec> extractIndexes(ExecutableElement method) {
        List<IndexSpec> indexes = new ArrayList<>();

        AnnotationMirror single = annotationMirror(method, INDEX_ANNOTATION);
        if (single != null) {
            indexes.add(new IndexSpec(annotationString(single, "name", ""),
                    annotationBoolean(single, "unique", false)));
        }

        AnnotationMirror container = annotationMirror(method, INDEXES_ANNOTATION);
        if (container != null) {
            for (AnnotationMirror nested : annotationMirrorArray(container, "value")) {
                indexes.add(new IndexSpec(annotationString(nested, "name", ""),
                        annotationBoolean(nested, "unique", false)));
            }
        }

        return List.copyOf(indexes);
    }

    /**
     * Resolves the SQL type of column.
     * <p>
     * An explicit SQL type wins, optionally parameterized with a length for {@code VARCHAR} and
     * {@code CHAR}. When {@code CUSTOM} is paired with a free-form type that string is used
     * verbatim. Otherwise, the type is inferred from the Java return type.
     *
     * @param declaredType  the declared SQL type constant name, or {@code null} when not declared
     * @param customSqlType the free-form SQL type, may be empty
     * @param length        the declared length, or a negative value when not declared
     * @param javaType      the Java type the getter returns
     * @return the SQL type to emit into the DDL
     */
    private static String resolveSqlType(String declaredType, String customSqlType, int length, TypeMirror javaType) {
        if (SQL_TYPE_CUSTOM.equals(declaredType) && !customSqlType.isEmpty()) {
            return customSqlType;
        }

        if (declaredType != null && !SQL_TYPE_CUSTOM.equals(declaredType)) {
            boolean parameterised = SQL_TYPE_VARCHAR.equals(declaredType) || SQL_TYPE_CHAR.equals(declaredType);
            return parameterised && length > 0 ? declaredType + "(" + length + ")" : declaredType;
        }

        return switch (javaType.toString()) {
            case "java.lang.String" -> length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR(255)";
            case "java.util.UUID" -> "VARCHAR(36)";
            case "int", "java.lang.Integer" -> "INT";
            case "short", "java.lang.Short" -> "SMALLINT";
            case "byte", "java.lang.Byte" -> "TINYINT";
            case "long", "java.lang.Long" -> "BIGINT";
            case "double", "java.lang.Double" -> "DOUBLE";
            case "float", "java.lang.Float" -> "FLOAT";
            case "boolean", "java.lang.Boolean" -> "BOOLEAN";
            case "char", "java.lang.Character" -> "CHAR(1)";
            case "java.util.Date", "java.time.Instant", "java.time.LocalDateTime" -> "DATETIME";
            case "java.time.LocalDate" -> "DATE";
            case "java.time.LocalTime" -> "TIME";
            case "byte[]" -> "BLOB";
            default -> "TEXT";
        };
    }

    /**
     * Resolves the physical table name of a DAO interface.
     *
     * @param element the DAO interface
     * @return the declared table name, or one derived from the type name
     */
    private @NotNull String resolveTableName(TypeElement element) {
        AnnotationMirror tableAnnotation = annotationMirror(element, TABLE_ANNOTATION);
        if (tableAnnotation != null) {
            String declared = annotationString(tableAnnotation, "value", "");
            if (!declared.isEmpty()) {
                return declared;
            }
        }
        return camelToSnake(element.getSimpleName().toString().replaceAll(DAO_NAME_SUFFIX_PATTERN, ""));
    }

    // =============================================================================================
    // Generation
    // =============================================================================================

    /**
     * Generates the mutable implementation of a DAO interface.
     *
     * @param packageName         package the sources are generated into
     * @param interfaceSimpleName simple name of the DAO interface
     * @param interfaceElement    the DAO interface
     * @param columns             the mapped columns
     * @throws IOException when the source file cannot be written
     */
    private void generateImplementation(@NotNull String packageName, String interfaceSimpleName,
                                        TypeElement interfaceElement, List<ColumnSpec> columns) throws IOException {
        String implClassName = interfaceSimpleName + IMPL_SUFFIX;
        String qualifiedName = packageName.isEmpty() ? implClassName : packageName + "." + implClassName;

        JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, interfaceElement);
        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            if (!packageName.isEmpty()) {
                out.println("package " + packageName + ";");
                out.println();
            }
            out.println("import java.util.Objects;");
            out.println();

            out.println("/**");
            out.println(" * GENERATED FILE. DO NOT EDIT.");
            out.println(" * <p>");
            out.println(" * Mutable implementation of " + interfaceSimpleName + ", used to create and modify rows.");
            out.println(" */");
            out.println("public class " + implClassName + " implements " + interfaceSimpleName + " {");
            out.println();

            for (ColumnSpec column : columns) {
                out.println("    private " + column.typeName() + " " + column.fieldName() + ";");
            }
            out.println();

            out.println("    public " + implClassName + "() {");
            out.println("    }");
            out.println();

            StringJoiner constructorParameters = new StringJoiner(", ");
            for (ColumnSpec column : columns) {
                constructorParameters.add(column.typeName() + " " + column.fieldName());
            }
            out.println("    public " + implClassName + "(" + constructorParameters + ") {");
            for (ColumnSpec column : columns) {
                out.println("        this." + column.fieldName() + " = " + column.fieldName() + ";");
            }
            out.println("    }");
            out.println();

            for (ColumnSpec column : columns) {
                out.println("    @Override");
                out.println("    public " + column.typeName() + " " + column.methodName() + "() {");
                out.println("        return this." + column.fieldName() + ";");
                out.println("    }");
                out.println();
            }

            for (ColumnSpec column : columns) {
                out.println("    public " + implClassName + " set" + capitalize(column.fieldName())
                        + "(" + column.typeName() + " " + column.fieldName() + ") {");
                out.println("        this." + column.fieldName() + " = " + column.fieldName() + ";");
                out.println("        return this;");
                out.println("    }");
                out.println();
            }

            out.println("    /**");
            out.println("     * @return a new builder for " + interfaceSimpleName);
            out.println("     */");
            out.println("    public static Builder builder() {");
            out.println("        return new Builder();");
            out.println("    }");
            out.println();

            out.println("    /**");
            out.println("     * Fluent builder for " + implClassName + ".");
            out.println("     */");
            out.println("    public static class Builder {");
            out.println();
            out.println("        private final " + implClassName + " instance = new " + implClassName + "();");
            out.println();
            for (ColumnSpec column : columns) {
                out.println("        public Builder " + column.fieldName() + "(" + column.typeName() + " value) {");
                out.println("            this.instance." + column.fieldName() + " = value;");
                out.println("            return this;");
                out.println("        }");
                out.println();
            }
            out.println("        public " + implClassName + " build() {");
            out.println("            return this.instance;");
            out.println("        }");
            out.println("    }");
            out.println();

            writeIdentity(out, implClassName, interfaceSimpleName, columns);

            out.println("}");
        }
    }

    /**
     * Emits {@code equals}, {@code hashCode} and {@code toString} for a generated implementation.
     * <p>
     * Identity follows the primary key when one is declared; otherwise the implementation keeps the
     * reference semantics inherited from {@link Object}.
     *
     * @param out                 target writer
     * @param implClassName       simple name of the generated class
     * @param interfaceSimpleName simple name of the DAO interface
     * @param columns             the mapped columns
     */
    private static void writeIdentity(
            @NotNull PrintWriter out,
            String implClassName,
            String interfaceSimpleName,
            @NotNull List<ColumnSpec> columns
    ) {
        Optional<ColumnSpec> primaryKey = columns.stream().filter(ColumnSpec::primaryKey).findFirst();

        out.println("    @Override");
        out.println("    public boolean equals(Object other) {");
        if (primaryKey.isPresent()) {
            ColumnSpec column = primaryKey.get();
            out.println("        if (this == other) return true;");
            out.println("        if (!(other instanceof " + interfaceSimpleName + " that)) return false;");
            out.println("        return Objects.equals(this." + column.fieldName() + ", that."
                    + column.methodName() + "());");
        } else {
            // Without a primary key there is no stable identity, so stay with reference equality.
            out.println("        return this == other;");
        }
        out.println("    }");
        out.println();

        out.println("    @Override");
        out.println("    public int hashCode() {");
        if (primaryKey.isPresent()) {
            out.println("        return Objects.hashCode(this." + primaryKey.get().fieldName() + ");");
        } else {
            out.println("        return System.identityHashCode(this);");
        }
        out.println("    }");
        out.println();

        out.println("    @Override");
        out.println("    public String toString() {");
        out.println("        return \"" + implClassName + "{\" +");
        for (int index = 0; index < columns.size(); index++) {
            String separator = index == 0 ? "" : ", ";
            out.println("                \"" + separator + columns.get(index).fieldName() + "=\" + this."
                    + columns.get(index).fieldName() + " +");
        }
        out.println("                \"}\";");
        out.println("    }");
    }

    /**
     * Generates the repository of a DAO interface.
     *
     * @param packageName         package the sources are generated into
     * @param interfaceSimpleName simple name of the DAO interface
     * @param interfaceElement    the DAO interface
     * @param tableName           physical table name
     * @param columns             the mapped columns
     * @throws IOException when the source file cannot be written
     */
    private void generateRepository(
            @NotNull String packageName,
            String interfaceSimpleName,
            TypeElement interfaceElement,
            String tableName,
            @NotNull List<ColumnSpec> columns
    ) throws IOException {
        String repositoryClassName = interfaceSimpleName + REPOSITORY_SUFFIX;
        String implClassName = interfaceSimpleName + IMPL_SUFFIX;
        String qualifiedName = packageName.isEmpty()
                ? repositoryClassName
                : packageName + "." + repositoryClassName;

        String primaryKeyType = columns.stream()
                .filter(ColumnSpec::primaryKey)
                .findFirst()
                .map(column -> boxedTypeName(column.typeName()))
                .orElse("Void");

        String daoCrud = simpleName(DAO_CRUD_CLASS);
        String mysqlManager = simpleName(MYSQL_MANAGER_CLASS);

        JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, interfaceElement);
        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            if (!packageName.isEmpty()) {
                out.println("package " + packageName + ";");
                out.println();
            }

            out.println("import " + DAO_CRUD_CLASS + ";");
            out.println("import " + DAO_CRUD_CLASS + ".ColumnMetadata;");
            out.println("import " + DAO_CRUD_CLASS + ".ColumnMeta;");
            out.println("import " + MYSQL_MANAGER_CLASS + ";");
            out.println();
            out.println("import java.util.ArrayList;");
            out.println("import java.util.List;");
            out.println("import java.util.Optional;");
            out.println("import java.util.concurrent.CompletableFuture;");
            out.println();

            writeRepositoryJavadoc(out, interfaceSimpleName, implClassName, repositoryClassName, tableName,
                    mysqlManager);
            out.println("public class " + repositoryClassName + " extends " + daoCrud + "<"
                    + interfaceSimpleName + ", " + primaryKeyType + "> {");
            out.println();

            out.println("    /**");
            out.println("     * Creates a new repository.");
            out.println("     * <p>");
            out.println("     * Call {@link #createTableIfNotExistsAsync()} after construction to ensure the table");
            out.println("     * exists.");
            out.println("     *");
            out.println("     * @param mysqlManager the MySQL manager used for every database operation");
            out.println("     */");
            out.println("    public " + repositoryClassName + "(" + mysqlManager + " mysqlManager) {");
            out.println("        super(mysqlManager, " + interfaceSimpleName + ".class, \""
                    + escapeString(tableName) + "\", buildColumnMetadataList());");
            out.println("    }");
            out.println();

            writeColumnMetadataFactory(out, interfaceSimpleName, columns, daoCrud);

            out.println("    /**");
            out.println("     * @return a new builder for " + interfaceSimpleName);
            out.println("     */");
            out.println("    public " + implClassName + ".Builder newBuilder() {");
            out.println("        return " + implClassName + ".builder();");
            out.println("    }");
            out.println();

            out.println("    /**");
            out.println("     * @return a new empty " + interfaceSimpleName + " instance");
            out.println("     */");
            out.println("    public " + implClassName + " newInstance() {");
            out.println("        return new " + implClassName + "();");
            out.println("    }");

            writeFinders(out, interfaceSimpleName, columns);

            out.println("}");
        }
    }

    /**
     * Emits the class level Javadoc of a generated repository.
     *
     * @param out                 target writer
     * @param interfaceSimpleName simple name of the DAO interface
     * @param implClassName       simple name of the generated implementation
     * @param repositoryClassName simple name of the generated repository
     * @param tableName           physical table name
     * @param mysqlManager        reference to the connection manager type
     */
    private static void writeRepositoryJavadoc(
            @NotNull PrintWriter out,
            String interfaceSimpleName,
            String implClassName,
            String repositoryClassName,
            String tableName,
            String mysqlManager
    ) {
        out.println("/**");
        out.println(" * GENERATED FILE. DO NOT EDIT.");
        out.println(" * <p>");
        out.println(" * Repository for " + interfaceSimpleName + ", backed by the '" + tableName + "' table.");
        out.println(" * <strong>Every database operation is asynchronous and returns a"
                + " CompletableFuture.</strong>");
        out.println(" * <p>");
        out.println(" * Usage:");
        out.println(" * <pre>");
        out.println(" * " + simpleName(DATABASE_SETTINGS_CLASS) + " settings = new DatabaseConfig();");
        out.println(" * " + mysqlManager + " mysqlManager = new " + mysqlManager + "(plugin, settings);");
        out.println(" * mysqlManager.setupDatabaseAsync().thenRun(() -&gt; {");
        out.println(" *     " + repositoryClassName + " repository = new " + repositoryClassName
                + "(mysqlManager);");
        out.println(" *     repository.createTableIfNotExistsAsync().thenRun(() -&gt; {");
        out.println(" *         " + implClassName + " entity = repository.newBuilder().build();");
        out.println(" *         repository.saveAsync(entity).thenAccept(id -&gt; {});");
        out.println(" *     });");
        out.println(" * });");
        out.println(" * </pre>");
        out.println(" *");
        out.println(" * @see " + DATABASE_SETTINGS_CLASS);
        out.println(" */");
    }

    /**
     * Emits the static factory that rebuilds the column metadata at runtime.
     *
     * @param out                 target writer
     * @param interfaceSimpleName simple name of the DAO interface
     * @param columns             the mapped columns
     * @param daoCrud             reference to the JDBC base class owning the metadata types
     */
    private static void
    writeColumnMetadataFactory(@NotNull PrintWriter out, String interfaceSimpleName,
                                                   @NotNull List<ColumnSpec> columns, String daoCrud) {
        out.println("    /**");
        out.println("     * @return the column metadata derived from the DAO annotations at build time");
        out.println("     */");
        out.println("    private static List<ColumnMetadata> buildColumnMetadataList() {");
        out.println("        List<ColumnMetadata> columnMetadataList = new ArrayList<>();");
        out.println("        try {");

        for (ColumnSpec column : columns) {
            out.println("            columnMetadataList.add(new ColumnMeta(");
            out.println("                \"" + escapeString(column.columnName()) + "\",");
            out.println("                \"" + escapeString(column.sqlType()) + "\",");
            out.println("                " + column.nullable() + ",");
            out.println("                " + column.unique() + ",");
            out.println("                " + column.primaryKey() + ",");
            out.println("                " + column.autoIncrement() + ",");
            out.println("                \"" + escapeString(column.defaultExpr()) + "\",");
            out.println("                " + interfaceSimpleName + ".class.getMethod(\""
                    + column.methodName() + "\"),");
            out.println("                " + column.classLiteral() + ",");

            ForeignKeySpec foreignKey = column.foreignKey();
            if (foreignKey == null) {
                out.println("                null, null, null, null,");
            } else {
                out.println("                \"" + escapeString(foreignKey.referencesTable()) + "\",");
                out.println("                \"" + escapeString(foreignKey.referencesColumn()) + "\",");
                out.println("                \"" + escapeString(foreignKey.onDelete()) + "\",");
                out.println("                \"" + escapeString(foreignKey.onUpdate()) + "\",");
            }

            if (column.indexes().isEmpty()) {
                out.println("                null");
            } else {
                StringJoiner indexJoiner = new StringJoiner(",\n");
                for (IndexSpec index : column.indexes()) {
                    indexJoiner.add("                    new " + daoCrud + ".IndexInfo(\""
                            + escapeString(index.name()) + "\", " + index.unique() + ")");
                }
                out.println("                List.of(");
                out.println(indexJoiner.toString());
                out.println("                )");
            }
            out.println("            ));");
        }

        out.println("        } catch (NoSuchMethodException exception) {");
        out.println("            throw new IllegalStateException(\"Failed to build column metadata\", exception);");
        out.println("        }");
        out.println("        return columnMetadataList;");
        out.println("    }");
        out.println();
    }

    /**
     * Emits an asynchronous finder for every column that carries an index, is unique, or both.
     * <p>
     * The primary key is skipped because the JDBC base class already exposes a load by id operation.
     *
     * @param out                 target writer
     * @param interfaceSimpleName simple name of the DAO interface
     * @param columns             the mapped columns
     */
    private static void writeFinders(PrintWriter out, String interfaceSimpleName, @NotNull List<ColumnSpec> columns) {
        for (ColumnSpec column : columns) {
            boolean searchable = !column.indexes().isEmpty() || column.unique();
            if (column.primaryKey() || !searchable) {
                continue;
            }

            String suffix = capitalize(column.fieldName());

            out.println();
            out.println("    /**");
            out.println("     * Finds every row matching a " + column.columnName() + " value.");
            out.println("     *");
            out.println("     * @param value the value to search for");
            out.println("     * @return the matching entities");
            out.println("     */");
            out.println("    public CompletableFuture<List<" + interfaceSimpleName + ">> findBy" + suffix
                    + "Async(" + column.typeName() + " value) {");
            out.println("        return findByColumnAsync(\"" + escapeString(column.columnName()) + "\", value);");
            out.println("    }");

            if (column.unique()) {
                out.println();
                out.println("    /**");
                out.println("     * Finds the single row matching a " + column.columnName() + " value.");
                out.println("     *");
                out.println("     * @param value the value to search for");
                out.println("     * @return the matching entity, if any");
                out.println("     */");
                out.println("    public CompletableFuture<Optional<" + interfaceSimpleName + ">> findOneBy" + suffix
                        + "Async(" + column.typeName() + " value) {");
                out.println("        return findOneByColumnAsync(\"" + escapeString(column.columnName())
                        + "\", value);");
                out.println("    }");
            }
        }
    }

    // =============================================================================================
    // Annotation reading
    // =============================================================================================

    /**
     * Finds an annotation mirror on an element by fully qualified annotation name.
     *
     * @param element        the annotated element
     * @param annotationName fully qualified annotation name
     * @return the mirror, or {@code null} when the annotation is absent
     */
    private static AnnotationMirror annotationMirror(Element element, String annotationName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement annotationElement
                    && annotationElement.getQualifiedName().contentEquals(annotationName)) {
                return mirror;
            }
        }
        return null;
    }

    /**
     * Reads one annotation member, falling back to its declared default.
     *
     * @param mirror       the annotation mirror
     * @param memberName   the member to read
     * @param expectedType the expected runtime type of the value
     * @param <T>          the expected value type
     * @return the value, or {@code null} when absent or of an unexpected type
     */
    private <T> T annotationValue(AnnotationMirror mirror, String memberName, Class<T> expectedType) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : this.elementUtils.getElementValuesWithDefaults(mirror).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(memberName)) {
                Object value = entry.getValue().getValue();
                return expectedType.isInstance(value) ? expectedType.cast(value) : null;
            }
        }
        return null;
    }

    /**
     * Reads a {@code String} annotation member.
     *
     * @param mirror       the annotation mirror
     * @param memberName   the member to read
     * @param defaultValue value used when the member is absent
     * @return the resolved value
     */
    private String annotationString(AnnotationMirror mirror, String memberName, String defaultValue) {
        String value = annotationValue(mirror, memberName, String.class);
        return value == null ? defaultValue : value;
    }

    /**
     * Reads an {@code int} annotation member.
     *
     * @param mirror       the annotation mirror
     * @param memberName   the member to read
     * @param defaultValue value used when the member is absent
     * @return the resolved value
     */
    private int annotationInt(AnnotationMirror mirror, String memberName, int defaultValue) {
        Integer value = annotationValue(mirror, memberName, Integer.class);
        return value == null ? defaultValue : value;
    }

    /**
     * Reads a {@code boolean} annotation member.
     *
     * @param mirror       the annotation mirror
     * @param memberName   the member to read
     * @param defaultValue value used when the member is absent
     * @return the resolved value
     */
    private boolean annotationBoolean(AnnotationMirror mirror, String memberName, boolean defaultValue) {
        Boolean value = annotationValue(mirror, memberName, Boolean.class);
        return value == null ? defaultValue : value;
    }

    /**
     * Reads the simple name of an enum annotation member.
     *
     * @param mirror     the annotation mirror
     * @param memberName the member to read
     * @return the constant name, or {@code null} when the member is absent
     */
    private String annotationEnumConstant(AnnotationMirror mirror, String memberName) {
        VariableElement constant = annotationValue(mirror, memberName, VariableElement.class);
        return constant == null ? null : constant.getSimpleName().toString();
    }

    /**
     * Reads a nested annotation array member.
     *
     * @param mirror     the annotation mirror
     * @param memberName the member to read
     * @return the nested mirrors, possibly empty
     */
    private @NotNull List<AnnotationMirror> annotationMirrorArray(AnnotationMirror mirror, String memberName) {
        List<?> rawValues = annotationValue(mirror, memberName, List.class);
        if (rawValues == null) {
            return List.of();
        }

        List<AnnotationMirror> mirrors = new ArrayList<>(rawValues.size());
        for (Object rawValue : rawValues) {
            if (rawValue instanceof AnnotationValue annotationValue
                    && annotationValue.getValue() instanceof AnnotationMirror nested) {
                mirrors.add(nested);
            }
        }
        return mirrors;
    }

    // =============================================================================================
    // Naming utilities
    // =============================================================================================

    /**
     * Returns the simple name of a fully qualified type name.
     *
     * @param qualifiedName fully qualified type name
     * @return the trailing segment
     */
    private static @NotNull String simpleName(@NotNull String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    /**
     * Strips a leading {@code get} or {@code is} from a getter name.
     *
     * @param methodName the getter name
     * @return the bare property name
     */
    private static @NotNull String stripAccessorPrefix(@NotNull String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return methodName.substring(3);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return methodName.substring(2);
        }
        return methodName;
    }

    /**
     * Lower-cases the first character of an identifier.
     *
     * @param value the identifier
     * @return the decapitalised identifier
     */
    private static String decapitalize(@NotNull String value) {
        return value.isEmpty() ? value : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Upper-cases the first character of an identifier.
     *
     * @param value the identifier
     * @return the capitalised identifier
     */
    private static String capitalize(@NotNull String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Converts {@code camelCase} into {@code snake_case}.
     *
     * @param camelCase the identifier to convert
     * @return the converted identifier
     */
    private static @NotNull String camelToSnake(@NotNull String camelCase) {
        StringBuilder snakeCase = new StringBuilder(camelCase.length() + 4);
        for (int index = 0; index < camelCase.length(); index++) {
            char character = camelCase.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0) {
                    snakeCase.append('_');
                }
                snakeCase.append(Character.toLowerCase(character));
            } else {
                snakeCase.append(character);
            }
        }
        return snakeCase.toString();
    }

    /**
     * Boxes a primitive type name so it can be used as a generic type argument.
     *
     * @param typeName the type name to box
     * @return the boxed name, or {@code typeName} when it already is a reference type
     */
    @Contract(pure = true)
    private static String boxedTypeName(@NotNull String typeName) {
        return switch (typeName) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "double" -> "Double";
            case "float" -> "Float";
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "char" -> "Character";
            default -> typeName;
        };
    }

    /**
     * Turns a referential action constant such as {@code SET_NULL} into its SQL spelling.
     *
     * @param enumConstant the constant name, may be {@code null}
     * @return the SQL action, or an empty string when no constant was declared
     */
    @Contract(pure = true)
    private static @NotNull String sqlAction(String enumConstant) {
        return enumConstant == null ? "" : enumConstant.replace('_', ' ');
    }

    /**
     * Escapes a value for inclusion in a generated Java string literal.
     *
     * @param value the raw value
     * @return the escaped value, without surrounding quotes
     */
    private static @NotNull String escapeString(@NotNull String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Reports a fatal processing error against an element.
     *
     * @param element the offending element
     * @param message the diagnostic message
     */
    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    // =============================================================================================
    // Model
    // =============================================================================================

    /**
     * One mapped column of a DAO interface.
     *
     * @param methodName    name of the getter that declared the column
     * @param returnType    declared return type of that getter
     * @param erasedType    erasure of the return type, used for the runtime class literal
     * @param columnName    physical column name
     * @param sqlType       SQL type emitted into the DDL
     * @param nullable      whether the column accepts {@code NULL}
     * @param unique        whether the column carries a unique constraint
     * @param defaultExpr   default SQL expression, empty when none is declared
     * @param primaryKey    whether the column is the primary key
     * @param autoIncrement whether the primary key auto increments
     * @param foreignKey    the foreign key constraint, or {@code null} when none is declared
     * @param indexes       the declared indexes, possibly empty
     */
    private record ColumnSpec(String methodName, TypeMirror returnType, TypeMirror erasedType, String columnName,
                              String sqlType, boolean nullable, boolean unique, String defaultExpr,
                              boolean primaryKey, boolean autoIncrement, ForeignKeySpec foreignKey,
                              List<IndexSpec> indexes) {

        /** @return the fully qualified name of the column type, as written into generated sources */
        String typeName() {
            return this.returnType.toString();
        }

        /**
         * @return the {@code .class} literal of the column type, erased so the literal stays legal
         *         Java even for generic return types
         */
        @Contract(pure = true)
        @NotNull String classLiteral() {
            return this.erasedType + ".class";
        }

        /** @return the backing field name derived from the getter name */
        String fieldName() {
            return decapitalize(stripAccessorPrefix(this.methodName));
        }
    }

    /**
     * A foreign key constraint on a column.
     *
     * @param referencesTable  the referenced table
     * @param referencesColumn the referenced column
     * @param onDelete         the {@code ON DELETE} action, in SQL spelling
     * @param onUpdate         the {@code ON UPDATE} action, in SQL spelling
     */
    private record ForeignKeySpec(String referencesTable, String referencesColumn, String onDelete, String onUpdate) {
    }

    /**
     * An index declared on a column.
     *
     * @param name   the index name, empty when the database should pick one
     * @param unique whether the index is unique
     */
    private record IndexSpec(String name, boolean unique) {
    }
}
