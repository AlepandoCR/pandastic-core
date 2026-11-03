package pandastic.studio.core.dialog.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field on a dialog controller that provides a {@link pandastic.studio.core.dialog.api.factory.DialogConfig}.
 * <p>
 * Intended usage:
 * <pre>
 * {@code
 * @DialogConfigProvider
 * private final MyDialogConfig config;
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DialogConfigProvider {
}
