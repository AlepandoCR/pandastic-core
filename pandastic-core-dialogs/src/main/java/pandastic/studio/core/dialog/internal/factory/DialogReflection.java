package pandastic.studio.core.dialog.internal.factory;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DialogReflection {

    private static final Logger LOGGER = LoggerFactory.getLogger(DialogReflection.class);

    static final Object[] NO_ARGS = new Object[0];

    private DialogReflection() {
    }

    /**
     * A method reachable on a controller type, paired with the annotations that apply to it.
     *
     * @param target   the most specific declaration, used as the invocation target
     * @param matches  annotations that apply, keyed by annotation type
     */
    record Scanned(@NotNull Method target, @NotNull Map<Class<? extends Annotation>, Match> matches) {

        <A extends Annotation> @Nullable A annotation(@NotNull Class<A> annotationType) {
            Match match = this.matches.get(annotationType);
            return match != null ? annotationType.cast(match.annotation()) : null;
        }

        /**
         * How far from the concrete class the annotation was declared. Lower wins when
         * two methods fight over the same {@code order()}.
         */
        int depth(@NotNull Class<? extends Annotation> annotationType) {
            Match match = this.matches.get(annotationType);
            return match != null ? match.depth() : Integer.MAX_VALUE;
        }
    }

    /**
     * @param annotation the annotation instance
     * @param depth      distance from the concrete class to the declaring class
     */
    record Match(@NotNull Annotation annotation, int depth) {
    }

    /**
     * Walks the type hierarchy exactly once, resolving both the invocation target for
     * every method signature and every requested annotation along with the depth of the
     * class that declared it.
     * <p>
     * The previous approach re-walked the hierarchy per method per annotation type and
     * relied on {@link NoSuchMethodException} as control flow. Everything this returns is
     * derived from the type alone, so it is safe to cache per class.
     *
     * @param type   the concrete controller class
     * @param wanted the annotation types to look for
     * @return scanned methods, most specific declarations first
     */
    static @NotNull List<Scanned> scan(
            @NotNull Class<?> type,
            @NotNull Collection<Class<? extends Annotation>> wanted
    ) {
        Map<String, Method> targets = new LinkedHashMap<>();
        Map<String, Map<Class<? extends Annotation>, Match>> matches = new HashMap<>();
        Deque<Class<?>> interfaceQueue = new ArrayDeque<>();

        int depth = 0;
        for (Class<?> clazz = type; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            collect(clazz, depth++, wanted, targets, matches);
            Collections.addAll(interfaceQueue, clazz.getInterfaces());
        }

        // Interfaces rank last so a default method never steals an order slot from a
        // concrete class. BFS, so the most directly implemented interface wins.
        Set<Class<?>> visited = new HashSet<>();
        while (!interfaceQueue.isEmpty()) {
            Class<?> iface = interfaceQueue.poll();
            if (!visited.add(iface)) continue;

            collect(iface, Integer.MAX_VALUE, wanted, targets, matches);
            Collections.addAll(interfaceQueue, iface.getInterfaces());
        }

        List<Scanned> scanned = new ArrayList<>(targets.size());
        targets.forEach((key, target) -> {
            Map<Class<? extends Annotation>, Match> found = matches.get(key);
            scanned.add(new Scanned(target, found != null ? Map.copyOf(found) : Map.of()));
        });
        return scanned;
    }

    private static void collect(
            @NotNull Class<?> clazz,
            int depth,
            @NotNull Collection<Class<? extends Annotation>> wanted,
            @NotNull Map<String, Method> targets,
            @NotNull Map<String, Map<Class<? extends Annotation>, Match>> matches
    ) {
        for (Method method : clazz.getDeclaredMethods()) {
            // Bridge and synthetic methods are compiler artifacts; invoking the real
            // declaration dispatches virtually to the override anyway.
            if (method.isBridge() || method.isSynthetic()) continue;

            String key = methodKey(method);
            targets.putIfAbsent(key, method);

            for (Class<? extends Annotation> annotationType : wanted) {
                Annotation annotation = method.getDeclaredAnnotation(annotationType);
                if (annotation == null) continue;

                // putIfAbsent: the first hit walking down from the concrete class wins.
                matches.computeIfAbsent(key, k -> new HashMap<>())
                        .putIfAbsent(annotationType, new Match(annotation, depth));
            }
        }
    }

    private static @NotNull String methodKey(@NotNull Method method) {
        StringBuilder key = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) key.append(',');
            key.append(parameterTypes[i].getName());
        }
        return key.append(')').toString();
    }

    static @NotNull String signature(@NotNull Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + "()";
    }

    static @Nullable Field findFieldWithAnnotation(
            @NotNull Class<?> type,
            @NotNull Class<? extends Annotation> annotationClass
    ) {
        Field found = null;
        for (Class<?> clazz = type; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(annotationClass)) {
                    if (found != null) {
                        throw new IllegalArgumentException("Multiple fields annotated with @" + annotationClass.getSimpleName() + " found in hierarchy of " + type.getName());
                    }
                    found = field;
                }
            }
        }
        return found;
    }

    /**
     * Invokes a method that was already made accessible while planning.
     *
     * @param controller the controller instance
     * @param method     the target method
     * @param args       the argument template computed by {@link #defaultArgs}; never mutated
     * @return the returned value, or {@code null} for conditional rendering
     */
    static @Nullable Object invoke(
            @NotNull Object controller,
            @NotNull Method method,
            @NotNull Object[] args
    ) {
        try {
            return method.invoke(controller, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            String message = "Failed to invoke " + signature(method);
            LOGGER.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * Builds the argument template for a method. Computed once while planning, then
     * reused for every build. {@link Method#invoke} never writes to the array, so a
     * single instance can be shared across threads.
     *
     * @param parameterTypes the method's parameter types
     * @return defaults for each parameter, or a shared empty array
     */
    @Contract(pure = true)
    static @NotNull Object[] defaultArgs(@NotNull Class<?>[] parameterTypes) {
        if (parameterTypes.length == 0) return NO_ARGS;

        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = defaultValue(parameterTypes[i]);
        }
        return args;
    }

    private static @Nullable Object defaultValue(@NotNull Class<?> type) {
        if (!type.isPrimitive()) return null;

        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;

        return null;
    }
}
