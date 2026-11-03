package pandastic.studio.core.dialog.internal.factory;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * JVM-only representation of an annotated dialog controller.
 * <p>
 * This is intentionally Paper-runtime free so it can be asserted in unit tests.
 * <p>
 * Everything here is derived from the controller <strong>class</strong>, never from an
 * instance, which is what makes it safe to cache. Values that can change between builds
 * (the config instance, the title, anything with placeholders in it) are deliberately
 * absent and get resolved per build instead.
 *
 * @param controllerType     the concrete controller class this plan was built from
 * @param canCloseWithEscape whether the dialog closes on ESCAPE
 * @param body               body providers, already sorted by final order
 * @param inputs             input providers, already sorted by final order
 * @param buttons            button providers, already sorted by final order
 * @param handlersByButtonId click handlers, keyed by the button id they belong to
 * @param configField        the accessible {@code @DialogConfigProvider} field, if any
 */
public record DialogDefinition(
        @NotNull Class<?> controllerType,
        boolean canCloseWithEscape,
        @NotNull List<ElementMethod> body,
        @NotNull List<ElementMethod> inputs,
        @NotNull List<ElementMethod> buttons,
        @NotNull Map<String, ButtonHandlerMethod> handlersByButtonId,
        @Nullable Field configField
) {

    /**
     * A provider method for one dialog element. Backs {@code @DialogBody},
     * {@code @DialogInput} and {@code @DialogButton} alike, since all three carry the
     * same shape.
     *
     * @param id     the element id declared on the annotation
     * @param order  the final order, after collisions across the hierarchy are resolved
     * @param method the accessible target method
     * @param args   argument template, shared and never mutated
     */
    public record ElementMethod(
            @NotNull String id,
            int order,
            @NotNull Method method,
            @NotNull Object[] args
    ) {
        @Contract("_ -> new")
        public @NotNull ElementMethod withOrder(int order) {
            return new ElementMethod(this.id, order, this.method, this.args);
        }
    }

    /**
     * @param buttonId the id of the button this handler is attached to
     * @param uses     how many times the callback may fire, already normalised
     * @param method   the accessible target method
     */
    public record ButtonHandlerMethod(
            @NotNull String buttonId,
            int uses,
            @NotNull Method method
    ) {
    }
}
