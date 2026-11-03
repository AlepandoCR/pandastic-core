package pandastic.studio.core.service.api.config.io;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Snapshot of one configuration file checked against its declaration, as produced by
 * {@link ConfigInspector}.
 * <p>
 * The snapshot answers the questions an operator asks about a misbehaving configuration: does
 * the file exist, does it parse, which declared keys does it actually define, and what stray
 * keys does it carry that the declaration knows nothing about. Both lists are immutable and
 * ordered: {@link #fields()} follows the walk over the declaration (most derived type first;
 * within one type, the order reflection reports its members, which may differ from source
 * order), {@link #unknownKeys()} follows the file.
 *
 * @param name                   the display name of the configuration, as passed to the inspector
 * @param filePath               the absolute path of the inspected file
 * @param fileExists             whether the file exists as a regular file
 * @param syntaxError            the parser message when the file exists but is not valid YAML,
 *                               or {@code null} when it is missing or parses cleanly
 * @param fieldMetadataAvailable whether a declaration type with at least one
 *                               {@link pandastic.studio.core.service.api.config.ConfigValue} member was
 *                               available; when {@code false}, {@link #fields()} degrades to a
 *                               raw dump of the file's leaf keys with {@code "?"} as type name
 * @param fields                 one report per declared key (or per file key in the raw dump)
 * @param unknownKeys            keys present in the file but declared nowhere; always empty in
 *                               the raw dump, where no declaration exists to contradict
 * @see ConfigInspector
 */
public record ConfigInspection(
        @NotNull String name,
        @NotNull String filePath,
        boolean fileExists,
        @Nullable String syntaxError,
        boolean fieldMetadataAvailable,
        @NotNull List<FieldReport> fields,
        @NotNull List<String> unknownKeys
) {

    /**
     * Canonical constructor; copies both lists so the snapshot stays immutable.
     *
     * @throws NullPointerException if either list or any of its elements is {@code null}
     */
    public ConfigInspection {
        fields = List.copyOf(fields);
        unknownKeys = List.copyOf(unknownKeys);
    }

    /**
     * The state of a single configuration key.
     * <p>
     * A key that is declared but absent from the file is reported with {@code present} set to
     * {@code false} and a {@code null} value. In this framework that is not an error: a
     * generated configuration keeps the declared default for every key its file does not
     * define, and writes the full key set back on creation. A missing key therefore means
     * "running on the default" - worth surfacing, because the file no longer shows the value
     * actually in effect.
     *
     * @param key      the configuration key, as written in the file
     * @param typeName the simple name of the declared Java type, or {@code "?"} when the
     *                 report comes from the raw dump of an undeclared file
     * @param comment  the comment declared on the member, empty when there is none
     * @param present  whether the file defines the key
     * @param value    the value the file holds, or {@code null} when the key is absent or the
     *                 file could not be parsed
     */
    public record FieldReport(
            @NotNull String key,
            @NotNull String typeName,
            @NotNull String comment,
            boolean present,
            @Nullable Object value
    ) {
    }
}
