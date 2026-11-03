package pandastic.studio.core.dialog.internal.factory;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pandastic.studio.core.dialog.api.DialogBody;
import pandastic.studio.core.dialog.api.DialogButton;
import pandastic.studio.core.dialog.api.DialogButtonHandler;
import pandastic.studio.core.dialog.api.DialogConfigProvider;
import pandastic.studio.core.dialog.api.DialogInput;
import pandastic.studio.core.dialog.api.factory.DialogConfig;
import pandastic.studio.core.dialog.api.factory.DialogContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static pandastic.studio.core.dialog.internal.factory.DialogReflection.signature;


/**
 * Builds Paper dialogs from annotated controller objects.
 * <p>
 * Reflection over a controller class is done once and cached, keyed by the class. Each
 * {@link #create} call then only reads the config field and invokes the provider methods,
 * so a dialog whose title or body contains placeholders still re-resolves them on every
 * build while the annotation scanning, validation and ordering happen a single time.
 */
public final class DialogFactoryImp {

    private static final Logger LOGGER = LoggerFactory.getLogger(DialogFactoryImp.class);

    private static final Class<io.papermc.paper.registry.data.dialog.body.DialogBody> PAPER_BODY =
            io.papermc.paper.registry.data.dialog.body.DialogBody.class;
    private static final Class<io.papermc.paper.registry.data.dialog.input.DialogInput> PAPER_INPUT =
            io.papermc.paper.registry.data.dialog.input.DialogInput.class;

    private static final List<Class<? extends Annotation>> ANNOTATIONS =
            List.of(DialogBody.class, DialogInput.class, DialogButton.class, DialogButtonHandler.class);

    /**
     * Per-class plan cache. {@link ClassValue} rather than a map keyed by {@code Class},
     * because the latter would keep controller classes — and with them the owning plugin
     * classloader — alive across a reload.
     */
    private static final ClassValue<DialogDefinition> DEFINITIONS = new ClassValue<>() {
        @Override
        protected DialogDefinition computeValue(@NotNull Class<?> type) {
            return plan(type);
        }
    };

    private DialogFactoryImp() {
    }

    /**
     * The cached plan for a controller class, computed on first use.
     * <p>
     * This method must not touch Paper registries and is safe to call in unit tests.
     *
     * @param type the controller class
     * @return the plan describing that class
     */
    public static @NotNull DialogDefinition definitionOf(@NotNull Class<?> type) {
        return DEFINITIONS.get(type);
    }

    /**
     * The cached plan for a controller instance's class.
     *
     * @param controller the controller instance
     * @return the plan describing its class
     */
    public static @NotNull DialogDefinition parse(@NotNull Object controller) {
        return DEFINITIONS.get(controller.getClass());
    }

    /**
     * Builds a Paper dialog from an annotated controller instance.
     *
     * @param controller the annotated controller
     * @return a freshly built dialog
     */
    public static @NotNull Dialog create(@NotNull Object controller) {
        DialogDefinition definition = DEFINITIONS.get(controller.getClass());

        // Read fresh on every build: the field may have been reassigned, and a config
        // holding placeholders can resolve to a different title than it did last time.
        DialogConfig config = readConfig(controller, definition.configField());

        DialogBase.Builder base = DialogBase.builder(resolveTitle(controller, config))
                .canCloseWithEscape(definition.canCloseWithEscape())
                .pause(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE);

        List<io.papermc.paper.registry.data.dialog.body.DialogBody> builtBody =
                buildElements(controller, definition.body(), PAPER_BODY);
        if (!builtBody.isEmpty()) {
            base.body(builtBody);
        }

        // Inputs first (Minecraft UI: inputs render at the top of the input section)
        List<io.papermc.paper.registry.data.dialog.input.DialogInput> builtInputs =
                buildElements(controller, definition.inputs(), PAPER_INPUT);
        if (!builtInputs.isEmpty()) {
            base.inputs(builtInputs);
        }

        List<ActionButton> builtButtons = buildButtons(controller, definition);

        return InlinedRegistryBuilderProvider.instance().createDialog(factory -> {
            var builder = factory.empty();
            builder.base(base.build());
            if (!builtButtons.isEmpty()) {
                builder.type(DialogType.multiAction(builtButtons).build());
            } else {
                builder.type(DialogInstancesProvider.instance().notice());
            }
        });
    }

    // ------------------------------------------------------------------
    // Planning — runs once per controller class
    // ------------------------------------------------------------------

    /**
     * An element paired with the hierarchy depth of the class that annotated it. Depth is
     * only needed while resolving order collisions, so it never reaches the plan.
     */
    private record Ordered(@NotNull DialogDefinition.ElementMethod element, int depth) {
    }

    static @NotNull DialogDefinition plan(@NotNull Class<?> type) {
        pandastic.studio.core.dialog.api.Dialog dialogAnn = type.getAnnotation(pandastic.studio.core.dialog.api.Dialog.class);
        if (dialogAnn == null) {
            throw new IllegalArgumentException("Controller class is missing @Dialog: " + type.getName());
        }

        List<Ordered> body = new ArrayList<>();
        List<Ordered> inputs = new ArrayList<>();
        List<Ordered> buttons = new ArrayList<>();
        Map<String, DialogDefinition.ButtonHandlerMethod> handlers = new LinkedHashMap<>();

        for (DialogReflection.Scanned scanned : DialogReflection.scan(type, ANNOTATIONS)) {
            Method method = scanned.target();

            DialogBody bodyAnn = scanned.annotation(DialogBody.class);
            if (bodyAnn != null) {
                if (!isValidReturnType(method, PAPER_BODY)) {
                    logAndThrow("@DialogBody method must return DialogBody (or subclass) or List<DialogBody>: " + signature(method));
                }
                body.add(new Ordered(element(bodyAnn.id(), bodyAnn.order(), method), scanned.depth(DialogBody.class)));
            }

            DialogInput inputAnn = scanned.annotation(DialogInput.class);
            if (inputAnn != null) {
                if (!isValidReturnType(method, PAPER_INPUT)) {
                    logAndThrow("@DialogInput method must return DialogInput (or subclass) or List<DialogInput>: " + signature(method));
                }
                inputs.add(new Ordered(element(inputAnn.id(), inputAnn.order(), method), scanned.depth(DialogInput.class)));
            }

            DialogButton buttonAnn = scanned.annotation(DialogButton.class);
            if (buttonAnn != null) {
                // Allowed: ActionButton.Builder, ActionButton, List<ActionButton>
                // Disallowed: List<ActionButton.Builder> because button lists should already come with their callbacks
                boolean isBuilder = ActionButton.Builder.class.isAssignableFrom(method.getReturnType());
                boolean isButton = ActionButton.class.isAssignableFrom(method.getReturnType());
                boolean isButtonList = isGenericListType(method, ActionButton.class);

                if (!isBuilder && !isButton && !isButtonList) {
                    logAndThrow("@DialogButton method must return ActionButton.Builder, ActionButton, or List<ActionButton> (List<Builder> is not supported): " + signature(method));
                }
                buttons.add(new Ordered(element(buttonAnn.id(), buttonAnn.order(), method), scanned.depth(DialogButton.class)));
            }

            DialogButtonHandler handlerAnn = scanned.annotation(DialogButtonHandler.class);
            if (handlerAnn != null) {
                validateHandlerSignature(method);
                method.setAccessible(true);

                int uses = handlerAnn.uses();
                if (uses < 0) {
                    LOGGER.warn("Invalid uses={} for buttonId='{}' on handler {}. Falling back to uses=1.",
                            uses, handlerAnn.buttonId(), signature(method));
                    uses = 1;
                }

                DialogDefinition.ButtonHandlerMethod previous = handlers.put(
                        handlerAnn.buttonId(),
                        new DialogDefinition.ButtonHandlerMethod(handlerAnn.buttonId(), uses, method)
                );
                if (previous != null) {
                    logAndThrow("Duplicate @DialogButtonHandler for buttonId='" + handlerAnn.buttonId() + "': " + signature(previous.method()) + " and " + signature(method));
                }
            }
        }

        validateUniqueIds(body, "@DialogBody");
        validateUniqueIds(inputs, "@DialogInput");
        validateUniqueIds(buttons, "@DialogButton");

        List<DialogDefinition.ElementMethod> plannedButtons = assignOrders(buttons);
        warnOnIgnoredHandlers(plannedButtons, handlers);

        return new DialogDefinition(
                type,
                dialogAnn.canBeClosedWithEscape(),
                assignOrders(body),
                assignOrders(inputs),
                plannedButtons,
                Map.copyOf(handlers),
                resolveConfigField(type)
        );
    }

    private static @NotNull DialogDefinition.ElementMethod element(
            @NotNull String id,
            int order,
            @NotNull Method method
    ) {
        method.setAccessible(true);
        return new DialogDefinition.ElementMethod(
                id, order, method, DialogReflection.defaultArgs(method.getParameterTypes())
        );
    }

    /**
     * Resolves order collisions across the class hierarchy and returns the elements
     * sorted by their final order.
     * <p>
     * The comparator reads the depth captured during the scan, so unlike the previous
     * version it performs no reflection while sorting.
     */
    private static @NotNull List<DialogDefinition.ElementMethod> assignOrders(@NotNull List<Ordered> items) {
        if (items.isEmpty()) return List.of();
        if (items.size() == 1) return List.of(items.getFirst().element());

        // Most derived first; within the same class the declared order, then signature
        // so the result stays stable across JVM runs.
        items.sort(Comparator.<Ordered>comparingInt(Ordered::depth)
                .thenComparingInt(ordered -> ordered.element().order())
                .thenComparing(ordered -> signature(ordered.element().method())));

        List<DialogDefinition.ElementMethod> assigned = new ArrayList<>(items.size());
        Set<Integer> used = new HashSet<>();
        for (Ordered ordered : items) {
            int desired = ordered.element().order();
            int slot = desired;
            while (!used.add(slot)) {
                slot++;
            }
            assigned.add(slot == desired ? ordered.element() : ordered.element().withOrder(slot));
        }

        assigned.sort(Comparator.comparingInt(DialogDefinition.ElementMethod::order));
        return List.copyOf(assigned);
    }

    /**
     * A handler only takes effect when its button method hands back a builder we can
     * attach an action to. That is decidable from the declared return type, so it is
     * reported once here instead of on every build.
     */
    private static void warnOnIgnoredHandlers(
            @NotNull List<DialogDefinition.ElementMethod> buttons,
            @NotNull Map<String, DialogDefinition.ButtonHandlerMethod> handlers
    ) {
        if (handlers.isEmpty()) return;

        for (DialogDefinition.ElementMethod button : buttons) {
            DialogDefinition.ButtonHandlerMethod handler = handlers.get(button.id());
            if (handler == null) continue;

            if (!ActionButton.Builder.class.isAssignableFrom(button.method().getReturnType())) {
                LOGGER.warn("Handler {} is declared for buttonId='{}' but {} does not return ActionButton.Builder. Handlers only apply to builders, so it will be ignored.",
                        signature(handler.method()), button.id(), signature(button.method()));
            }
        }
    }

    private static @Nullable Field resolveConfigField(@NotNull Class<?> type) {
        Field configField = DialogReflection.findFieldWithAnnotation(type, DialogConfigProvider.class);
        if (configField == null) return null;

        if (!DialogConfig.class.isAssignableFrom(configField.getType())) {
            logAndThrow("@DialogConfigProvider field must be assignable to DialogConfig (did you forget 'extends DialogConfig' in your generic?): " + configField.getDeclaringClass().getName() + "#" + configField.getName());
        }

        configField.setAccessible(true);
        return configField;
    }

    // ------------------------------------------------------------------
    // Building — runs on every create()
    // ------------------------------------------------------------------

    private static <T> @NotNull List<T> buildElements(
            @NotNull Object controller,
            @NotNull List<DialogDefinition.ElementMethod> elements,
            @NotNull Class<T> elementType
    ) {
        if (elements.isEmpty()) return List.of();

        List<T> built = new ArrayList<>(elements.size());
        for (DialogDefinition.ElementMethod element : elements) {
            Object result = DialogReflection.invoke(controller, element.method(), element.args());
            if (result == null) continue; // Conditional rendering

            if (elementType.isInstance(result)) {
                built.add(elementType.cast(result));
            } else if (result instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (elementType.isInstance(item)) {
                        built.add(elementType.cast(item));
                    } else {
                        LOGGER.warn("Method {} returned a collection containing a non-{}: {}",
                                signature(element.method()), elementType.getSimpleName(),
                                item != null ? item.getClass().getName() : "null");
                    }
                }
            } else {
                LOGGER.warn("Method {} returned unexpected type: {}",
                        signature(element.method()), result.getClass().getName());
            }
        }
        return built;
    }

    private static @NotNull List<ActionButton> buildButtons(
            @NotNull Object controller,
            @NotNull DialogDefinition definition
    ) {
        List<DialogDefinition.ElementMethod> buttons = definition.buttons();
        if (buttons.isEmpty()) return List.of();

        List<ActionButton> built = new ArrayList<>(buttons.size());
        for (DialogDefinition.ElementMethod button : buttons) {
            Object result = DialogReflection.invoke(controller, button.method(), button.args());
            if (result == null) continue; // Conditional rendering

            switch (result) {
                case ActionButton.Builder builder -> {
                    DialogDefinition.ButtonHandlerMethod handler =
                            definition.handlersByButtonId().get(button.id());
                    if (handler != null) {
                        builder.action(registerAction(controller, handler));
                    }
                    built.add(builder.build());
                }
                case ActionButton actionButton -> built.add(actionButton);
                case Collection<?> collection -> {
                    for (Object item : collection) {
                        if (item instanceof ActionButton actionButton) {
                            built.add(actionButton);
                        } else {
                            LOGGER.warn("Method {} returned a collection containing a non-ActionButton: {}",
                                    signature(button.method()),
                                    item != null ? item.getClass().getName() : "null");
                        }
                    }
                }
                default -> throw new IllegalStateException("Method " + signature(button.method()) + " returned unexpected type: " + result.getClass().getName());
            }
        }
        return built;
    }

    /**
     * Registers a click callback. This happens per build rather than per class because
     * each registration carries its own {@code uses} budget.
     */
    private static @NotNull DialogAction registerAction(
            @NotNull Object controller,
            @NotNull DialogDefinition.ButtonHandlerMethod handler
    ) {
        Method method = handler.method();
        DialogActionCallback callback = (response, audience) -> {
            DialogContext context = new DialogContextImp(audience, response);
            try {
                method.invoke(controller, context);
            } catch (IllegalAccessException | InvocationTargetException e) {
                LOGGER.error("Failed to execute button handler {}", signature(method), e);
            }
        };

        return DialogInstancesProvider.instance().register(
                callback,
                ClickCallback.Options.builder().uses(handler.uses()).build()
        );
    }

    private static @Nullable DialogConfig readConfig(@NotNull Object controller, @Nullable Field configField) {
        if (configField == null) return null;

        try {
            Object value = configField.get(controller);
            if (value == null) {
                LOGGER.error("@DialogConfigProvider field is null: {}#{}",
                        controller.getClass().getName(), configField.getName());
                return null;
            }
            return (DialogConfig) value;
        } catch (IllegalAccessException e) {
            LOGGER.error("Failed to read @DialogConfigProvider field: {}#{}",
                    controller.getClass().getName(), configField.getName(), e);
            return null;
        }
    }

    private static @NotNull Component resolveTitle(@NotNull Object controller, @Nullable DialogConfig config) {
        if (config != null) {
            Component title = config.title();
            if (title != null) return title;
        }
        return Component.text(controller.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private static void validateHandlerSignature(@NotNull Method method) {
        if (method.getReturnType() != void.class) {
            logAndThrow("@DialogButtonHandler method must be void: " + signature(method));
        }
        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != DialogContext.class) {
            logAndThrow("@DialogButtonHandler method must accept exactly one parameter of type DialogContext: " + signature(method));
        }
    }

    private static void validateUniqueIds(@NotNull List<Ordered> items, @NotNull String source) {
        if (items.size() <= 1) return;

        Set<String> ids = new HashSet<>();
        for (Ordered ordered : items) {
            if (!ids.add(ordered.element().id())) {
                logAndThrow("Duplicate id '" + ordered.element().id() + "' found for " + source);
            }
        }
    }

    private static void logAndThrow(String message) {
        LOGGER.error(message);
        throw new IllegalArgumentException(message);
    }

    private static boolean isValidReturnType(@NotNull Method method, @NotNull Class<?> targetType) {
        if (targetType.isAssignableFrom(method.getReturnType())) {
            return true;
        }
        return isGenericListType(method, targetType);
    }

    private static boolean isGenericListType(@NotNull Method method, @NotNull Class<?> componentType) {
        if (!List.class.isAssignableFrom(method.getReturnType())) {
            return false;
        }
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> genericClass) {
                return componentType.isAssignableFrom(genericClass);
            }
        }
        return false;
    }
}
