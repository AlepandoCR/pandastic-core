package pandastic.studio.core.processor.contract;

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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Build-time generator for the reflection bridge contract.
 * <p>
 * The processor scans the bridge surface declared with {@code @BridgeApi} and {@code @BridgeMethod},
 * validates that every method maps to exactly one deterministic contract id, and emits two sources
 * into {@value #GENERATED_PKG}:
 * <ul>
 *   <li>{@code GeneratedBridgeContract} - anchor keys, contract id constants and method specs;</li>
 *   <li>{@code GeneratedBridgeIds} - readable aliases for the same ids, grouped per namespace.</li>
 * </ul>
 * <p>
 * Generation is fully deterministic: namespaces, methods and aliases are sorted so two builds of
 * unchanged sources produce byte identical output.
 */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class BridgeContractProcessor extends AbstractProcessor {

    // ---------------------------------------------------------------------------------------------
    // Fully qualified names. Single source of truth.
    //
    // This module declares no dependency on pandastic-core-bridge-contract on purpose: that module applies
    // this processor, so a compile dependency back onto it would close a Gradle task cycle. The
    // annotation types are therefore unavailable as classes and every name below has to stay a
    // literal, resolved at processing time through the javax.lang.model mirror API.
    //
    // Declare each name exactly once here and derive everything else - the supported annotation
    // set, the per-annotation lookups - from these constants.
    // ---------------------------------------------------------------------------------------------

    /** Package holding the bridge contract annotations. */
    private static final String CONTRACT_PKG = "pandastic.studio.core.bootstrap.api.contract";

    /** Marks a type as part of the reflection bridge surface. */
    private static final String BRIDGE_API_ANNOTATION = CONTRACT_PKG + ".BridgeApi";

    /** Marks a method as bridgeable across classloaders. */
    private static final String BRIDGE_METHOD_ANNOTATION = CONTRACT_PKG + ".BridgeMethod";

    /** Marks a {@code static final String} constant as a bridge anchor key. */
    private static final String BRIDGE_ANCHOR_KEY_ANNOTATION = CONTRACT_PKG + ".BridgeAnchorKey";

    /** Declares the bridge protocol version of a compilation unit. */
    private static final String BRIDGE_CONTRACT_VERSION_ANNOTATION = CONTRACT_PKG + ".BridgeContractVersion";

    /** Disambiguates overloaded bridge methods with an explicit key. */
    private static final String BRIDGE_OVERLOAD_KEY_ANNOTATION = CONTRACT_PKG + ".BridgeOverloadKey";

    /** Name of the {@code BridgeStability} constant demanding an explicit, hand written id. */
    private static final String STABILITY_STABLE_ID = "STABLE_ID";

    /** Name of the {@code SignaturePolicy} constant keying ids on arity alone. */
    private static final String SIGNATURE_POLICY_ARITY_ONLY = "ARITY_ONLY";

    /** Name of the {@code OverloadPolicy} constant forbidding overloads outright. */
    private static final String OVERLOAD_POLICY_ERROR = "ERROR";

    /**
     * Package the contract sources are generated into.
     * <p>
     * This also has to stay a literal: the package hosts no type at build time, and
     * {@code pandastic-core-bootstrap} imports the generated types from exactly this package. Changing it
     * requires updating those imports as well.
     */
    private static final String GENERATED_PKG = "pandastic.studio.core.bootstrap.api";

    /** Simple name of the generated contract class. Imported by {@code pandastic-core-bootstrap}. */
    private static final String GENERATED_NAME = "GeneratedBridgeContract";

    /** Simple name of the generated alias class. Imported by {@code pandastic-core-bootstrap}. */
    private static final String GENERATED_IDS_NAME = "GeneratedBridgeIds";

    private Types types;
    private Elements elements;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        this.types = processingEnvironment.getTypeUtils();
        this.elements = processingEnvironment.getElementUtils();
    }

    /**
     * Declares the annotations this processor claims.
     * <p>
     * Overriding the method instead of using {@code @SupportedAnnotationTypes} keeps the constants
     * above the only place each name is spelled out; the annotation only accepts literals, which
     * would duplicate them.
     *
     * @return the supported annotation names
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                BRIDGE_API_ANNOTATION,
                BRIDGE_METHOD_ANNOTATION,
                BRIDGE_ANCHOR_KEY_ANNOTATION,
                BRIDGE_CONTRACT_VERSION_ANNOTATION,
                BRIDGE_OVERLOAD_KEY_ANNOTATION);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        if (roundEnvironment.processingOver()) {
            return false;
        }

        Map<String, AnchorKey> anchorKeys = collectAnchorKeys(roundEnvironment);
        List<ApiSurface> apis = collectApis(roundEnvironment);
        Integer protocol = collectProtocolVersion(roundEnvironment);

        if (anchorKeys.isEmpty() && apis.isEmpty() && protocol == null) {
            return false;
        }

        // Validate contract determinism and reject ambiguous or duplicate ids before writing.
        boolean valid = validateNoDuplicateContractIds(apis);
        valid &= validateOverloadPolicies(apis);
        if (!valid) {
            return false;
        }

        try {
            writeGenerated(anchorKeys, apis, protocol);
            writeGeneratedIds(apis);
        } catch (IOException exception) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed generating " + GENERATED_NAME + ": " + exception);
        }

        return false;
    }

    // =============================================================================================
    // Validation
    // =============================================================================================

    /**
     * Ensures no two bridge methods resolve to the same contract id.
     *
     * @param apis the collected bridge surface
     * @return {@code true} when every id is unique
     */
    private boolean validateNoDuplicateContractIds(List<ApiSurface> apis) {
        boolean valid = true;
        Map<String, MethodSpec> firstById = new HashMap<>();

        for (ApiSurface api : apis) {
            for (MethodSpec methodSpec : api.methods()) {
                MethodSpec first = firstById.putIfAbsent(methodSpec.id, methodSpec);
                if (first != null) {
                    valid = false;
                    error(methodSpec.source,
                            "Duplicate bridge contract id '" + methodSpec.id + "'. "
                                    + "First defined at " + formatElement(first.source) + ". "
                                    + "Fix by using stability=STABLE_ID with an explicit id, "
                                    + "or change SignaturePolicy/BridgeOverloadKey to disambiguate.");
                }
            }
        }

        return valid;
    }

    /**
     * Ensures overloaded bridge methods respect their declared overload and signature policies.
     *
     * @param apis the collected bridge surface
     * @return {@code true} when every overload group is unambiguous
     */
    private boolean validateOverloadPolicies(List<ApiSurface> apis) {
        boolean valid = true;

        for (ApiSurface api : apis) {
            Map<String, List<MethodSpec>> byName = api.methods().stream()
                    .collect(Collectors.groupingBy(methodSpec -> methodSpec.javaName));

            for (Map.Entry<String, List<MethodSpec>> entry : byName.entrySet()) {
                List<MethodSpec> sameName = entry.getValue();
                if (sameName.size() <= 1) {
                    continue;
                }

                // A single ERROR policy forbids the whole overload group.
                boolean anyErrorPolicy = sameName.stream()
                        .anyMatch(methodSpec -> OVERLOAD_POLICY_ERROR.equals(methodSpec.overloadPolicy));
                if (anyErrorPolicy) {
                    valid = false;
                    for (MethodSpec methodSpec : sameName) {
                        error(methodSpec.source,
                                "Overloaded bridge method '" + api.typeFqn() + "#" + methodSpec.javaName
                                        + "' is not allowed (OverloadPolicy.ERROR). "
                                        + "Disambiguate by renaming the method, using @BridgeOverloadKey, "
                                        + "or change the overloads() policy.");
                    }
                    continue;
                }

                // ARITY_ONLY cannot disambiguate overloads that share an arity.
                Map<Integer, List<MethodSpec>> byArity = sameName.stream()
                        .collect(Collectors.groupingBy(methodSpec -> methodSpec.paramTypeFqns.size()));
                for (Map.Entry<Integer, List<MethodSpec>> arityEntry : byArity.entrySet()) {
                    List<MethodSpec> sameArity = arityEntry.getValue();
                    if (sameArity.size() <= 1) {
                        continue;
                    }

                    boolean anyArityOnly = sameArity.stream()
                            .anyMatch(methodSpec -> SIGNATURE_POLICY_ARITY_ONLY.equals(methodSpec.signaturePolicy));
                    if (anyArityOnly) {
                        valid = false;
                        for (MethodSpec methodSpec : sameArity) {
                            error(methodSpec.source,
                                    "Ambiguous overloads for bridge method '" + api.typeFqn() + "#"
                                            + methodSpec.javaName + "' with arity " + arityEntry.getKey()
                                            + " while SignaturePolicy.ARITY_ONLY is used. "
                                            + "Use SignaturePolicy.FULL_SIGNATURE or add @BridgeOverloadKey "
                                            + "to disambiguate.");
                        }
                    }
                }
            }
        }

        return valid;
    }

    // =============================================================================================
    // Collection
    // =============================================================================================

    /**
     * Collects every anchor key constant of the round.
     *
     * @param roundEnvironment the current round
     * @return anchor keys keyed by field name, in deterministic order
     */
    private Map<String, AnchorKey> collectAnchorKeys(RoundEnvironment roundEnvironment) {
        Map<String, AnchorKey> keys = new TreeMap<>();

        for (Element element : annotatedElements(roundEnvironment, BRIDGE_ANCHOR_KEY_ANNOTATION)) {
            if (element.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement fieldElement = (VariableElement) element;

            if (!fieldElement.getModifiers().contains(Modifier.STATIC)
                    || !fieldElement.getModifiers().contains(Modifier.FINAL)) {
                error(fieldElement, "@BridgeAnchorKey must be placed on static final fields.");
                continue;
            }

            if (!(fieldElement.getConstantValue() instanceof String value)) {
                error(fieldElement, "@BridgeAnchorKey requires a compile-time String constant initializer.");
                continue;
            }

            String fieldName = fieldElement.getSimpleName().toString();
            keys.put(fieldName, new AnchorKey(fieldName, value));
        }
        return keys;
    }

    /**
     * Collects every bridge API type of the round together with its bridge methods.
     *
     * @param roundEnvironment the current round
     * @return the bridge surface, sorted by namespace and contract id
     */
    private List<ApiSurface> collectApis(RoundEnvironment roundEnvironment) {
        List<ApiSurface> apiSurfaces = new ArrayList<>();

        for (Element element : annotatedElements(roundEnvironment, BRIDGE_API_ANNOTATION)) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }

            AnnotationMirror apiMirror = annotationMirror(typeElement, BRIDGE_API_ANNOTATION);
            String namespace = apiMirror == null ? null : annotationEnumConstant(apiMirror, "value");
            if (namespace == null) {
                error(typeElement, "@BridgeApi requires a BridgeNamespace value.");
                continue;
            }

            List<MethodSpec> methods = new ArrayList<>();
            for (Element enclosedElement : typeElement.getEnclosedElements()) {
                if (enclosedElement.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement methodElement = (ExecutableElement) enclosedElement;
                AnnotationMirror methodMirror = annotationMirror(methodElement, BRIDGE_METHOD_ANNOTATION);
                if (methodMirror == null) {
                    continue;
                }
                methods.add(buildMethodSpec(namespace, methodElement, methodMirror));
            }

            methods.sort(Comparator.comparing(methodSpec -> methodSpec.id));
            apiSurfaces.add(new ApiSurface(namespace, this.elements.getBinaryName(typeElement).toString(), methods));
        }

        apiSurfaces.sort(Comparator.comparing(ApiSurface::namespace));
        return apiSurfaces;
    }

    /**
     * Derives the contract id and signature of a single bridge method.
     *
     * @param namespace     the owning namespace constant name
     * @param methodElement the annotated method
     * @param methodMirror  the bridge method mirror of that method
     * @return the resolved specification
     */
    private MethodSpec buildMethodSpec(String namespace, ExecutableElement methodElement,
                                       AnnotationMirror methodMirror) {
        MethodSpec methodSpec = new MethodSpec();
        methodSpec.source = methodElement;
        methodSpec.namespace = namespace;
        methodSpec.javaName = methodElement.getSimpleName().toString();
        methodSpec.signaturePolicy = annotationEnumConstant(methodMirror, "signature");
        methodSpec.overloadPolicy = annotationEnumConstant(methodMirror, "overloads");
        methodSpec.sinceProtocol = annotationInt(methodMirror, "sinceProtocol", 1);
        methodSpec.paramTypeFqns = methodElement.getParameters().stream()
                .map(parameter -> eraseToFqn(parameter.asType()))
                .toList();
        methodSpec.returnTypeFqn = eraseToFqn(methodElement.getReturnType());

        AnnotationMirror overloadKeyMirror = annotationMirror(methodElement, BRIDGE_OVERLOAD_KEY_ANNOTATION);
        String declaredOverloadKey = overloadKeyMirror == null
                ? ""
                : annotationString(overloadKeyMirror, "value", "");
        String overloadKey = declaredOverloadKey.isBlank() ? null : declaredOverloadKey.trim();

        String stability = annotationEnumConstant(methodMirror, "stability");
        String declaredId = annotationString(methodMirror, "id", "");

        if (STABILITY_STABLE_ID.equals(stability)) {
            if (declaredId.isBlank()) {
                error(methodElement, "@BridgeMethod(stability=STABLE_ID) requires a non-empty id.");
                methodSpec.id = namespace + "#" + methodSpec.javaName;
            } else {
                methodSpec.id = declaredId.trim();
            }
        } else if (overloadKey != null) {
            methodSpec.id = namespace + "#" + methodSpec.javaName + ":" + overloadKey;
        } else if (SIGNATURE_POLICY_ARITY_ONLY.equals(methodSpec.signaturePolicy)) {
            methodSpec.id = namespace + "#" + methodSpec.javaName + "/" + methodSpec.paramTypeFqns.size();
        } else {
            methodSpec.id = namespace + "#" + methodSpec.javaName
                    + "(" + String.join(",", methodSpec.paramTypeFqns) + ")";
        }

        return methodSpec;
    }

    /**
     * Resolves the single bridge protocol version of the round.
     *
     * @param roundEnvironment the current round
     * @return the declared protocol version, or {@code null} when none is declared
     */
    private Integer collectProtocolVersion(RoundEnvironment roundEnvironment) {
        Integer protocol = null;

        for (Element element : annotatedElements(roundEnvironment, BRIDGE_CONTRACT_VERSION_ANNOTATION)) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            AnnotationMirror mirror = annotationMirror(typeElement, BRIDGE_CONTRACT_VERSION_ANNOTATION);
            if (mirror == null) {
                continue;
            }

            int value = annotationInt(mirror, "value", 1);
            if (protocol == null) {
                protocol = value;
            } else if (protocol != value) {
                error(typeElement, "Multiple @BridgeContractVersion values found (" + protocol + " vs " + value + ").");
            }
        }
        return protocol;
    }

    // =============================================================================================
    // Generation
    // =============================================================================================

    /**
     * Writes {@code GeneratedBridgeContract}.
     *
     * @param anchorKeys the collected anchor keys
     * @param apis       the collected bridge surface
     * @param protocol   the declared protocol version, or {@code null}
     * @throws IOException when the source file cannot be written
     */
    private void writeGenerated(Map<String, AnchorKey> anchorKeys, List<ApiSurface> apis, Integer protocol)
            throws IOException {
        JavaFileObject file = processingEnv.getFiler().createSourceFile(GENERATED_PKG + "." + GENERATED_NAME);
        try (Writer writer = file.openWriter()) {
            CodeWriter out = new CodeWriter(writer);

            out.line("package " + GENERATED_PKG + ";");
            out.line("");
            out.line("import java.util.Collections;");
            out.line("import java.util.HashMap;");
            out.line("import java.util.List;");
            out.line("import java.util.Map;");
            out.line("");
            out.line("/**");
            out.line(" * GENERATED FILE. DO NOT EDIT.");
            out.line(" * <p>");
            out.line(" * Generated by " + BridgeContractProcessor.class.getName() + ".");
            out.line(" */");
            out.line("public final class " + GENERATED_NAME + " {");
            out.indent();
            out.line("private " + GENERATED_NAME + "() {}");
            out.line("");

            if (protocol != null) {
                out.line("public static final int PROTOCOL_VERSION = " + protocol + ";");
                out.line("");
            }

            writeAnchorKeys(out, anchorKeys);
            out.line("");
            writeMethods(out, apis);
            out.line("");
            out.line("public record Spec(String javaName, List<String> paramTypeFqns, String returnTypeFqn,"
                    + " String namespace, int sinceProtocol) {}");

            out.unindent();
            out.line("}");
        }
    }

    /**
     * Writes the nested {@code AnchorKeys} holder.
     *
     * @param out        target writer
     * @param anchorKeys the collected anchor keys
     * @throws IOException when the source file cannot be written
     */
    private static void writeAnchorKeys(CodeWriter out, Map<String, AnchorKey> anchorKeys) throws IOException {
        out.line("public static final class AnchorKeys {");
        out.indent();
        out.line("private AnchorKeys() {}");
        for (AnchorKey key : anchorKeys.values()) {
            out.line("public static final String " + key.constName() + " = \"" + escapeJava(key.value()) + "\";");
        }
        out.unindent();
        out.line("}");
    }

    /**
     * Writes the nested {@code Methods} holder with the contract id constants and their specs.
     *
     * @param out  target writer
     * @param apis the collected bridge surface
     * @throws IOException when the source file cannot be written
     */
    private static void writeMethods(CodeWriter out, List<ApiSurface> apis) throws IOException {
        List<MethodSpec> allMethods = apis.stream().flatMap(api -> api.methods().stream()).toList();

        out.line("public static final class Methods {");
        out.indent();
        out.line("private Methods() {}");
        out.line("");

        for (MethodSpec methodSpec : allMethods) {
            String constSeed = methodSpec.namespace + "_" + methodSpec.javaName + "_"
                    + methodSpec.paramTypeFqns.size();
            methodSpec.constName = toConstName(constSeed, methodSpec.id);
            out.line("public static final String " + methodSpec.constName + " = \""
                    + escapeJava(methodSpec.id) + "\";");
        }

        out.line("");
        out.line("public static final Map<String, Spec> SPECS;");
        out.line("");
        out.line("static {");
        out.indent();
        out.line("Map<String, Spec> methods = new HashMap<>();");

        for (MethodSpec methodSpec : allMethods) {
            String params = methodSpec.paramTypeFqns.stream()
                    .map(param -> "\"" + escapeJava(param) + "\"")
                    .collect(Collectors.joining(", "));

            out.line("methods.put(" + methodSpec.constName + ", new Spec(\"" + escapeJava(methodSpec.javaName)
                    + "\", List.of(" + params + "), \"" + escapeJava(methodSpec.returnTypeFqn)
                    + "\", \"" + escapeJava(methodSpec.namespace) + "\", " + methodSpec.sinceProtocol + "));");
        }

        out.line("SPECS = Collections.unmodifiableMap(methods);");
        out.unindent();
        out.line("}");
        out.line("");
        out.line("public static Spec spec(String id) {");
        out.indent();
        out.line("return SPECS.get(id);");
        out.unindent();
        out.line("}");

        out.unindent();
        out.line("}");
    }

    /**
     * Writes {@code GeneratedBridgeIds}, the readable alias view of the contract ids.
     *
     * @param apis the collected bridge surface
     * @throws IOException when the source file cannot be written
     */
    private void writeGeneratedIds(List<ApiSurface> apis) throws IOException {
        JavaFileObject file = processingEnv.getFiler().createSourceFile(GENERATED_PKG + "." + GENERATED_IDS_NAME);
        try (Writer writer = file.openWriter()) {
            CodeWriter out = new CodeWriter(writer);

            out.line("package " + GENERATED_PKG + ";");
            out.line("");
            out.line("/**");
            out.line(" * GENERATED FILE. DO NOT EDIT.");
            out.line(" * <p>");
            out.line(" * Readable deterministic aliases for bridge contract ids.");
            out.line(" * Generated by " + BridgeContractProcessor.class.getName() + ".");
            out.line(" */");
            out.line("public final class " + GENERATED_IDS_NAME + " {");
            out.indent();
            out.line("private " + GENERATED_IDS_NAME + "() {}");
            out.line("");

            List<MethodSpec> allMethods = apis.stream()
                    .flatMap(api -> api.methods().stream())
                    .sorted(Comparator.comparing((MethodSpec methodSpec) -> methodSpec.namespace)
                            .thenComparing(methodSpec -> methodSpec.javaName)
                            .thenComparing(methodSpec -> String.join(",", methodSpec.paramTypeFqns)))
                    .toList();

            String currentNamespace = null;
            for (MethodSpec methodSpec : allMethods) {
                if (!Objects.equals(currentNamespace, methodSpec.namespace)) {
                    if (currentNamespace != null) {
                        out.unindent();
                        out.line("}");
                        out.line("");
                    }
                    currentNamespace = methodSpec.namespace;
                    out.line("public static final class " + namespaceClassName(currentNamespace) + " {");
                    out.indent();
                    out.line("private " + namespaceClassName(currentNamespace) + "() {}");
                    out.line("");
                }

                out.line("public static final String " + aliasFieldName(methodSpec) + " = \""
                        + escapeJava(methodSpec.id) + "\";");
            }

            if (currentNamespace != null) {
                out.unindent();
                out.line("}");
            }

            out.unindent();
            out.line("}");
        }
    }

    // =============================================================================================
    // Annotation reading
    // =============================================================================================

    /**
     * Returns the elements annotated with one of the supported annotations.
     * <p>
     * The annotation is looked up by name instead of by class, because this module intentionally
     * does not depend on the module that declares it.
     *
     * @param roundEnvironment the current round
     * @param annotationName   fully qualified annotation name
     * @return the annotated elements, empty when the annotation is not on the classpath
     */
    private Set<? extends Element> annotatedElements(RoundEnvironment roundEnvironment, String annotationName) {
        TypeElement annotationElement = this.elements.getTypeElement(annotationName);
        return annotationElement == null ? Set.of() : roundEnvironment.getElementsAnnotatedWith(annotationElement);
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

    // =============================================================================================
    // Naming
    // =============================================================================================

    /**
     * Turns a namespace constant into a nested class name, for example {@code PANDASTIC_LIB_API} into
     * {@code PandasticLibApi}.
     *
     * @param namespace the namespace constant name
     * @return the nested class name
     */
    private static String namespaceClassName(String namespace) {
        StringBuilder className = new StringBuilder(namespace.length());
        for (String part : namespace.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isBlank()) {
                continue;
            }
            className.append(Character.toUpperCase(part.charAt(0))).append(part, 1, part.length());
        }
        return className.toString();
    }

    /**
     * Builds the alias field name of a method, for example {@code getService__Plugin__Configuration}.
     *
     * @param methodSpec the method specification
     * @return a valid Java identifier
     */
    private static String aliasFieldName(MethodSpec methodSpec) {
        StringBuilder alias = new StringBuilder(methodSpec.javaName);
        for (String parameter : methodSpec.paramTypeFqns) {
            alias.append("__").append(simpleTypeToken(parameter));
        }

        String sanitized = alias.toString().replaceAll("[^A-Za-z0-9_]", "_");
        return !sanitized.isEmpty() && Character.isDigit(sanitized.charAt(0)) ? "_" + sanitized : sanitized;
    }

    /**
     * Reduces a fully qualified name to a readable single token.
     *
     * @param fqn the fully qualified name
     * @return the simple name, with nested type separators flattened
     */
    private static String simpleTypeToken(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "Unknown";
        }
        String cleaned = fqn.replace('$', '.');
        int lastDot = cleaned.lastIndexOf('.');
        return (lastDot >= 0 ? cleaned.substring(lastDot + 1) : cleaned).replaceAll("<.*>", "");
    }

    /**
     * Builds a readable yet collision resistant constant name for a contract id.
     * <p>
     * The seed provides readability while the hash disambiguates overloads and stable ids.
     *
     * @param seed readable seed, usually namespace, method name and arity
     * @param id   the contract id the constant will hold
     * @return the constant name
     */
    private static String toConstName(String seed, String id) {
        String base = seed.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        return "M_" + base + "_" + shortHashHex(id);
    }

    /**
     * Computes the first four bytes of the SHA-256 digest of {@code input} as upper case hex.
     *
     * @param input the value to hash
     * @return eight hexadecimal characters
     */
    private static String shortHashHex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int index = 0; index < 4 && index < digest.length; index++) {
                hex.append(String.format(Locale.ROOT, "%02X", digest[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to ship SHA-256, so this branch is unreachable.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Escapes a value for inclusion in a generated Java string literal.
     *
     * @param value the raw value
     * @return the escaped value, without surrounding quotes
     */
    private static String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Renders an element as {@code owner::member} for use in diagnostics.
     *
     * @param element the element to describe
     * @return the readable location
     */
    private static String formatElement(Element element) {
        if (element == null) {
            return "<unknown>";
        }
        Element enclosing = element.getEnclosingElement();
        return (enclosing == null ? "<unknown>" : enclosing.toString()) + "::" + element.getSimpleName();
    }

    /**
     * Erases a type to the binary name the bridge uses at runtime.
     *
     * @param type the type to erase
     * @return the binary name of the erasure, or the plain type string for primitives and arrays
     */
    private String eraseToFqn(TypeMirror type) {
        TypeMirror erased = this.types.erasure(type);
        if (erased.getKind() == TypeKind.DECLARED
                && ((DeclaredType) erased).asElement() instanceof TypeElement declaredTypeElement) {
            return this.elements.getBinaryName(declaredTypeElement).toString();
        }
        return erased.toString();
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
     * Minimal indentation aware writer for generated Java sources.
     */
    private static final class CodeWriter {

        private final Writer out;
        private int indent;

        /**
         * Creates a writer around an open stream. The stream stays owned by the caller.
         *
         * @param out the destination
         */
        CodeWriter(Writer out) {
            this.out = out;
        }

        /** Increases the indentation level by one. */
        void indent() {
            this.indent++;
        }

        /** Decreases the indentation level by one, never below zero. */
        void unindent() {
            this.indent = Math.max(0, this.indent - 1);
        }

        /**
         * Writes one line at the current indentation. Blank lines are written without padding.
         *
         * @param line the line content
         * @throws IOException when the destination rejects the write
         */
        void line(String line) throws IOException {
            if (!line.isEmpty()) {
                this.out.write("    ".repeat(this.indent));
            }
            this.out.write(line);
            this.out.write("\n");
        }
    }

    /**
     * A {@code static final String} anchor key lifted into the generated contract.
     *
     * @param constName name of the generated constant
     * @param value     the anchor key value
     */
    private record AnchorKey(String constName, String value) {
    }

    /**
     * One bridge API type and its bridge methods.
     *
     * @param namespace the namespace constant name
     * @param typeFqn   binary name of the annotated type
     * @param methods   the bridge methods, sorted by contract id
     */
    private record ApiSurface(String namespace, String typeFqn, List<MethodSpec> methods) {
    }

    /**
     * Mutable specification of a single bridge method.
     * <p>
     * The contract id is derived during collection and the constant name only becomes known while
     * writing, so this stays a mutable holder rather than a record.
     */
    private static final class MethodSpec {
        private Element source;
        private String namespace;
        private String javaName;
        private List<String> paramTypeFqns;
        private String returnTypeFqn;
        private String signaturePolicy;
        private String overloadPolicy;
        private int sinceProtocol;
        private String id;
        private String constName;
    }
}
