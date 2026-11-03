package pandastic.studio.core.processor.config;

import com.google.auto.service.AutoService;

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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Build-time generator for Bukkit YAML backed configuration classes.
 * <p>
 * For every type annotated with {@code @Configurable} this processor emits a concrete class that
 * implements {@code GeneratedConfig}, every interface the annotated type implements, and - when the
 * annotated element is itself an interface - that interface too. Each member annotated with
 * {@code @ConfigValue} becomes a field plus a JavaBeans accessor, is read from the YAML document on
 * {@code load()} and written back on {@code save()}.
 * <p>
 * Carrying the superinterfaces over is what makes a generated configuration usable as a value:
 * a declaration implementing a narrow read-only view stays substitutable by its generated twin.
 * <p>
 * Declared defaults survive into the generated class. A class declaration is instantiated once per
 * generated constructor and its values are copied over, so the defaults live in its field
 * initializers. An interface declaration needs no instance at all: every member backed by a
 * {@code default} method is seeded through the {@code Declaration.super} call form, which is legal
 * precisely because the generated class implements the declaration directly. One annotated
 * interface therefore carries both the schema and the defaults.
 *
 * <h2>Supported configuration type matrix</h2>
 * The generated code talks to {@code org.bukkit.configuration.ConfigurationSection}. That API has
 * no {@code getFloat}, {@code getShort}, {@code getByte} or {@code getChar} accessor, so those
 * types are read through a wider accessor and narrowed, or read as text. Every list accessor used
 * below was verified to exist on {@code ConfigurationSection} and is guaranteed by Bukkit to return
 * a non-{@code null} list without {@code null} elements.
 *
 * <table border="1">
 *   <caption>Java type to Bukkit accessor mapping</caption>
 *   <tr><th>Java type</th><th>Read</th><th>Write</th></tr>
 *   <tr><td>{@code int}, {@code Integer}</td><td>{@code getInt}</td><td>as is</td></tr>
 *   <tr><td>{@code long}, {@code Long}</td><td>{@code getLong}</td><td>as is</td></tr>
 *   <tr><td>{@code double}, {@code Double}</td><td>{@code getDouble}</td><td>as is</td></tr>
 *   <tr><td>{@code float}, {@code Float}</td><td>{@code (float) getDouble}</td><td>as is</td></tr>
 *   <tr><td>{@code short}, {@code Short}</td><td>{@code (short) getInt}</td><td>as is</td></tr>
 *   <tr><td>{@code byte}, {@code Byte}</td><td>{@code (byte) getInt}</td><td>as is</td></tr>
 *   <tr><td>{@code boolean}, {@code Boolean}</td><td>{@code getBoolean}</td><td>as is</td></tr>
 *   <tr><td>{@code char}, {@code Character}</td><td>{@code getString} + {@code charAt(0)}</td><td>single character text</td></tr>
 *   <tr><td>{@code String}</td><td>{@code getString}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Integer>}</td><td>{@code getIntegerList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Long>}</td><td>{@code getLongList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Double>}</td><td>{@code getDoubleList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Float>}</td><td>{@code getFloatList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Short>}</td><td>{@code getShortList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Byte>}</td><td>{@code getByteList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Boolean>}</td><td>{@code getBooleanList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<String>}</td><td>{@code getStringList}</td><td>as is</td></tr>
 *   <tr><td>{@code List<Character>}</td><td>{@code getCharacterList}</td><td>{@code List<String>}</td></tr>
 *   <tr><td>{@code int[]}, {@code Integer[]}</td><td>{@code getIntegerList} + convert</td><td>{@code List<Integer>}</td></tr>
 *   <tr><td>{@code long[]}, {@code Long[]}</td><td>{@code getLongList} + convert</td><td>{@code List<Long>}</td></tr>
 *   <tr><td>{@code double[]}, {@code Double[]}</td><td>{@code getDoubleList} + convert</td><td>{@code List<Double>}</td></tr>
 *   <tr><td>{@code float[]}, {@code Float[]}</td><td>{@code getFloatList} + convert</td><td>{@code List<Float>}</td></tr>
 *   <tr><td>{@code short[]}, {@code Short[]}</td><td>{@code getShortList} + convert</td><td>{@code List<Short>}</td></tr>
 *   <tr><td>{@code byte[]}, {@code Byte[]}</td><td>{@code getByteList} + convert</td><td>{@code List<Byte>}</td></tr>
 *   <tr><td>{@code boolean[]}, {@code Boolean[]}</td><td>{@code getBooleanList} + convert</td><td>{@code List<Boolean>}</td></tr>
 *   <tr><td>{@code char[]}, {@code Character[]}</td><td>{@code getCharacterList} + convert</td><td>{@code List<String>}</td></tr>
 *   <tr><td>{@code String[]}</td><td>{@code getStringList} + convert</td><td>{@code List<String>}</td></tr>
 *   <tr><td>any {@code ConfigurationSerializable}</td><td>{@code getSerializable}</td><td>as is</td></tr>
 * </table>
 *
 * <p>
 * Java arrays have no YAML representation, so every array is persisted as the equivalent list.
 * Characters are persisted as single character strings, which round-trips through both
 * {@code getString} and {@code getCharacterList}.
 * <p>
 * Anything outside this matrix is rejected with a {@link Diagnostic.Kind#ERROR} naming the offending
 * member and its type; the processor never emits code it cannot prove correct.
 */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class ConfigContractProcessor extends AbstractProcessor {

    // ---------------------------------------------------------------------------------------------
    // Fully qualified names. Single source of truth.
    //
    // This module declares no dependency on pandastic-core-config, pandastic-core-database or pandastic-core-bridge-contract on
    // purpose: those modules apply this processor, so a compile dependency back onto them would
    // close a Gradle task cycle. The annotation types are consequently unavailable as classes and
    // every name below has to stay a literal, resolved at processing time through the
    // javax.lang.model mirror API.
    //
    // Paper is compile-only for every module and therefore absent from this processor's runtime
    // classpath as well, so the Bukkit names below are likewise symbolic: they only ever appear
    // inside generated sources.
    //
    // Declare each name exactly once here and derive everything else - simple names, supported
    // annotation set, generated imports - from these constants.
    // ---------------------------------------------------------------------------------------------

    /** Marks a type as a configuration declaration. */
    private static final String CONFIGURABLE_ANNOTATION = "pandastic.studio.core.service.api.config.Configurable";

    /** Marks a field or getter as one configuration entry. */
    private static final String CONFIG_VALUE_ANNOTATION = "pandastic.studio.core.service.api.config.ConfigValue";

    /** Lifecycle interface every generated configuration implements. */
    private static final String GENERATED_CONFIG_INTERFACE = "pandastic.studio.core.service.api.config.GeneratedConfig";

    /** Bukkit section interface, used by the generated {@code readChar} helper. */
    private static final String BUKKIT_CONFIGURATION_SECTION = "org.bukkit.configuration.ConfigurationSection";

    /** Bukkit YAML document type backing every generated configuration. */
    private static final String BUKKIT_YAML_CONFIGURATION = "org.bukkit.configuration.file.YamlConfiguration";

    /** Checked exception thrown by {@code YamlConfiguration#load(File)}. */
    private static final String BUKKIT_INVALID_CONFIGURATION_EXCEPTION =
            "org.bukkit.configuration.InvalidConfigurationException";

    /** Marker interface for types Bukkit serialises on its own, such as ItemStack or Location. */
    private static final String BUKKIT_CONFIGURATION_SERIALIZABLE =
            "org.bukkit.configuration.serialization.ConfigurationSerializable";

    /** Name of the {@code ConfigFormat} constant selecting JSON output. */
    private static final String FORMAT_JSON = "JSON";

    /** Name of the {@code YamlConfiguration} field held by every generated configuration. */
    private static final String DOCUMENT_FIELD = "yamlConfiguration";

    /** Expression addressing the YAML document from inside a generated instance method. */
    private static final String DOCUMENT = "this." + DOCUMENT_FIELD;

    /**
     * Name of the local holding the declaration instance the generated constructor seeds from.
     * <p>
     * Member locals are named {@code default<Member>}, so this name can never be shadowed by one.
     */
    private static final String TEMPLATE_VARIABLE = "defaults";

    /** Placeholder written for a {@code null} scalar so the key, and therefore its comment, survives a save. */
    private static final String NULL_SCALAR_PLACEHOLDER = "\"\"";

    /** Placeholder written for a {@code null} list or array so the key keeps its list shape. */
    private static final String NULL_SEQUENCE_PLACEHOLDER = "List.of()";

    /** Every primitive type together with the Bukkit accessors able to carry it. */
    private static final List<PrimitiveBridge> PRIMITIVE_BRIDGES = List.of(
            new PrimitiveBridge(TypeKind.INT, "int", "java.lang.Integer", "Integer",
                    "getInt", "", "getIntegerList", "toIntArray"),
            new PrimitiveBridge(TypeKind.LONG, "long", "java.lang.Long", "Long",
                    "getLong", "", "getLongList", "toLongArray"),
            new PrimitiveBridge(TypeKind.DOUBLE, "double", "java.lang.Double", "Double",
                    "getDouble", "", "getDoubleList", "toDoubleArray"),
            new PrimitiveBridge(TypeKind.FLOAT, "float", "java.lang.Float", "Float",
                    "getDouble", "(float) ", "getFloatList", "toFloatArray"),
            new PrimitiveBridge(TypeKind.SHORT, "short", "java.lang.Short", "Short",
                    "getInt", "(short) ", "getShortList", "toShortArray"),
            new PrimitiveBridge(TypeKind.BYTE, "byte", "java.lang.Byte", "Byte",
                    "getInt", "(byte) ", "getByteList", "toByteArray"),
            new PrimitiveBridge(TypeKind.BOOLEAN, "boolean", "java.lang.Boolean", "Boolean",
                    "getBoolean", "", "getBooleanList", "toBooleanArray"),
            new PrimitiveBridge(TypeKind.CHAR, "char", "java.lang.Character", "Character",
                    null, "", "getCharacterList", "toCharArray"));

    /** {@link #PRIMITIVE_BRIDGES} indexed by primitive {@link TypeKind}. */
    private static final Map<TypeKind, PrimitiveBridge> BRIDGES_BY_KIND = PRIMITIVE_BRIDGES.stream()
            .collect(Collectors.toUnmodifiableMap(PrimitiveBridge::kind, Function.identity()));

    /** {@link #PRIMITIVE_BRIDGES} indexed by the fully qualified name of the boxed type. */
    private static final Map<String, PrimitiveBridge> BRIDGES_BY_BOXED_NAME = PRIMITIVE_BRIDGES.stream()
            .collect(Collectors.toUnmodifiableMap(PrimitiveBridge::boxedName, Function.identity()));

    private Types types;
    private Elements elements;
    private TypeMirror listErasure;
    private TypeMirror stringType;
    private TypeMirror configurationSerializableType;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        this.types = processingEnvironment.getTypeUtils();
        this.elements = processingEnvironment.getElementUtils();
        this.listErasure = erasureOf(List.class.getCanonicalName());
        this.stringType = erasureOf(String.class.getCanonicalName());
        this.configurationSerializableType = erasureOf(BUKKIT_CONFIGURATION_SERIALIZABLE);
    }

    /**
     * Declares the annotations this processor claims.
     * <p>
     * Overriding the method instead of using {@code @SupportedAnnotationTypes} keeps
     * {@link #CONFIGURABLE_ANNOTATION} the single place the name is spelled out; the annotation
     * only accepts literals, which would duplicate it.
     *
     * @return the single supported annotation name
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(CONFIGURABLE_ANNOTATION);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        if (roundEnvironment.processingOver()) {
            return false;
        }

        boolean claimed = false;
        for (TypeElement annotation : annotations) {
            if (!annotation.getQualifiedName().contentEquals(CONFIGURABLE_ANNOTATION)) {
                continue;
            }
            claimed = true;
            for (Element element : roundEnvironment.getElementsAnnotatedWith(annotation)) {
                processConfigurable(element);
            }
        }
        return claimed;
    }

    /**
     * Validates one {@code @Configurable} element and generates its configuration class.
     *
     * @param element the annotated element
     */
    private void processConfigurable(Element element) {
        if (element.getKind() != ElementKind.CLASS && element.getKind() != ElementKind.INTERFACE) {
            error(element, "@Configurable can only be applied to classes or interfaces.");
            return;
        }

        TypeElement typeElement = (TypeElement) element;
        AnnotationMirror mirror = annotationMirror(typeElement, CONFIGURABLE_ANNOTATION);
        if (mirror == null) {
            return;
        }

        String className = orDefault(annotationValue(mirror, "name", String.class),
                typeElement.getSimpleName() + "Config");
        String targetPackage = orDefault(annotationValue(mirror, "targetPackage", String.class), "");
        if (targetPackage.isEmpty()) {
            targetPackage = this.elements.getPackageOf(typeElement).getQualifiedName().toString();
        }

        String format = orDefault(annotationEnumConstant(mirror, "format"), "");
        if (FORMAT_JSON.equals(format)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "ConfigFormat.JSON only changes the file extension; the generated code writes YAML content"
                            + " through Bukkit's YamlConfiguration.", typeElement);
        }

        Map<String, ConfigItem> items = new LinkedHashMap<>();
        collectConfigItems(typeElement, items);
        if (items.isEmpty()) {
            error(typeElement, "@Configurable type " + typeElement.getSimpleName()
                    + " declares no @ConfigValue members, so there is nothing to generate.");
            return;
        }

        GeneratedHelpers helpers = new GeneratedHelpers();
        Map<ConfigItem, ValueCodec> codecs = new LinkedHashMap<>();
        for (ConfigItem item : items.values()) {
            ValueCodec codec = resolveCodec(item, helpers);
            if (codec == null) {
                error(item.source(), "@ConfigValue member '" + item.memberName() + "' has type '" + item.type()
                        + "', which the YAML configuration generator cannot read or write. See the supported type"
                        + " matrix on " + ConfigContractProcessor.class.getName() + ".");
                return;
            }
            codecs.put(item, codec);
        }

        try {
            writeConfigClass(typeElement, targetPackage, className, fileExtension(format), codecs, helpers);
        } catch (IOException exception) {
            error(typeElement, "Failed to generate configuration '" + className + "': " + exception);
        }
    }

    /**
     * Maps a declared format constant onto the default file extension of the generated configuration.
     *
     * @param format name of the {@code ConfigFormat} constant, possibly empty
     * @return the extension, including the leading dot
     */
    private static String fileExtension(String format) {
        return FORMAT_JSON.equals(format) ? ".json" : ".yml";
    }

    /**
     * Collects every {@code @ConfigValue} member of a type, including inherited ones.
     * <p>
     * Super types are visited first, so a subtype can override an inherited entry by reusing its
     * configuration path.
     *
     * @param typeElement the type to scan
     * @param items       accumulator keyed by configuration path
     */
    private void collectConfigItems(TypeElement typeElement, Map<String, ConfigItem> items) {
        for (TypeMirror superInterface : typeElement.getInterfaces()) {
            if (superInterface instanceof DeclaredType declaredType
                    && declaredType.asElement() instanceof TypeElement superElement) {
                collectConfigItems(superElement, items);
            }
        }

        if (typeElement.getSuperclass() instanceof DeclaredType declaredSuperclass
                && declaredSuperclass.asElement() instanceof TypeElement superElement
                && !superElement.getQualifiedName().contentEquals(Object.class.getCanonicalName())) {
            collectConfigItems(superElement, items);
        }

        for (Element enclosed : typeElement.getEnclosedElements()) {
            AnnotationMirror mirror = annotationMirror(enclosed, CONFIG_VALUE_ANNOTATION);
            if (mirror == null) {
                continue;
            }

            String declaredPath = orDefault(annotationValue(mirror, "fieldName", String.class), "");
            List<String> comments = splitComment(orDefault(annotationValue(mirror, "comment", String.class), ""));
            String memberName = enclosed.getSimpleName().toString();
            String path = declaredPath.isEmpty() ? memberName : declaredPath;

            switch (enclosed.getKind()) {
                case METHOD -> {
                    ExecutableElement method = (ExecutableElement) enclosed;
                    if (method.getParameters().isEmpty()) {
                        items.put(path, new ConfigItem(path, comments, method.getReturnType(), memberName,
                                propertyName(memberName), true, enclosed));
                    } else {
                        error(method, "@ConfigValue methods must not declare parameters.");
                    }
                }
                case FIELD -> items.put(path,
                        new ConfigItem(path, comments, enclosed.asType(), memberName, memberName, false, enclosed));
                default -> error(enclosed, "@ConfigValue can only be applied to fields and no-arg methods.");
            }
        }
    }

    // =============================================================================================
    // Type matrix
    // =============================================================================================

    /**
     * Resolves how one configuration member is read from and written back to the YAML document.
     *
     * @param item    the member to bind
     * @param helpers accumulator of the private helper methods the generated class will need
     * @return the binding, or {@code null} when the type is outside the supported matrix
     */
    private ValueCodec resolveCodec(ConfigItem item, GeneratedHelpers helpers) {
        TypeMirror type = item.type();
        String path = quote(item.path());
        String field = "this." + item.fieldName();

        PrimitiveBridge primitive = BRIDGES_BY_KIND.get(type.getKind());
        if (primitive != null) {
            return scalarCodec(primitive, false, path, field, helpers);
        }
        if (type instanceof ArrayType arrayType) {
            return arrayCodec(arrayType, path, field, helpers);
        }
        if (type instanceof DeclaredType declaredType) {
            return declaredCodec(declaredType, path, field, helpers);
        }
        return null;
    }

    /**
     * Builds the binding for a primitive or boxed primitive member.
     *
     * @param bridge  the primitive bridge to use
     * @param boxed   {@code true} when the member type is the boxed form
     * @param path    quoted configuration path literal
     * @param field   expression addressing the generated field
     * @param helpers helper accumulator
     * @return the binding, never {@code null}
     */
    private ValueCodec scalarCodec(PrimitiveBridge bridge, boolean boxed, String path, String field,
                                   GeneratedHelpers helpers) {
        if (bridge.kind() == TypeKind.CHAR) {
            helpers.requireReadChar();
            // A boxed Character field may still be null the first time load() runs.
            String fallback = boxed ? "(" + field + " == null ? '\\0' : " + field + ")" : field;
            String read = "readChar(" + DOCUMENT + ", " + path + ", " + fallback + ")";
            String write = boxed
                    ? "String.valueOf(" + field + ".charValue())"
                    : "String.valueOf(" + field + ")";
            return new ValueCodec(read, write, boxed ? NULL_SCALAR_PLACEHOLDER : null);
        }

        String read = bridge.narrowingCast() + DOCUMENT + "." + bridge.scalarGetter() + "(" + path + ")";
        return new ValueCodec(read, field, boxed ? NULL_SCALAR_PLACEHOLDER : null);
    }

    /**
     * Builds the binding for an array member. Arrays are stored as the equivalent YAML list.
     *
     * @param arrayType the member type
     * @param path      quoted configuration path literal
     * @param field     expression addressing the generated field
     * @param helpers   helper accumulator
     * @return the binding, or {@code null} when the component type is unsupported
     */
    private ValueCodec arrayCodec(ArrayType arrayType, String path, String field, GeneratedHelpers helpers) {
        TypeMirror component = arrayType.getComponentType();
        helpers.requireList();

        PrimitiveBridge primitive = BRIDGES_BY_KIND.get(component.getKind());
        if (primitive != null) {
            helpers.requireArrayUnboxer(primitive.kind());
            helpers.requireListBoxer(primitive.kind());
            String read = primitive.arrayHelper() + "(" + listRead(primitive, path) + ")";
            String write = primitive.kind() == TypeKind.CHAR
                    ? wrapCharactersToStrings("toList(" + field + ")", helpers)
                    : "toList(" + field + ")";
            return new ValueCodec(read, write, NULL_SEQUENCE_PLACEHOLDER);
        }

        if (!(component instanceof DeclaredType declaredComponent)) {
            return null;
        }

        if (this.stringType != null && this.types.isSameType(this.types.erasure(declaredComponent), this.stringType)) {
            helpers.requireArrays();
            String read = DOCUMENT + ".getStringList(" + path + ").toArray(new String[0])";
            return new ValueCodec(read, "Arrays.asList(" + field + ")", NULL_SEQUENCE_PLACEHOLDER);
        }

        PrimitiveBridge boxed = BRIDGES_BY_BOXED_NAME.get(canonicalName(declaredComponent));
        if (boxed == null) {
            return null;
        }
        helpers.requireArrays();
        String read = listRead(boxed, path) + ".toArray(new " + boxed.boxedSimpleName() + "[0])";
        String write = boxed.kind() == TypeKind.CHAR
                ? wrapCharactersToStrings("Arrays.asList(" + field + ")", helpers)
                : "Arrays.asList(" + field + ")";
        return new ValueCodec(read, write, NULL_SEQUENCE_PLACEHOLDER);
    }

    /**
     * Builds the binding for a declared (reference) member type.
     *
     * @param declaredType the member type
     * @param path         quoted configuration path literal
     * @param field        expression addressing the generated field
     * @param helpers      helper accumulator
     * @return the binding, or {@code null} when the type is unsupported
     */
    private ValueCodec declaredCodec(DeclaredType declaredType, String path, String field, GeneratedHelpers helpers) {
        TypeMirror erasure = this.types.erasure(declaredType);

        if (this.stringType != null && this.types.isSameType(erasure, this.stringType)) {
            return new ValueCodec(DOCUMENT + ".getString(" + path + ")", field, NULL_SCALAR_PLACEHOLDER);
        }

        PrimitiveBridge boxed = BRIDGES_BY_BOXED_NAME.get(canonicalName(declaredType));
        if (boxed != null) {
            return scalarCodec(boxed, true, path, field, helpers);
        }

        if (this.listErasure != null && this.types.isSameType(erasure, this.listErasure)) {
            return listCodec(declaredType, path, field, helpers);
        }

        if (this.configurationSerializableType != null
                && this.types.isAssignable(erasure, this.configurationSerializableType)) {
            String read = DOCUMENT + ".getSerializable(" + path + ", " + erasure + ".class)";
            return new ValueCodec(read, field, NULL_SCALAR_PLACEHOLDER);
        }

        return null;
    }

    /**
     * Builds the binding for a {@code List<E>} member.
     *
     * @param listType the member type, whose erasure is {@link List}
     * @param path     quoted configuration path literal
     * @param field    expression addressing the generated field
     * @param helpers  helper accumulator
     * @return the binding, or {@code null} when the element type is unsupported
     */
    private ValueCodec listCodec(DeclaredType listType, String path, String field, GeneratedHelpers helpers) {
        List<? extends TypeMirror> arguments = listType.getTypeArguments();
        if (arguments.size() != 1 || !(arguments.getFirst() instanceof DeclaredType element)) {
            return null;
        }
        helpers.requireList();

        if (this.stringType != null && this.types.isSameType(this.types.erasure(element), this.stringType)) {
            return new ValueCodec(DOCUMENT + ".getStringList(" + path + ")", field, NULL_SEQUENCE_PLACEHOLDER);
        }

        PrimitiveBridge boxed = BRIDGES_BY_BOXED_NAME.get(canonicalName(element));
        if (boxed == null) {
            return null;
        }
        String write = boxed.kind() == TypeKind.CHAR ? wrapCharactersToStrings(field, helpers) : field;
        return new ValueCodec(listRead(boxed, path), write, NULL_SEQUENCE_PLACEHOLDER);
    }

    /**
     * Renders the Bukkit list accessor call for a primitive bridge.
     *
     * @param bridge the bridge to use
     * @param path   quoted configuration path literal
     * @return the accessor expression
     */
    private static String listRead(PrimitiveBridge bridge, String path) {
        return DOCUMENT + "." + bridge.listGetter() + "(" + path + ")";
    }

    /**
     * Wraps a {@code List<Character>} expression so it is persisted as single character strings.
     *
     * @param charactersExpression expression yielding a {@code List<Character>}
     * @param helpers              helper accumulator
     * @return the wrapped expression
     */
    private static String wrapCharactersToStrings(String charactersExpression, GeneratedHelpers helpers) {
        helpers.requireCharactersToStrings();
        return "charactersToStrings(" + charactersExpression + ")";
    }

    // =============================================================================================
    // Code generation
    // =============================================================================================

    /**
     * Writes the generated configuration class.
     *
     * @param sourceElement the annotated type
     * @param packageName   package the class is generated into
     * @param className     simple name of the generated class
     * @param extension     default file extension, including the leading dot
     * @param codecs        resolved bindings in declaration order
     * @param helpers       helper methods the generated class needs
     * @throws IOException when the source file cannot be written
     */
    private void writeConfigClass(TypeElement sourceElement, String packageName, String className, String extension,
                                  Map<ConfigItem, ValueCodec> codecs, GeneratedHelpers helpers) throws IOException {
        ImportScope scope = new ImportScope(packageName);
        String yamlConfiguration = scope.reference(BUKKIT_YAML_CONFIGURATION);
        String invalidConfiguration = scope.reference(BUKKIT_INVALID_CONFIGURATION_EXCEPTION);
        String configurationSection = helpers.needsReadChar() ? scope.reference(BUKKIT_CONFIGURATION_SECTION) : null;
        scope.reference("java.io.File");
        scope.reference("java.io.IOException");
        scope.reference("java.util.concurrent.CompletableFuture");
        if (helpers.needsList()) {
            scope.reference("java.util.List");
        }
        if (helpers.needsArrayList()) {
            scope.reference("java.util.ArrayList");
        }
        if (helpers.needsArrays()) {
            scope.reference("java.util.Arrays");
        }

        List<String> implementedInterfaces = new ArrayList<>();
        for (String interfaceName : inheritedInterfaces(sourceElement)) {
            implementedInterfaces.add(scope.reference(interfaceName));
        }
        implementedInterfaces.add(scope.reference(GENERATED_CONFIG_INTERFACE));

        List<DefaultSeed> seeds = resolveDefaultSeeds(sourceElement, packageName, codecs.keySet());
        String declarationReference = seeds.isEmpty()
                ? null
                : scope.reference(sourceElement.getQualifiedName().toString());
        if (seeds.stream().anyMatch(seed -> seed.copy() == CopyStrategy.LIST)) {
            scope.reference("java.util.ArrayList");
        }

        JavaFileObject sourceFile = processingEnv.getFiler()
                .createSourceFile(packageName + "." + className, sourceElement);

        try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();

            for (String importName : scope.importStatements()) {
                out.println("import " + importName + ";");
            }
            out.println();

            out.println("/**");
            out.println(" * GENERATED FILE. DO NOT EDIT.");
            out.println(" * <p>");
            out.println(" * Generated by " + ConfigContractProcessor.class.getName() + " from "
                    + sourceElement.getQualifiedName() + ".");
            out.println(" */");
            out.println("public class " + className + " implements " + String.join(", ", implementedInterfaces) + " {");
            out.println();
            out.println("    /** Default file name used when the owner does not provide one. */");
            out.println("    public static final String DEFAULT_FILENAME = \"" + className + extension + "\";");
            out.println();
            out.println("    private File file;");
            out.println("    private " + yamlConfiguration + " " + DOCUMENT_FIELD + ";");
            out.println();

            writeFields(out, codecs.keySet());
            writeConstructorAndFactory(out, className, yamlConfiguration, declarationReference, seeds);
            writeAccessors(out, codecs.keySet());
            writeLoad(out, codecs, invalidConfiguration);
            writeSave(out, codecs);
            writeLifecycle(out);
            helpers.write(out, configurationSection);

            out.println("}");
        }
    }

    /**
     * Lists the interfaces a generated configuration has to carry over from its declaration.
     * <p>
     * When the annotated element is an interface the generated class implements it directly;
     * otherwise it implements every interface the annotated class implements, so the generated twin
     * stays substitutable wherever the declaration was.
     *
     * @param sourceElement the annotated type
     * @return fully qualified interface names, in declaration order and without duplicates
     */
    private Set<String> inheritedInterfaces(TypeElement sourceElement) {
        Set<String> interfaceNames = new LinkedHashSet<>();
        if (sourceElement.getKind() == ElementKind.INTERFACE) {
            interfaceNames.add(sourceElement.getQualifiedName().toString());
            return interfaceNames;
        }

        for (TypeMirror superInterface : sourceElement.getInterfaces()) {
            if (superInterface instanceof DeclaredType declaredType
                    && declaredType.asElement() instanceof TypeElement interfaceElement) {
                interfaceNames.add(interfaceElement.getQualifiedName().toString());
            }
        }
        return interfaceNames;
    }

    /**
     * Emits the backing field for every configuration member.
     *
     * @param out   target writer
     * @param items configuration members in declaration order
     */
    private static void writeFields(PrintWriter out, Set<ConfigItem> items) {
        for (ConfigItem item : items) {
            out.println("    private " + sourceForm(item.type()) + " " + item.fieldName() + ";");
        }
        out.println();
    }

    /**
     * Emits the no-arg constructor, {@code init} and the {@code create} factory.
     * <p>
     * When the declaration is a class that can be instantiated, the constructor seeds every member
     * from a throwaway instance of it, which is what makes the defaults written into the
     * declaration's field initializers survive into the generated configuration. When the
     * declaration is an interface, the constructor instead invokes its {@code default} methods
     * through the {@code Declaration.super} call form, which is legal because the generated class
     * implements the declaration directly.
     *
     * @param out                  target writer
     * @param className            simple name of the generated class
     * @param yamlConfiguration    reference to the Bukkit YAML document type
     * @param declarationReference reference to the annotated type, or {@code null} when nothing is
     *                             seeded
     * @param seeds                the members to seed, in declaration order
     */
    private static void writeConstructorAndFactory(PrintWriter out, String className, String yamlConfiguration,
                                                   String declarationReference, List<DefaultSeed> seeds) {
        boolean seedsFromInstance = seeds.stream().anyMatch(seed -> !seed.viaInterfaceSuper());
        if (seeds.isEmpty()) {
            out.println("    public " + className + "() {");
            out.println("    }");
        } else {
            out.println("    /**");
            out.println("     * Creates a configuration pre-filled with the defaults declared by");
            out.println("     * {@link " + declarationReference + "}.");
            out.println("     * <p>");
            if (seedsFromInstance) {
                out.println("     * The values are read off a throwaway instance of the declaration, so the defaults");
                out.println("     * written into its field initializers are the ones a configuration starts with and");
                out.println("     * the ones {@link #save()} writes when the file does not exist yet.");
            } else {
                out.println("     * The values are read through the declaration's default methods, so the defaults");
                out.println("     * returned by those methods are the ones a configuration starts with and the ones");
                out.println("     * {@link #save()} writes when the file does not exist yet.");
            }
            out.println("     */");
            out.println("    public " + className + "() {");
            if (seedsFromInstance) {
                out.println("        " + declarationReference + " " + TEMPLATE_VARIABLE + " = new "
                        + declarationReference + "();");
            }
            for (DefaultSeed seed : seeds) {
                writeSeed(out, seed, declarationReference);
            }
            out.println("    }");
        }
        out.println();
        out.println("    /**");
        out.println("     * Creates a configuration already bound to {@code file}.");
        out.println("     *");
        out.println("     * @param file the backing YAML file");
        out.println("     * @return the new configuration");
        out.println("     */");
        out.println("    public static " + className + " create(File file) {");
        out.println("        " + className + " configuration = new " + className + "();");
        out.println("        configuration.init(file);");
        out.println("        return configuration;");
        out.println("    }");
        out.println();
        out.println("    @Override");
        out.println("    public void init(File file) {");
        out.println("        this.file = file;");
        out.println("        this." + DOCUMENT_FIELD + " = new " + yamlConfiguration + "();");
        out.println("    }");
        out.println();
    }

    /**
     * Emits the assignment that seeds a single member in the generated constructor.
     * <p>
     * Mutable defaults are copied, so a generated configuration can never share list or array state
     * with the declaration - be it the throwaway instance or a default method's return value - nor
     * with a second generated configuration.
     *
     * @param out                  target writer
     * @param seed                 the member to seed
     * @param declarationReference reference to the annotated type, used for the
     *                             {@code Declaration.super} call form of interface seeds
     */
    private static void writeSeed(PrintWriter out, DefaultSeed seed, String declarationReference) {
        ConfigItem item = seed.item();
        String read = seed.viaInterfaceSuper()
                ? declarationReference + ".super." + seed.read()
                : seed.read();
        if (seed.copy() == CopyStrategy.DIRECT) {
            out.println("        this." + item.fieldName() + " = " + read + ";");
            return;
        }

        String local = "default" + capitalize(item.fieldName());
        String copy = seed.copy() == CopyStrategy.LIST ? "new ArrayList<>(" + local + ")" : local + ".clone()";
        out.println("        " + sourceForm(item.type()) + " " + local + " = " + read + ";");
        out.println("        this." + item.fieldName() + " = " + local + " == null ? null : " + copy + ";");
    }

    /**
     * Emits the accessor for every configuration member.
     * <p>
     * Members declared as methods keep their exact name, so the generated class can implement the
     * annotated interface; members declared as fields get a JavaBeans accessor, which is the shape
     * a read-only view interface is expected to declare. Either way the accessor reads the backing
     * field, whose name is the property name rather than the accessor name.
     *
     * @param out   target writer
     * @param items configuration members in declaration order
     */
    private static void writeAccessors(PrintWriter out, Set<ConfigItem> items) {
        for (ConfigItem item : items) {
            String accessorName = item.declaredAsMethod()
                    ? item.memberName()
                    : (item.type().getKind() == TypeKind.BOOLEAN ? "is" : "get") + capitalize(item.fieldName());

            out.println("    public " + sourceForm(item.type()) + " " + accessorName + "() {");
            out.println("        return this." + item.fieldName() + ";");
            out.println("    }");
            out.println();
        }
    }

    /**
     * Emits {@code load()}, which reads every member that is present in the document.
     *
     * @param out                  target writer
     * @param codecs               resolved bindings in declaration order
     * @param invalidConfiguration reference to Bukkit's invalid configuration exception
     */
    private static void writeLoad(PrintWriter out, Map<ConfigItem, ValueCodec> codecs, String invalidConfiguration) {
        out.println("    @Override");
        out.println("    public void load() {");
        out.println("        try {");
        out.println("            if (this.file.exists()) {");
        out.println("                " + DOCUMENT + ".load(this.file);");
        out.println("            }");
        out.println("        } catch (IOException | " + invalidConfiguration + " exception) {");
        out.println("            throw new IllegalStateException(\"Unable to read \" + this.file, exception);");
        out.println("        }");
        out.println();

        for (Map.Entry<ConfigItem, ValueCodec> entry : codecs.entrySet()) {
            ConfigItem item = entry.getKey();
            out.println("        if (" + DOCUMENT + ".contains(" + quote(item.path()) + ")) {");
            out.println("            this." + item.fieldName() + " = " + entry.getValue().read() + ";");
            out.println("        }");
        }

        out.println("    }");
        out.println();
    }

    /**
     * Emits {@code save()}, which writes every member plus its comment and flushes the document.
     *
     * @param out    target writer
     * @param codecs resolved bindings in declaration order
     */
    private static void writeSave(PrintWriter out, Map<ConfigItem, ValueCodec> codecs) {
        out.println("    @Override");
        out.println("    public void save() {");

        for (Map.Entry<ConfigItem, ValueCodec> entry : codecs.entrySet()) {
            ConfigItem item = entry.getKey();
            ValueCodec codec = entry.getValue();
            String path = quote(item.path());

            if (codec.nullPlaceholder() == null) {
                out.println("        " + DOCUMENT + ".set(" + path + ", " + codec.write() + ");");
            } else {
                out.println("        if (this." + item.fieldName() + " != null) {");
                out.println("            " + DOCUMENT + ".set(" + path + ", " + codec.write() + ");");
                out.println("        } else {");
                out.println("            " + DOCUMENT + ".set(" + path + ", " + codec.nullPlaceholder() + ");");
                out.println("        }");
            }

            // Comments must be attached after the key exists, otherwise Bukkit drops them.
            if (!item.comments().isEmpty()) {
                String lines = item.comments().stream()
                        .map(ConfigContractProcessor::quote)
                        .collect(Collectors.joining(", "));
                out.println("        " + DOCUMENT + ".setComments(" + path + ", java.util.List.of(" + lines + "));");
            }
        }

        out.println("        try {");
        out.println("            " + DOCUMENT + ".save(this.file);");
        out.println("        } catch (IOException exception) {");
        out.println("            throw new IllegalStateException(\"Unable to write \" + this.file, exception);");
        out.println("        }");
        out.println("    }");
        out.println();
    }

    /**
     * Emits {@code loadOrCreate()} and the three asynchronous wrappers of {@code GeneratedConfig}.
     *
     * @param out target writer
     */
    private static void writeLifecycle(PrintWriter out) {
        out.println("    @Override");
        out.println("    public void loadOrCreate() {");
        out.println("        if (this.file.exists()) {");
        out.println("            load();");
        out.println("        } else {");
        out.println("            save();");
        out.println("        }");
        out.println("    }");
        out.println();
        out.println("    @Override");
        out.println("    public CompletableFuture<Void> loadAsync() {");
        out.println("        return CompletableFuture.runAsync(this::load);");
        out.println("    }");
        out.println();
        out.println("    @Override");
        out.println("    public CompletableFuture<Void> saveAsync() {");
        out.println("        return CompletableFuture.runAsync(this::save);");
        out.println("    }");
        out.println();
        out.println("    @Override");
        out.println("    public CompletableFuture<Void> loadOrCreateAsync() {");
        out.println("        return CompletableFuture.runAsync(this::loadOrCreate);");
        out.println("    }");
        out.println();
    }

    // =============================================================================================
    // Default seeding
    // =============================================================================================

    /**
     * Works out how the generated constructor can read each member's default off the declaration.
     * <p>
     * A class declaration is read through a throwaway instance; an interface declaration is read
     * through its {@code default} methods via the {@code Declaration.super} call form, which needs
     * no instance. Any member whose default cannot be reached is reported as a
     * {@link Diagnostic.Kind#WARNING} and simply not seeded: a configuration without defaults still
     * works, it just starts at the Java zero values.
     *
     * @param sourceElement the annotated type
     * @param targetPackage package the configuration is generated into
     * @param items         the configuration members, in declaration order
     * @return the seedable members, possibly empty
     */
    private List<DefaultSeed> resolveDefaultSeeds(TypeElement sourceElement, String targetPackage,
                                                  Set<ConfigItem> items) {
        if (sourceElement.getKind() == ElementKind.INTERFACE) {
            return resolveInterfaceSeeds(sourceElement, items);
        }
        if (!canInstantiate(sourceElement, targetPackage)) {
            return List.of();
        }

        List<DefaultSeed> seeds = new ArrayList<>(items.size());
        for (ConfigItem item : items) {
            String read = resolveDefaultRead(sourceElement, item, targetPackage);
            if (read == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Default value of @ConfigValue member '" + item.memberName() + "' cannot be seeded:"
                                + " it is not reachable from the generated configuration in package '"
                                + targetPackage + "'. Add an accessible getter, or widen the member's access.",
                        item.source());
                continue;
            }
            seeds.add(new DefaultSeed(item, read, copyStrategyFor(item.type()), false));
        }
        return seeds;
    }

    /**
     * Works out how the generated constructor can read each member's default off an annotated
     * interface.
     * <p>
     * The generated class implements the annotated interface directly, so inside its constructor
     * {@code Declaration.super.member()} is a legal invocation of the interface's {@code default}
     * method - the defaults need no instance of anything. A member the interface leaves abstract
     * has no default to read; it is reported as a {@link Diagnostic.Kind#WARNING} and not seeded,
     * so it starts at the Java zero value exactly like an unseeded class member.
     *
     * @param sourceElement the annotated interface
     * @param items         the configuration members, in declaration order
     * @return the seedable members, possibly empty
     */
    private List<DefaultSeed> resolveInterfaceSeeds(TypeElement sourceElement, Set<ConfigItem> items) {
        List<DefaultSeed> seeds = new ArrayList<>(items.size());
        for (ConfigItem item : items) {
            if (!item.declaredAsMethod() || !seenAsDefaultMethod(sourceElement, item)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Default value of @ConfigValue member '" + item.memberName() + "' cannot be seeded:"
                                + " interface " + sourceElement.getQualifiedName() + " provides no default"
                                + " method implementing it. Declare the member as a default method returning"
                                + " the desired value; until then the generated configuration starts at the"
                                + " Java zero value.",
                        item.source());
                continue;
            }
            seeds.add(new DefaultSeed(item, item.memberName() + "()", copyStrategyFor(item.type()), true));
        }
        return seeds;
    }

    /**
     * Decides whether an interface member resolves to a {@code default} method from the annotated
     * interface's point of view.
     * <p>
     * The check runs against {@link Elements#getAllMembers}, not against the declaring element:
     * a default inherited from a superinterface counts, while a default the annotated interface
     * re-declares as abstract does not - {@code Declaration.super} resolves exactly the member the
     * declaration itself sees, so the seeding decision has to as well.
     *
     * @param interfaceElement the annotated interface
     * @param item             the member to look up
     * @return {@code true} when {@code interfaceElement.super.member()} invokes a default method
     */
    private boolean seenAsDefaultMethod(TypeElement interfaceElement, ConfigItem item) {
        for (Element member : this.elements.getAllMembers(interfaceElement)) {
            if (member.getKind() == ElementKind.METHOD
                    && member instanceof ExecutableElement method
                    && method.getParameters().isEmpty()
                    && method.getSimpleName().contentEquals(item.memberName())) {
                return method.getModifiers().contains(Modifier.DEFAULT);
            }
        }
        return false;
    }

    /**
     * Decides whether the generated configuration can call the declaration's no-arg constructor.
     * <p>
     * A class that declares no constructor at all has an implicit one carrying the class's own
     * access level, which counts.
     *
     * @param sourceElement the annotated type
     * @param targetPackage package the configuration is generated into
     * @return {@code true} when {@code new Declaration()} is legal from the generated class
     */
    private boolean canInstantiate(TypeElement sourceElement, String targetPackage) {
        if (sourceElement.getModifiers().contains(Modifier.ABSTRACT)) {
            warnNotSeeded(sourceElement, "it is abstract");
            return false;
        }
        if (sourceElement.getNestingKind().isNested() && !sourceElement.getModifiers().contains(Modifier.STATIC)) {
            warnNotSeeded(sourceElement, "it is an inner class and has no enclosing instance here");
            return false;
        }
        if (!isAccessibleFrom(sourceElement, targetPackage)) {
            warnNotSeeded(sourceElement, "the type is not accessible from package '" + targetPackage + "'");
            return false;
        }

        List<ExecutableElement> constructors = ElementFilter.constructorsIn(sourceElement.getEnclosedElements());
        if (constructors.isEmpty()) {
            return true;
        }
        for (ExecutableElement constructor : constructors) {
            if (constructor.getParameters().isEmpty() && isAccessibleFrom(constructor, targetPackage)) {
                return true;
            }
        }

        warnNotSeeded(sourceElement, "it has no accessible no-arg constructor");
        return false;
    }

    /**
     * Reports that a declaration's defaults will not be carried into the generated configuration.
     *
     * @param sourceElement the annotated type
     * @param reason        why seeding is impossible, phrased to follow "because"
     */
    private void warnNotSeeded(TypeElement sourceElement, String reason) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "Defaults declared by " + sourceElement.getQualifiedName() + " will not be seeded into its"
                        + " generated configuration because " + reason + ". The generated configuration will"
                        + " start at the Java zero values.", sourceElement);
    }

    /**
     * Builds the expression reading one member's default off the declaration instance.
     * <p>
     * A member declared as a method is called directly. A member declared as a field prefers a
     * matching accessor and only falls back to reading the field when that field is reachable.
     *
     * @param sourceElement the annotated type
     * @param item          the member to read
     * @param targetPackage package the configuration is generated into
     * @return the read expression, or {@code null} when the member cannot be reached
     */
    private String resolveDefaultRead(TypeElement sourceElement, ConfigItem item, String targetPackage) {
        if (item.declaredAsMethod()) {
            return isAccessibleFrom(item.source(), targetPackage)
                    ? TEMPLATE_VARIABLE + "." + item.memberName() + "()"
                    : null;
        }

        String accessor = findAccessor(sourceElement, item, targetPackage);
        if (accessor != null) {
            return TEMPLATE_VARIABLE + "." + accessor + "()";
        }
        return isAccessibleFrom(item.source(), targetPackage)
                ? TEMPLATE_VARIABLE + "." + item.memberName()
                : null;
    }

    /**
     * Finds an accessible no-arg accessor on the declaration that yields a member's value.
     * <p>
     * Candidates follow the JavaBeans shape, with {@code isX} preferred over {@code getX} for
     * booleans, and a record style {@code x()} accepted last. Inherited accessors count.
     *
     * @param sourceElement the annotated type
     * @param item          the member to read
     * @param targetPackage package the configuration is generated into
     * @return the accessor name, or {@code null} when there is none
     */
    private String findAccessor(TypeElement sourceElement, ConfigItem item, String targetPackage) {
        String capitalized = capitalize(item.memberName());
        boolean booleanMember = item.type().getKind() == TypeKind.BOOLEAN
                || Boolean.class.getCanonicalName().equals(typeName(item.type()));
        List<String> candidates = booleanMember
                ? List.of("is" + capitalized, "get" + capitalized, item.memberName())
                : List.of("get" + capitalized, item.memberName());

        for (String candidate : candidates) {
            for (Element member : this.elements.getAllMembers(sourceElement)) {
                if (member.getKind() != ElementKind.METHOD || !(member instanceof ExecutableElement method)) {
                    continue;
                }
                if (!method.getParameters().isEmpty()
                        || !method.getSimpleName().contentEquals(candidate)
                        || !isAccessibleFrom(method, targetPackage)
                        || !this.types.isAssignable(method.getReturnType(), item.type())) {
                    continue;
                }
                return candidate;
            }
        }
        return null;
    }

    /**
     * Applies Java's access rules to decide whether the generated class can reach a member.
     * <p>
     * The generated class is a separate top-level type that does not extend the declaration, so
     * {@code private} is never reachable and {@code protected} only through the shared package.
     *
     * @param element       the type or member to reach
     * @param targetPackage package the configuration is generated into
     * @return {@code true} when the element is reachable
     */
    private boolean isAccessibleFrom(Element element, String targetPackage) {
        Set<Modifier> modifiers = element.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) {
            return false;
        }
        if (modifiers.contains(Modifier.PUBLIC)) {
            return true;
        }
        return this.elements.getPackageOf(element).getQualifiedName().contentEquals(targetPackage);
    }

    /**
     * Decides whether a default has to be copied before it is handed to the generated configuration.
     *
     * @param type the member type
     * @return the copy strategy keeping mutable defaults unshared
     */
    private CopyStrategy copyStrategyFor(TypeMirror type) {
        if (type instanceof ArrayType) {
            return CopyStrategy.ARRAY;
        }
        if (type instanceof DeclaredType declaredType && this.listErasure != null
                && this.types.isSameType(this.types.erasure(declaredType), this.listErasure)) {
            return CopyStrategy.LIST;
        }
        return CopyStrategy.DIRECT;
    }

    // =============================================================================================
    // Utilities
    // =============================================================================================

    /**
     * Returns the canonical name of a type, ignoring type arguments.
     *
     * @param type the type to inspect
     * @return the canonical name, or an empty string when it cannot be determined
     */
    private static String typeName(TypeMirror type) {
        return type instanceof DeclaredType declaredType ? canonicalName(declaredType) : "";
    }

    /**
     * Renders a type the way generated source spells it: fully qualified and without type
     * annotations.
     * <p>
     * {@link TypeMirror#toString()} is not usable here because it carries {@code TYPE_USE}
     * annotations along - a declaration reading {@code default @NotNull String getHost()} would
     * otherwise stamp {@code java.lang.@org.jetbrains.annotations.NotNull String} onto every
     * generated field, accessor and local. Annotations on the declaration document the contract;
     * they are not part of the generated type.
     *
     * @param type the type to render
     * @return the annotation-free source form of the type
     */
    private static String sourceForm(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return type.getKind().toString().toLowerCase(Locale.ROOT);
        }
        if (type instanceof ArrayType arrayType) {
            return sourceForm(arrayType.getComponentType()) + "[]";
        }
        if (type instanceof DeclaredType declaredType) {
            String name = canonicalName(declaredType);
            List<? extends TypeMirror> arguments = declaredType.getTypeArguments();
            if (arguments.isEmpty()) {
                return name;
            }
            return arguments.stream()
                    .map(ConfigContractProcessor::sourceForm)
                    .collect(Collectors.joining(", ", name + "<", ">"));
        }
        return type.toString();
    }

    /**
     * Resolves the erasure of a type by name.
     *
     * @param qualifiedName fully qualified type name
     * @return the erased mirror, or {@code null} when the type is not on the compilation classpath
     */
    private TypeMirror erasureOf(String qualifiedName) {
        TypeElement typeElement = this.elements.getTypeElement(qualifiedName);
        return typeElement == null ? null : this.types.erasure(typeElement.asType());
    }

    /**
     * Returns the canonical name of a declared type, ignoring type arguments.
     *
     * @param declaredType the type to inspect
     * @return the canonical name, or an empty string when it cannot be determined
     */
    private static String canonicalName(DeclaredType declaredType) {
        return declaredType.asElement() instanceof TypeElement typeElement
                ? typeElement.getQualifiedName().toString()
                : "";
    }

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
                : this.elements.getElementValuesWithDefaults(mirror).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(memberName)) {
                Object value = entry.getValue().getValue();
                return expectedType.isInstance(value) ? expectedType.cast(value) : null;
            }
        }
        return null;
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
     * Splits a multi-line annotation comment into individual YAML comment lines.
     *
     * @param comment the raw comment
     * @return one entry per line, empty when the comment carries nothing
     */
    private static List<String> splitComment(String comment) {
        return comment.isEmpty() ? List.of() : List.of(comment.split("\\R", -1));
    }

    /**
     * Renders a Java string literal, escaping everything that would break the generated source.
     *
     * @param value the raw value
     * @return the quoted literal
     */
    private static String quote(String value) {
        StringBuilder literal = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> literal.append("\\\\");
                case '"' -> literal.append("\\\"");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> literal.append(character);
            }
        }
        return literal.append('"').toString();
    }

    /**
     * Upper-cases the first character of an identifier.
     *
     * @param value the identifier
     * @return the capitalised identifier
     */
    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Derives the name of the generated backing field from an accessor method name.
     * <p>
     * {@code getHost} backs a field {@code host} and {@code isUseSsl} backs {@code useSsl}; a
     * method without a {@code get} or {@code is} prefix keeps its own name. The configuration path
     * is deliberately not consulted: a key such as {@code useSSL} is fine YAML but a poor Java
     * identifier, so the field name always comes from the Java accessor.
     *
     * @param methodName name of the accessor method
     * @return the property name the backing field uses
     */
    private static String propertyName(String methodName) {
        if (methodName.length() > 3 && methodName.startsWith("get")
                && Character.isUpperCase(methodName.charAt(3))) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.length() > 2 && methodName.startsWith("is")
                && Character.isUpperCase(methodName.charAt(2))) {
            return decapitalize(methodName.substring(2));
        }
        return methodName;
    }

    /**
     * Applies the JavaBeans decapitalisation rule to a property name fragment.
     * <p>
     * The first character is lower-cased unless the second one is a capital as well, so
     * {@code Host} becomes {@code host} while an acronym such as {@code URL} survives intact.
     *
     * @param value non-empty identifier fragment starting with a capital
     * @return the decapitalised property name
     */
    private static String decapitalize(String value) {
        if (value.length() > 1 && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Returns {@code value} unless it carries nothing.
     *
     * @param value         the candidate value
     * @param fallbackValue the value used when {@code value} is {@code null} or empty
     * @return the resolved value
     */
    private static String orDefault(String value, String fallbackValue) {
        return value == null || value.isEmpty() ? fallbackValue : value;
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
     * Decides, per generated file, whether a type is imported and referenced by its simple name or
     * spelled out in full.
     * <p>
     * The first type to claim a simple name wins; a later type with the same simple name but a
     * different package is referenced by its fully qualified name, so generated sources can never
     * collide on an import.
     */
    private static final class ImportScope {

        private final String targetPackage;
        private final Map<String, String> claimedSimpleNames = new LinkedHashMap<>();

        /**
         * Creates a scope for one generated file.
         *
         * @param targetPackage package the file is generated into
         */
        ImportScope(String targetPackage) {
            this.targetPackage = targetPackage;
        }

        /**
         * Registers a type and returns the shortest legal way to reference it.
         *
         * @param qualifiedName fully qualified type name
         * @return the simple name when it is still free, the qualified name otherwise
         */
        String reference(String qualifiedName) {
            String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            String owner = this.claimedSimpleNames.putIfAbsent(simple, qualifiedName);
            return owner == null || owner.equals(qualifiedName) ? simple : qualifiedName;
        }

        /**
         * Computes the imports the generated file needs. Types from the target package and from
         * {@code java.lang} are already in scope and never imported.
         *
         * @return a sorted, de-duplicated import list
         */
        Set<String> importStatements() {
            Set<String> imports = new TreeSet<>();
            for (String qualifiedName : this.claimedSimpleNames.values()) {
                String packageName = qualifiedName.substring(0, Math.max(0, qualifiedName.lastIndexOf('.')));
                if (!packageName.equals(this.targetPackage) && !packageName.equals("java.lang")
                        && !packageName.isEmpty()) {
                    imports.add(qualifiedName);
                }
            }
            return imports;
        }
    }

    /**
     * One {@code @ConfigValue} annotated member of a configuration contract.
     *
     * @param path             configuration path inside the YAML document
     * @param comments         comment lines attached to the path, one entry per line
     * @param type             declared type of the member
     * @param memberName       name of the field or method that declared the value, used wherever the
     *                         declaration itself is addressed
     * @param fieldName        name of the backing field in the generated class: the member name for a
     *                         field-declared value, the derived property name for a method-declared
     *                         one, so an accessor {@code getHost()} backs a field {@code host}
     * @param declaredAsMethod {@code true} when the member was declared as a method
     * @param source           the originating element, used for diagnostics
     */
    private record ConfigItem(String path, List<String> comments, TypeMirror type, String memberName,
                              String fieldName, boolean declaredAsMethod, Element source) {
    }

    /**
     * What the generated constructor has to do to keep a seeded default unshared.
     */
    private enum CopyStrategy {

        /** Immutable or primitive value, assigned straight across. */
        DIRECT,

        /** {@code List} value, rebuilt into a fresh {@code ArrayList}. */
        LIST,

        /** Array value, cloned. */
        ARRAY
    }

    /**
     * One member whose default the generated constructor seeds from the declaration.
     *
     * @param item              the member being seeded
     * @param read              expression reading the default; off the {@code defaults} instance for
     *                          a class declaration, or the bare no-arg invocation an interface seed
     *                          prefixes with {@code Declaration.super.} at write time
     * @param copy              how the value has to be copied before it is stored
     * @param viaInterfaceSuper {@code true} when the default is a default method invoked through
     *                          the {@code Declaration.super} call form
     */
    private record DefaultSeed(ConfigItem item, String read, CopyStrategy copy, boolean viaInterfaceSuper) {
    }

    /**
     * How a single member is read from and written back to the YAML document.
     *
     * @param read            expression producing the member value from the document
     * @param write           expression producing the object handed to {@code ConfigurationSection#set}
     * @param nullPlaceholder value written when the member is {@code null}, or {@code null} for
     *                        primitive members that can never be {@code null}
     */
    private record ValueCodec(String read, String write, String nullPlaceholder) {
    }

    /**
     * Binds one Java primitive to the Bukkit accessors able to carry it.
     *
     * @param kind            the primitive kind
     * @param primitiveName   the primitive keyword, for example {@code short}
     * @param boxedName       fully qualified name of the boxed type
     * @param boxedSimpleName simple name of the boxed type
     * @param scalarGetter    scalar accessor on {@code ConfigurationSection}; {@code null} for
     *                        {@code char}, which has no accessor of its own and is read as text
     * @param narrowingCast   cast prefix applied to the scalar accessor result, empty when the
     *                        accessor already returns the right type
     * @param listGetter      list accessor on {@code ConfigurationSection}
     * @param arrayHelper     name of the generated helper converting the list into a primitive array
     */
    private record PrimitiveBridge(TypeKind kind, String primitiveName, String boxedName, String boxedSimpleName,
                                   String scalarGetter, String narrowingCast, String listGetter, String arrayHelper) {
    }

    /**
     * Accumulates the private helper methods and imports a generated configuration class needs.
     * <p>
     * Only the helpers that are actually referenced are emitted, so generated sources never carry
     * unused members.
     */
    private static final class GeneratedHelpers {

        private final Set<TypeKind> arrayUnboxers = EnumSet.noneOf(TypeKind.class);
        private final Set<TypeKind> listBoxers = EnumSet.noneOf(TypeKind.class);
        private boolean readChar;
        private boolean charactersToStrings;
        private boolean list;
        private boolean arrays;

        /** Requests the {@code readChar} helper and the {@code ConfigurationSection} import. */
        void requireReadChar() {
            this.readChar = true;
        }

        /** Requests the {@code charactersToStrings} helper. */
        void requireCharactersToStrings() {
            this.charactersToStrings = true;
            this.list = true;
        }

        /** Requests the {@code java.util.List} import. */
        void requireList() {
            this.list = true;
        }

        /** Requests the {@code java.util.Arrays} import. */
        void requireArrays() {
            this.arrays = true;
        }

        /**
         * Requests the list to primitive array helper for a primitive kind.
         *
         * @param kind the primitive kind
         */
        void requireArrayUnboxer(TypeKind kind) {
            this.arrayUnboxers.add(kind);
            this.list = true;
        }

        /**
         * Requests the primitive array to list helper for a primitive kind.
         *
         * @param kind the primitive kind
         */
        void requireListBoxer(TypeKind kind) {
            this.listBoxers.add(kind);
            this.list = true;
        }

        /** @return {@code true} when the {@code readChar} helper is needed */
        boolean needsReadChar() {
            return this.readChar;
        }

        /** @return {@code true} when {@code java.util.List} must be imported */
        boolean needsList() {
            return this.list;
        }

        /** @return {@code true} when {@code java.util.ArrayList} must be imported */
        boolean needsArrayList() {
            return this.charactersToStrings || !this.listBoxers.isEmpty();
        }

        /** @return {@code true} when {@code java.util.Arrays} must be imported */
        boolean needsArrays() {
            return this.arrays;
        }

        /**
         * Emits every requested helper method.
         *
         * @param out                  target writer
         * @param configurationSection reference to Bukkit's section interface; only consulted when
         *                             {@link #needsReadChar()} holds
         */
        void write(PrintWriter out, String configurationSection) {
            if (this.readChar) {
                out.println("    /**");
                out.println("     * Reads a single character, falling back when the value is absent or empty.");
                out.println("     *");
                out.println("     * @param section  the document to read from");
                out.println("     * @param path     the configuration path");
                out.println("     * @param fallback value used when the path holds no usable text");
                out.println("     * @return the first character of the stored text, or {@code fallback}");
                out.println("     */");
                out.println("    private static char readChar(" + configurationSection
                        + " section, String path, char fallback) {");
                out.println("        String raw = section.getString(path);");
                out.println("        return raw == null || raw.isEmpty() ? fallback : raw.charAt(0);");
                out.println("    }");
                out.println();
            }

            if (this.charactersToStrings) {
                out.println("    /**");
                out.println("     * Converts characters into single character strings so YAML can store them.");
                out.println("     *");
                out.println("     * @param values the characters to convert");
                out.println("     * @return one string per character");
                out.println("     */");
                out.println("    private static List<String> charactersToStrings(List<Character> values) {");
                out.println("        List<String> strings = new ArrayList<>(values.size());");
                out.println("        for (Character value : values) {");
                out.println("            strings.add(value == null ? \"\" : String.valueOf(value.charValue()));");
                out.println("        }");
                out.println("        return strings;");
                out.println("    }");
                out.println();
            }

            for (PrimitiveBridge bridge : PRIMITIVE_BRIDGES) {
                if (!this.arrayUnboxers.contains(bridge.kind())) {
                    continue;
                }
                out.println("    /**");
                out.println("     * Unboxes a list into a {@code " + bridge.primitiveName() + "[]}.");
                out.println("     *");
                out.println("     * @param values the values to unbox");
                out.println("     * @return the primitive array");
                out.println("     */");
                out.println("    private static " + bridge.primitiveName() + "[] " + bridge.arrayHelper()
                        + "(List<" + bridge.boxedSimpleName() + "> values) {");
                out.println("        " + bridge.primitiveName() + "[] array = new "
                        + bridge.primitiveName() + "[values.size()];");
                out.println("        for (int index = 0; index < array.length; index++) {");
                out.println("            array[index] = values.get(index);");
                out.println("        }");
                out.println("        return array;");
                out.println("    }");
                out.println();
            }

            for (PrimitiveBridge bridge : PRIMITIVE_BRIDGES) {
                if (!this.listBoxers.contains(bridge.kind())) {
                    continue;
                }
                out.println("    /**");
                out.println("     * Boxes a {@code " + bridge.primitiveName()
                        + "[]} into a list so YAML can store it.");
                out.println("     *");
                out.println("     * @param values the values to box");
                out.println("     * @return the boxed list");
                out.println("     */");
                out.println("    private static List<" + bridge.boxedSimpleName() + "> toList("
                        + bridge.primitiveName() + "[] values) {");
                out.println("        List<" + bridge.boxedSimpleName() + "> list = new ArrayList<>(values.length);");
                out.println("        for (" + bridge.primitiveName() + " value : values) {");
                out.println("            list.add(value);");
                out.println("        }");
                out.println("        return list;");
                out.println("    }");
                out.println();
            }
        }
    }
}
