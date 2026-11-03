package pandastic.studio.core.dialog.api;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as an action button provider.
 * <p>
 * The annotated method must return one of the following:
 * <ul>
 *     <li>{@link io.papermc.paper.registry.data.dialog.ActionButton.Builder} (Requires a {@link DialogButtonHandler})</li>
 *     <li>{@link io.papermc.paper.registry.data.dialog.ActionButton} (Ready-to-use button)</li>
 *     <li>{@link java.util.List}&lt;{@link io.papermc.paper.registry.data.dialog.ActionButton}&gt; (List of ready-to-use buttons)</li>
 * </ul>
 * <p>
 * <strong>Note:</strong> Returning {@code List<ActionButton.Builder>} is <b>NOT</b> supported.
 * <p>
 * If the method returns {@code null}, the button(s) will be skipped (conditional rendering).
 * <p>
 * <strong>Handlers:</strong> If returning a {@code Builder}, you must provide a corresponding
 * {@link DialogButtonHandler} method to handle click actions. Handlers are ignored for raw {@code ActionButton}
 * or lists.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DialogButton {
    /**
     * Unique identifier for this button within the controller.
     * Used to link with {@link DialogButtonHandler#buttonId()}.
     */
    @NotNull String id();

    /**
     * The sorting order for this button. Lower numbers appear first (from left to right in most UIs).
     */
    int order();
}
