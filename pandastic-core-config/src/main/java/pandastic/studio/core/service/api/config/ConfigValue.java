package pandastic.studio.core.service.api.config;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a field or an accessor of a {@link Configurable} declaration to a single
 * configuration key.
 * <p>
 * Every annotated member becomes one entry of the generated configuration file. Members
 * without this annotation are ignored entirely, which makes it safe to keep constants and
 * helper state next to the declared values.
 *
 * @see Configurable
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ConfigValue {

    /**
     * Key this member is stored under in the configuration file.
     * <p>
     * When left empty the name of the annotated field or method is used, which keeps the
     * declaration and the file in sync for free. Set it explicitly when the file has to
     * keep a spelling the Java member would rather not use, such as {@code useSSL}.
     *
     * @return the configuration key, or an empty string to derive it from the member name
     */
    @NotNull String fieldName() default "";

    /**
     * Human readable comment written above the key in the generated file.
     * <p>
     * Keep it to a single line of plain text: it is emitted verbatim into the generated
     * source, so quotes and line breaks would corrupt it.
     *
     * @return the comment documenting the key, or an empty string for no comment
     */
    @NotNull String comment() default "";
}
