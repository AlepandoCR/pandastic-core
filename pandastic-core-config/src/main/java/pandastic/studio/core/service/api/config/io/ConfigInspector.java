package pandastic.studio.core.service.api.config.io;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;
import pandastic.studio.core.service.api.config.GeneratedConfig;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Diagnoses one configuration file against the {@link Configurable} declaration it was
 * generated from.
 * <p>
 * The inspector reads the file with a strict YAML parse and walks the declaration for its
 * {@link ConfigValue} members - both shapes the annotation processor supports: annotated
 * fields up the class hierarchy and annotated no-arg methods on the type and its
 * superinterfaces. Duplicate keys are resolved the way the generated code resolves them: the
 * most derived declaration wins. The result is a {@link ConfigInspection} pairing every
 * declared key with its state in the file and listing the keys the file defines without any
 * declaration backing them.
 * <p>
 * A declared key missing from the file is not a failure in this framework: the generated
 * {@link GeneratedConfig} keeps the declared default for it, so the configuration still
 * carries a sensible value. The inspection surfaces it anyway, because a file that no longer
 * shows a key also no longer shows the value actually in effect.
 * <p>
 * Inspection never mutates the file and never requires it to exist.
 *
 * @see ConfigInspection
 */
public final class ConfigInspector {

    /** Type name reported for file keys when no declaration metadata is available. */
    private static final String UNKNOWN_TYPE = "?";

    private ConfigInspector() {
    }

    /**
     * Inspects {@code file} against {@code declarationType} on the calling thread.
     * <p>
     * When {@code declarationType} is {@code null}, or it declares no {@link ConfigValue}
     * member at all, the inspection degrades gracefully: {@code fieldMetadataAvailable} is
     * {@code false} and the field reports become a raw dump of the file's leaf keys with
     * {@value #UNKNOWN_TYPE} as type name, so the file contents remain visible even without a
     * declaration to compare them to.
     *
     * @param name            the display name the inspection reports the configuration under
     * @param file            the configuration file to read; it does not have to exist
     * @param declarationType the hand-written {@link Configurable} declaration (not the
     *                        generated class), or {@code null} when it is not known
     * @return the inspection snapshot
     */
    public static @NotNull ConfigInspection inspect(
            @NotNull String name,
            @NotNull File file,
            @Nullable Class<?> declarationType
    ) {
        boolean fileExists = file.isFile();
        YamlConfiguration yaml = new YamlConfiguration();
        String syntaxError = null;
        if (fileExists) {
            try {
                yaml.load(file);
            } catch (InvalidConfigurationException | IOException exception) {
                syntaxError = messageOf(exception);
            }
        }

        Map<String, DeclaredKey> declared = declarationType == null
                ? Map.of()
                : declaredKeys(declarationType);

        List<ConfigInspection.FieldReport> fields = new ArrayList<>();
        List<String> unknownKeys = new ArrayList<>();
        boolean metadataAvailable = !declared.isEmpty();

        if (metadataAvailable) {
            for (DeclaredKey entry : declared.values()) {
                boolean present = yaml.contains(entry.key());
                fields.add(new ConfigInspection.FieldReport(entry.key(), entry.typeName(),
                        entry.comment(), present, yaml.get(entry.key())));
            }
            for (String key : leafKeys(yaml)) {
                if (!declared.containsKey(key)) {
                    unknownKeys.add(key);
                }
            }
        } else {
            for (String key : leafKeys(yaml)) {
                fields.add(new ConfigInspection.FieldReport(key, UNKNOWN_TYPE, "", true,
                        yaml.get(key)));
            }
        }

        return new ConfigInspection(name, file.getAbsolutePath(), fileExists, syntaxError,
                metadataAvailable, fields, unknownKeys);
    }

    /**
     * Asynchronous counterpart of {@link #inspect(String, File, Class)}: both the file read
     * and the reflective walk of the declaration run on the common
     * {@link java.util.concurrent.ForkJoinPool}, never on the calling thread.
     * <p>
     * Callbacks chained onto the returned future run on that pool as well, so they must not
     * touch the Bukkit API without hopping back to the main thread first.
     *
     * @param name            the display name the inspection reports the configuration under
     * @param file            the configuration file to read; it does not have to exist
     * @param declarationType the hand-written {@link Configurable} declaration (not the
     *                        generated class), or {@code null} when it is not known
     * @return a future completing with the inspection snapshot
     */
    public static @NotNull CompletableFuture<ConfigInspection> inspectAsync(
            @NotNull String name,
            @NotNull File file,
            @Nullable Class<?> declarationType
    ) {
        return CompletableFuture.supplyAsync(() -> inspect(name, file, declarationType));
    }

    /**
     * Collects every key {@code declarationType} declares, most derived type first; within one
     * type the keys follow the order reflection reports the members, which may differ from
     * source order.
     * <p>
     * The walk mirrors what the annotation processor reads: {@link ConfigValue} fields up the
     * class hierarchy and {@link ConfigValue} no-arg methods on the type and all of its
     * superinterfaces. The key is the annotation's {@code fieldName} when set and the raw
     * member name otherwise - exactly the processor's rule. When the same key is declared more
     * than once, the first declaration encountered wins; since the traversal starts at the
     * type itself and only then climbs to supertypes, that is the most derived one, matching
     * which declaration the generated code honours.
     *
     * @param declarationType the declaration to walk
     * @return the declared keys, keyed by configuration key
     */
    private static @NonNull Map<String, DeclaredKey> declaredKeys(@NotNull Class<?> declarationType) {
        Map<String, DeclaredKey> entries = new LinkedHashMap<>();
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(declarationType);

        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (current == Object.class || !visited.add(current)) {
                continue;
            }

            for (Field field : current.getDeclaredFields()) {
                ConfigValue annotation = field.getAnnotation(ConfigValue.class);
                if (annotation != null) {
                    String key = keyOf(annotation, field.getName());
                    entries.putIfAbsent(key, new DeclaredKey(key,
                            field.getType().getSimpleName(), annotation.comment()));
                }
            }
            for (Method method : current.getDeclaredMethods()) {
                ConfigValue annotation = method.getAnnotation(ConfigValue.class);
                if (annotation != null && method.getParameterCount() == 0) {
                    String key = keyOf(annotation, method.getName());
                    entries.putIfAbsent(key, new DeclaredKey(key,
                            method.getReturnType().getSimpleName(), annotation.comment()));
                }
            }

            Class<?> superclass = current.getSuperclass();
            if (superclass != null) {
                queue.add(superclass);
            }
            queue.addAll(Arrays.asList(current.getInterfaces()));
        }
        return entries;
    }

    /**
     * Applies the processor's key rule: the annotation's {@code fieldName} when set, the raw
     * member name otherwise.
     *
     * @param annotation the {@link ConfigValue} on the member
     * @param memberName the simple name of the annotated field or method
     * @return the configuration key the member is stored under
     */
    private static String keyOf(@NotNull ConfigValue annotation,@NotNull String memberName) {
        String declared = annotation.fieldName();
        return declared.isEmpty() ? memberName : declared;
    }

    /**
     * The value-bearing keys of {@code yaml}, in file order: every deep key that is not a
     * configuration section.
     *
     * @param yaml the parsed configuration; empty when the file was missing or malformed
     * @return the leaf keys of the document
     */
    private static List<String> leafKeys(@NotNull YamlConfiguration yaml) {
        List<String> keys = new ArrayList<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * The parser's message for a failed load, falling back to the exception's string form for
     * the rare exception without a message.
     *
     * @param exception the load failure
     * @return a non-null human readable description
     */
    private static String messageOf(@NotNull Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.toString() : message;
    }

    /**
     * One key of the declaration: its name in the file, the Java type that backs it and the
     * comment documenting it.
     *
     * @param key      the configuration key
     * @param typeName the simple name of the declared Java type
     * @param comment  the declared comment, empty when there is none
     */
    private record DeclaredKey(@NotNull String key, @NotNull String typeName, @NotNull String comment) {
    }
}
