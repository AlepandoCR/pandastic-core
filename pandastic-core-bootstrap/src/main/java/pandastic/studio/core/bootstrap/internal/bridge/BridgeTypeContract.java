package pandastic.studio.core.bootstrap.internal.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.internal.PandasticBridgePackages;
import pandastic.studio.core.bootstrap.internal.PandasticLibrary;
import pandastic.studio.core.util.api.data.Dto;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The structural rules of what may cross the leader/follower classloader boundary.
 * <p>
 * Because this library is shaded unrelocated into every consuming plugin, the same
 * fully-qualified library name resolves to a different {@link Class} per plugin classloader. A
 * follower can therefore only ever hold:
 * <ul>
 *     <li><b>parent-visible types</b> — JDK, Bukkit/Paper, Adventure, … (loaded by a shared parent
 *     loader, so both sides really do share one {@link Class});</li>
 *     <li><b>library interfaces</b> — held as a follower-local JDK proxy over the leader's
 *     instance;</li>
 *     <li><b>{@link Dto}s</b> — re-created locally as the receiving side's own copy of the type.</li>
 * </ul>
 * {@link BridgeValueAdapter} implements the adaptation and {@link BridgeDtoTransfer} implements the
 * {@code Dto} case. {@link #isBridgeSafe(Type)} is the predicate that decides whether a declared
 * signature obeys these rules at all; a library <em>class</em> that is not a {@code Dto}
 * (an implementation, an enum, a runtime holder) can never be handed over and is rejected.
 * <h2>Why {@code Dto} and not a name list</h2>
 * This used to carry a hardcoded set of value-type names, each needing a matching reflective
 * branch in the adapter. That does not scale and it silently diverges. Membership is now a
 * property of the type itself: implement {@link Dto} and the transfer works, with no change here.
 */
public final class BridgeTypeContract {

    /**
     * Package prefix that identifies this library's own types, derived from
     * {@link PandasticLibrary#PACKAGE_PREFIX} so a repackaging can never desynchronise it from the code
     * it is supposed to describe.
     */
    public static final String LIBRARY_PACKAGE_PREFIX = PandasticLibrary.PACKAGE_PREFIX;

    /**
     * Fully-qualified name of {@link Dto}, used to recognise a <em>foreign</em> copy of it. The
     * foreign side's {@code Dto} is a different {@link Class}, so {@code isAssignableFrom} is
     * useless there and the name is the only shared identity.
     */
    static final String DTO_FQN = Dto.class.getName();

    private BridgeTypeContract() {
    }

    /**
     * Whether a class name belongs to this library, in either classloader.
     */
    public static boolean isLibraryName(@NotNull String className) {
        return className.startsWith(LIBRARY_PACKAGE_PREFIX);
    }

    /**
     * Whether a class belongs to this library, in either classloader.
     */
    public static boolean isLibraryType(@Nullable Class<?> type) {
        return type != null && isLibraryName(type.getName());
    }

    /**
     * Whether an interface may be implemented by a bridge proxy: a library interface, or an
     * interface under a package prefix registered via {@code PandasticCore.registerBridgePackage}.
     * <p>
     * This is deliberately a separate predicate from {@link #isLibraryType(Class)} and is consulted
     * only where <em>proxyability</em> is decided — which interfaces a cross-proxy implements, and
     * whether a declared interface position may be served by a proxy at all. Registered prefixes
     * never widen {@link #isBridgeSafe(Type)} or the {@code Dto} value rules: a trusted prefix
     * makes a consumer's interfaces proxyable, never its concrete classes crossable.
     * <p>
     * The trusted prefixes live in an anchor slot ({@link PandasticBridgePackages}) because this method
     * runs in whichever library copy is receiving a value, while registration lands on the leader;
     * a poisoned or unreadable slot degrades to "library interfaces only".
     */
    public static boolean isProxyableInterfaceType(@Nullable Class<?> type) {
        if (type == null || !type.isInterface()) return false;
        if (isLibraryType(type)) return true;
        return PandasticBridgePackages.isTrustedPrefixOf(type.getName());
    }

    /**
     * Whether {@code type} is a {@link Dto} and may therefore cross the boundary by value.
     * <p>
     * Works for local and foreign types alike: the fast path is a real {@code isAssignableFrom}
     * check for types from this classloader, and the fallback compares interface names so a
     * foreign copy of {@code Dto} is recognised too.
     */
    public static boolean isDto(@NotNull Class<?> type) {
        if (Dto.class.isAssignableFrom(type)) return true;
        return implementsByName(type, DTO_FQN);
    }

    /**
     * Whether {@code type} implements, transitively, an interface with exactly this
     * fully-qualified name.
     * <p>
     * Name-based identity is the only identity shared by two unrelocated copies of the same
     * library, so this is the workhorse of every cross-classloader type check in the bridge. It is
     * also strictly structural: it proves nothing about <em>trust</em>, only about shape. Callers
     * that need trust must additionally check the type's origin.
     */
    public static boolean implementsByName(@NotNull Class<?> type, @NotNull String interfaceFqn) {
        return findInterfaceByName(type, interfaceFqn) != null;
    }

    /**
     * Resolves the interface of {@code type} whose fully-qualified name is {@code interfaceFqn},
     * walking the whole superclass/superinterface closure.
     *
     * @return the matching interface as seen by {@code type}'s classloader, or {@code null}
     */
    public static @Nullable Class<?> findInterfaceByName(@NotNull Class<?> type, @NotNull String interfaceFqn) {
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.isInterface() && current.getName().equals(interfaceFqn)) return current;
            pending.addAll(List.of(current.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> next = pending.poll();
            if (next.getName().equals(interfaceFqn)) return next;
            pending.addAll(List.of(next.getInterfaces()));
        }
        return null;
    }

    /**
     * Whether {@code value} can be held through a reference of type {@code declared} without
     * risking a cross-classloader {@link ClassCastException}.
     * <p>
     * One declared type makes this check vacuous: {@code canHold(Object.class, x)} is trivially
     * {@code true} for every {@code x}, including a foreign library object the receiver cannot
     * actually use. Holdability through {@code java.lang.Object} therefore proves nothing about a
     * value's locality, which is why {@link BridgeValueAdapter#adapt} never relies on this check
     * alone for {@code Object}-typed positions — it first classifies library-named values with
     * {@link #isHoldableAsOwnType(Object, ClassLoader)} and adapts foreign ones by their runtime
     * shape.
     */
    public static boolean canHold(@NotNull Class<?> declared, @Nullable Object value) {
        if (value == null) return true;
        if (declared.isPrimitive()) return true;
        try {
            return declared.isInstance(value);
        } catch (LinkageError e) {
            // The declared type cannot even be linked here, so nothing can be held through it.
            return false;
        }
    }

    /**
     * Whether {@code loader} resolves {@code value}'s own class name to a type that can actually
     * hold the value — that is, whether the value is <em>local</em> to that loader's view rather
     * than a foreign copy.
     * <p>
     * Two unrelocated copies of a library type share nothing but their name, so a value can be
     * library-<em>named</em> and still be unholdable where it is about to arrive. This is the
     * foreignness test behind {@link BridgeValueAdapter}'s handling of positions that erase to
     * {@code java.lang.Object}: a library-named value that fails it is adapted by its concrete
     * shape — Dto copy, cross-proxy — or rejected, never passed through by reference. A
     * {@code null} loader is the bootstrap loader, which defines no library types, so every
     * library-named value is foreign to it.
     */
    public static boolean isHoldableAsOwnType(@NotNull Object value, @Nullable ClassLoader loader) {
        try {
            return Class.forName(value.getClass().getName(), false, loader).isInstance(value);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Whether a declared signature type is allowed on the bridged surface: every library type it
     * mentions, including generic arguments, must be an interface or a {@link Dto}.
     * <h2>{@code java.lang.Object} is bridge-safe <em>with adaptation</em></h2>
     * {@code Object} (and the unbounded type variables that erase to it) is parent-visible and
     * stays accepted here, but unlike every other parent-visible type it is not a plain
     * pass-through: the declared type admits anything, so it says nothing about what will actually
     * arrive. {@link BridgeValueAdapter#adapt} therefore decides such positions by the
     * <em>runtime</em> value — parent-visible values and library objects already
     * {@linkplain #isHoldableAsOwnType(Object, ClassLoader) local to the receiver} pass through
     * untouched, while a foreign library object is re-created as a {@link Dto}, cross-proxied
     * through its library interfaces, or rejected at the bridge. One honest caveat: this
     * value-driven rule applies to positions whose declared type erases to {@code Object}, not to
     * containers whose declared <em>element</em> type is {@code Object} ({@code Map<K, Object>},
     * raw collections), which still cross as live shared containers with unadapted elements.
     */
    public static boolean isBridgeSafe(@NotNull Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) return isBridgeSafe(clazz.getComponentType());
            if (!isLibraryType(clazz)) return true;
            return clazz.isInterface() || isDto(clazz);
        }
        if (type instanceof ParameterizedType parameterized) {
            if (!isBridgeSafe(parameterized.getRawType())) return false;
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (!isBridgeSafe(argument)) return false;
            }
            return true;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                if (!isBridgeSafe(bound)) return false;
            }
            for (Type bound : wildcard.getLowerBounds()) {
                if (!isBridgeSafe(bound)) return false;
            }
            return true;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                if (!isBridgeSafe(bound)) return false;
            }
            return true;
        }
        if (type instanceof GenericArrayType array) {
            return isBridgeSafe(array.getGenericComponentType());
        }
        return false;
    }

    /**
     * Erasure of a declared generic type; type variables and wildcards erase to their first bound.
     */
    public static @NotNull Class<?> erase(@NotNull Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof ParameterizedType parameterized) return erase(parameterized.getRawType());
        if (type instanceof TypeVariable<?> variable) {
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : erase(bounds[0]);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] bounds = wildcard.getUpperBounds();
            return bounds.length == 0 ? Object.class : erase(bounds[0]);
        }
        if (type instanceof GenericArrayType array) {
            return erase(array.getGenericComponentType()).arrayType();
        }
        return Object.class;
    }
}
