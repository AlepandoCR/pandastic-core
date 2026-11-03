package pandastic.studio.core.dialog.api;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as a dialog body element provider.
 * <p>
 * The annotated method must return one of the following:
 * <ul>
 *     <li>{@link io.papermc.paper.registry.data.dialog.body.DialogBody} (Single body element)</li>
 *     <li>{@link java.util.List}&lt;{@link io.papermc.paper.registry.data.dialog.body.DialogBody}&gt; (List of body elements)</li>
 * </ul>
 * <p>
 * If the method returns {@code null}, the body element(s) will be skipped (conditional rendering).
 * <p>
 * <strong>Order Collisions:</strong> If multiple methods (or superclasses) define the same {@link #order()},
 * the one declared in the most specific (concrete) class takes precedence, shifting others to the next available slot.
 * Lists are inserted as a block at their respective order position.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DialogBody {

    /**
     * Unique identifier for this body element within the controller.
     */
    @NotNull String id();

    /**
     * The sorting order for this body element. Lower numbers appear first.
     */
    int order();
}
