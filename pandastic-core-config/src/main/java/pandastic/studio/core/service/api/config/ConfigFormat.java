package pandastic.studio.core.service.api.config;

/**
 * On-disk serialization formats available to a {@link Configurable} class.
 * <p>
 * The chosen format drives both the syntax written by the generated
 * {@link GeneratedConfig} implementation and the extension of its default file name.
 *
 * @see Configurable#format()
 */
public enum ConfigFormat {

    /** JavaScript Object Notation. Generated files use the {@code .json} extension. */
    JSON,

    /** YAML. Generated files use the {@code .yml} extension, the Paper convention. */
    YAML
}
