package pandastic.studio.core.service.api.config;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or an interface as the declaration of a configuration file.
 * <p>
 * The annotated type is a plain declaration: its {@link ConfigValue} annotated members
 * describe the keys, the comments and the default values of the file. The annotation
 * processor reads that declaration and generates a {@link GeneratedConfig} implementation
 * that performs the actual loading and saving, named after {@link #name()} and placed in
 * {@link #targetPackage()}.
 * <p>
 * An interface declaration carries its defaults in {@code default} methods, which the
 * generated class - implementing the interface directly - invokes from its constructor:
 * <pre>{@code
 * @Configurable(name = "DatabaseConfig", format = ConfigFormat.YAML)
 * public interface DatabaseSettings {
 *
 *     @ConfigValue(fieldName = "host", comment = "Hostname of the database server")
 *     default String getHost() {
 *         return "localhost";
 *     }
 * }
 * }</pre>
 * A class declaration carries its defaults in field initializers instead; the generated
 * class reads them off a throwaway instance, so it needs an accessible no-arg constructor:
 * <pre>{@code
 * @Configurable(name = "MessagesConfig", format = ConfigFormat.YAML)
 * public class MessagesDeclaration {
 *
 *     @ConfigValue(fieldName = "prefix", comment = "Prefix of every chat message")
 *     private String prefix = "[PANDASTIC]";
 * }
 * }</pre>
 *
 * @see ConfigValue
 * @see GeneratedConfig
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Configurable {

    /**
     * Simple class name of the generated {@link GeneratedConfig} implementation.
     * <p>
     * It must differ from the simple name of the annotated type whenever both end up in
     * the same package, otherwise the generated source would clash with the declaration.
     * The name doubles as the default file name, suffixed with the extension of
     * {@link #format()}.
     *
     * @return the simple name of the class to generate
     */
    @NotNull String name();

    /**
     * Package the generated implementation is written to.
     * <p>
     * When left empty the package of the annotated type is used.
     *
     * @return the fully qualified target package, or an empty string to inherit the declaring package
     */
    @NotNull String targetPackage() default "";

    /**
     * Serialization format of the generated configuration file.
     *
     * @return the format to persist the configuration in; {@link ConfigFormat#YAML} by default
     */
    @NotNull ConfigFormat format() default ConfigFormat.YAML;
}
