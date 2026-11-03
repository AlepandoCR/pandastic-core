package pandastic.studio.core.dialog.api;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as a dialog input provider.
 * <p>
 * The annotated method must return one of the following:
 * <ul>
 *     <li>{@link io.papermc.paper.registry.data.dialog.input.DialogInput} (Single input element)</li>
 *     <li>{@link java.util.List}&lt;{@link io.papermc.paper.registry.data.dialog.input.DialogInput}&gt; (List of input elements)</li>
 * </ul>
 * <p>
 * If the method returns {@code null}, the input(s) will be skipped (conditional rendering).
 * <p>
 * Input elements typically appear at the top of the dialog interface in Minecraft.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DialogInput {
    /**
     * Unique identifier for this input element within the controller.
     */
    @NotNull String id();

    /**
     * The sorting order for this input element. Lower numbers appear first.
     */
    int order();
}
