package pandastic.studio.core.dialog.api;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.dialog.api.factory.DialogContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the click handler for a {@link DialogButton}.
 * <p>
 * The annotated method must be {@code void} and accept exactly one parameter of type
 * {@link DialogContext}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DialogButtonHandler {

    /**
     * The id from {@link DialogButton#id()} that this method should handle.
     */
    @NotNull String buttonId();

    /**
     * Max number of times the registered callback can be used.
     * <p>
     * Use {@link net.kyori.adventure.text.event.ClickCallback#UNLIMITED_USES} for unlimited.
     */
    int uses() default 1;
}
