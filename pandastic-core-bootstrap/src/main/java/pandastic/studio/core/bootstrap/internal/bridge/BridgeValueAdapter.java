package pandastic.studio.core.bootstrap.internal.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.internal.PandasticCoreLog;
import pandastic.studio.core.bootstrap.internal.handler.GenericPandasticBootstrapHandler;
import pandastic.studio.core.bootstrap.internal.handler.PandasticBootstrapHandler;
import pandastic.studio.core.util.api.data.Dto;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Adapts values crossing the leader/follower classloader boundary so that the receiving side only
 * ever holds objects it can actually type.
 * <p>
 * Adaptation is driven by the <b>declared</b> type on the receiving side of the call (a follower
 * interface method's return type, or a leader method's parameter type):
 * <ul>
 *     <li>value already holdable through the declared type → passed through unchanged — except
 *     when the declared type is {@code java.lang.Object}, which can hold anything and therefore
 *     proves nothing (see below);</li>
 *     <li>{@link Dto}s → re-created as the receiving side's own instance by
 *     {@link BridgeDtoTransfer}, with no per-type code anywhere;</li>
 *     <li>library interfaces — and consumer interfaces under a
 *     {@linkplain BridgeTypeContract#isProxyableInterfaceType(Class) registered trusted package} —
 *     → wrapped in a JDK proxy created in the declared type's classloader, implementing every
 *     proxyable interface of the foreign object that is loadable there (so downcasts like
 *     {@code (SomeTypedCache) service.getCache()} keep working);</li>
 *     <li>{@link CompletableFuture}/{@link Optional}/{@link List}/{@link Map} with library-typed
 *     elements → elements adapted (maps become live translating views, futures adapt on
 *     completion, optionals are re-wrapped around the adapted element);</li>
 *     <li>positions whose declared type <em>erases</em> to {@code java.lang.Object} — a plain
 *     {@code Object} parameter, return type or record component, and the unbounded type variables
 *     that erase to it — → adapted by the <b>runtime</b> value's shape instead, because the
 *     declared type admits anything and so proves nothing: parent-visible values and library
 *     objects the receiver already holds as its own type pass through untouched, a foreign
 *     {@link Dto} is re-created locally, a foreign object with library interfaces loadable on the
 *     receiving side is cross-proxied, and anything else fails loudly. Without this rule a foreign
 *     library object crossed by reference and surfaced later as a cross-classloader
 *     {@link ClassCastException} pointing at nothing, quietly bypassing the proxy-or-copy rule;</li>
 *     <li>anything else → {@link IllegalStateException}, never a latent {@link ClassCastException}.
 *     {@link BridgeTypeContract#isBridgeSafe(Type)} prevents such signatures from being added.</li>
 * </ul>
 * One gap remains by design: a container whose declared <em>element</em> type is
 * {@code java.lang.Object} (a {@code Map<K, Object>}, a raw {@code List}) still crosses as a
 * shared live container without per-element adaptation, because element adaptation is keyed on
 * the declared element type mentioning a library type. Adapting those elements would force every
 * parent-visible {@code Object}-element container into a copy or a translating view, changing
 * identity and liveness for code that is correct today.
 */
public final class BridgeValueAdapter {

    /**
     * Classloader of the side that will hold the values currently being adapted, for positions
     * whose declared type carries no loader of its own.
     * <p>
     * Everywhere else the receiver is implicit in the declared type — a library interface or
     * {@link Dto} {@link Class} carries the receiving loader with it — but {@code java.lang.Object}
     * is defined by the bootstrap loader and carries nothing. A thread-local rather than a
     * parameter for the same reason {@link BridgeDtoTransfer} uses one for its budget: the
     * recursion leaves this class through {@code BridgeDtoTransfer}'s component conversion and
     * re-enters through the public {@link #adapt(Type, Object, ClassLoader)} entry point, which
     * must not grow a receiver parameter for this one case. Always restored (never merely set) so
     * nothing is left behind on a long-lived pool thread. Read through
     * {@link #currentReceiverLoader()}, which documents the fallback.
     */
    private static final ThreadLocal<ClassLoader> RECEIVER_CONTEXT = new ThreadLocal<>();

    private BridgeValueAdapter() {
    }

    /**
     * Adapts call arguments against the resolved target method's declared parameter types (the
     * target method belongs to the foreign side, so this adapts local values into it).
     * <p>
     * The receiving side here is the target method's declaring class, so its loader is pinned as
     * the {@linkplain #currentReceiverLoader() receiver context} for the duration of the
     * adaptation: an {@code Object}-typed parameter carries no loader of its own, and without the
     * pin a foreign library value in such a position could not be told apart from a local one.
     *
     * @param targetMethod the method about to be invoked on the foreign side
     * @param args         the locally produced arguments
     * @param sourceLoader classloader of the side that produced the arguments
     * @return a new array of arguments the foreign side can hold
     */
    public static Object @NotNull [] adaptArguments(@NotNull Method targetMethod,
                                                    Object @Nullable [] args,
                                                    @Nullable ClassLoader sourceLoader) {
        if (args == null || args.length == 0) return new Object[0];

        Type[] declaredParams = targetMethod.getGenericParameterTypes();
        Object[] adapted = new Object[args.length];
        ClassLoader previous = RECEIVER_CONTEXT.get();
        RECEIVER_CONTEXT.set(targetMethod.getDeclaringClass().getClassLoader());
        try {
            for (int i = 0; i < args.length; i++) {
                adapted[i] = i < declaredParams.length ? adapt(declaredParams[i], args[i], sourceLoader) : args[i];
            }
        } finally {
            restoreReceiverContext(previous);
        }
        return adapted;
    }

    /**
     * Adapts {@code value} (produced by a foreign classloader) so it can be held through
     * {@code declaredType}, which belongs to the receiving side.
     * <p>
     * When {@code declaredType} erases to {@code java.lang.Object} the declared type decides
     * nothing, so the runtime value does: a library-named value that is not
     * {@linkplain BridgeTypeContract#isHoldableAsOwnType(Object, ClassLoader) holdable as the
     * receiver's own type} is re-created as a {@link Dto}, cross-proxied through its library
     * interfaces, or rejected — it is never passed through by reference. Everything else in an
     * {@code Object} position (JDK and other parent-visible values, library objects already local
     * to the receiver) passes through untouched, exactly as before.
     *
     * @param declaredType the receiving side's declared type
     * @param value        the foreign value, may be {@code null}
     * @param sourceLoader classloader of the side that produced {@code value}; used to create
     *                     foreign counterparts when writing back through translating views
     * @return a value holdable through {@code declaredType}
     * @throws IllegalStateException if the value cannot legally cross the boundary
     */
    public static @Nullable Object adapt(@NotNull Type declaredType, @Nullable Object value, @Nullable ClassLoader sourceLoader) {
        if (value == null) return null;

        Class<?> declared = BridgeTypeContract.erase(declaredType);

        // JDK containers are holdable by anyone but may carry library-typed elements.
        if (value instanceof CompletableFuture<?> future && CompletableFuture.class.isAssignableFrom(declared)) {
            Type elementType = typeArgument(declaredType, 0);
            if (elementType == null || !mentionsLibraryType(elementType)) return requireHoldable(declared, value);
            return requireHoldable(declared, future.thenApply(element -> adapt(elementType, element, sourceLoader)));
        }

        // Optional is parent-visible, so canHold(Optional.class, foreignOptional) is always true —
        // which is exactly why it needs its own branch: without one, an Optional wrapping a
        // library-typed element (the profile API's Optional<SkinDto>, say) would pass through the
        // generic holdability check intact and hand the caller a foreign element, deferring the
        // failure to a ClassCastException at opt.get(). Unwrap, adapt the element against the
        // declared element type, re-wrap.
        if (value instanceof Optional<?> optional && Optional.class.isAssignableFrom(declared)) {
            Type elementType = typeArgument(declaredType, 0);
            if (elementType == null || !mentionsLibraryType(elementType)) return requireHoldable(declared, value);
            return requireHoldable(declared,
                    Optional.ofNullable(adapt(elementType, optional.orElse(null), sourceLoader)));
        }

        if (value instanceof Map<?, ?> map && Map.class.isAssignableFrom(declared)) {
            Type keyType = typeArgument(declaredType, 0);
            Type valueType = typeArgument(declaredType, 1);
            boolean keysNeedAdaptation = keyType != null && mentionsLibraryType(keyType);
            boolean valuesNeedAdaptation = valueType != null && mentionsLibraryType(valueType);
            // No library-typed elements: hand the live map over so state stays genuinely shared.
            if (!keysNeedAdaptation && !valuesNeedAdaptation) return requireHoldable(declared, map);

            @SuppressWarnings("unchecked")
            Map<Object, Object> backing = (Map<Object, Object>) map;
            return requireHoldable(declared, new BridgeAdaptingMap(
                    backing,
                    keysNeedAdaptation,
                    elementReader(keysNeedAdaptation, keyType, sourceLoader),
                    elementWriter(keysNeedAdaptation, keyType, sourceLoader),
                    elementReader(valuesNeedAdaptation, valueType, sourceLoader),
                    elementWriter(valuesNeedAdaptation, valueType, sourceLoader)
            ));
        }

        if (value instanceof Collection<?> collection && Collection.class.isAssignableFrom(declared)) {
            Type elementType = typeArgument(declaredType, 0);
            if (elementType == null || !mentionsLibraryType(elementType)) return requireHoldable(declared, value);
            List<Object> adapted = new ArrayList<>(collection.size());
            for (Object element : collection) {
                adapted.add(adapt(elementType, element, sourceLoader));
            }
            return requireHoldable(declared, Set.class.isAssignableFrom(declared)
                    ? Collections.unmodifiableSet(new LinkedHashSet<>(adapted))
                    : Collections.unmodifiableList(adapted));
        }

        // Positions declared as java.lang.Object — including unbounded type variables, which erase
        // to Object — are the one place where the holdability fast path below proves nothing:
        // canHold(Object.class, x) is trivially true for every x. Without this branch a FOREIGN
        // library object slipped through by reference, deferring the failure to a cross-classloader
        // ClassCastException at the receiver's first downcast and quietly defeating the rule that
        // library objects cross only as proxies or as Dto copies. Such a value is adapted by its
        // concrete runtime shape instead. Parent-visible values and library objects the receiver
        // already holds as its own type fall through to the pass-through below, untouched.
        if (declared == Object.class && BridgeTypeContract.isLibraryName(value.getClass().getName())) {
            ClassLoader receiverLoader = currentReceiverLoader();
            if (receiverLoader == null) {
                throw new IllegalStateException(
                        "Bridge contract violation: a value of type " + value.getClass().getName()
                                + " (loader " + value.getClass().getClassLoader() + ") reached a position"
                                + " declared as java.lang.Object, but the receiving classloader could not"
                                + " be determined (source loader " + sourceLoader + "). Refusing to pass a"
                                + " library object through unadapted.");
            }
            if (!BridgeTypeContract.isHoldableAsOwnType(value, receiverLoader)) {
                return adaptForeignObjectPosition(value, receiverLoader);
            }
        }

        if (BridgeTypeContract.canHold(declared, value)) return value;

        // If the value is one of our own bridge proxies, unwrap it instead of stacking proxies.
        Object unwrapped = unwrapBridgeProxy(value);
        if (unwrapped != value) {
            if (BridgeTypeContract.canHold(declared, unwrapped)) return unwrapped;
            value = unwrapped;
        }

        // Dtos cross by value and must be checked before the interface case: a Dto declared
        // through one of its interfaces is still a value, not something to proxy.
        if (BridgeTypeContract.isDto(declared) && BridgeTypeContract.isDto(value.getClass())) {
            return transferAsReceiver(declared, value);
        }

        // Library interfaces are always proxyable; consumer interfaces only once their package
        // was registered via PandasticCore.registerBridgePackage. An unregistered consumer interface
        // deliberately falls through to the contract violation below: the caller explicitly
        // requesting a type does not confer trust on that type.
        if (BridgeTypeContract.isProxyableInterfaceType(declared)) {
            return crossProxy(declared, value);
        }

        throw new IllegalStateException(
                "Bridge contract violation: a value of type " + value.getClass().getName()
                        + " (loader " + value.getClass().getClassLoader() + ") cannot cross the classloader boundary as "
                        + declared.getName() + " (loader " + declared.getClassLoader() + "). "
                        + "Bridged signatures may only expose parent-visible types, library interfaces, "
                        + "or Dtos (see BridgeTypeContract).");
    }

    /**
     * Returns {@code container} if the receiving side can actually hold it through
     * {@code declared}, and fails loudly otherwise.
     * <p>
     * The container branches above test only that the declared type is <em>some</em> {@code Map},
     * {@code Collection}, {@code Optional} or {@code CompletableFuture}, then produce a container
     * of this library's choosing: an unmodifiable list, a {@link LinkedHashSet}, a
     * {@link BridgeAdaptingMap}, a re-wrapped {@link Optional}, a
     * dependent future. A signature declaring a narrower container — {@code ConcurrentMap},
     * {@code Queue}, {@code SortedSet} — would receive something that is not one, and the
     * {@link ClassCastException} would surface in the consumer's code with nothing in it to
     * suggest the bridge. The same applies to the pass-through cases, where the <em>foreign</em>
     * container may be narrower or wider than declared. This class promises never to hand back a
     * latent cast failure, so the promise is checked rather than assumed.
     */
    private static @NotNull Object requireHoldable(@NotNull Class<?> declared, @NotNull Object container) {
        if (BridgeTypeContract.canHold(declared, container)) return container;
        throw new IllegalStateException(
                "Bridge contract violation: a " + container.getClass().getName()
                        + " cannot be held as " + declared.getName()
                        + ". The bridge adapts containers into an unmodifiable List, a LinkedHashSet,"
                        + " a translating Map view, a re-wrapped Optional or a dependent"
                        + " CompletableFuture, so a bridged signature must declare List, Set,"
                        + " Collection, Map, Optional or CompletableFuture rather than a narrower"
                        + " container type.");
    }

    /**
     * Creates a proxy in {@code declaredInterface}'s classloader implementing every
     * {@linkplain BridgeTypeContract#isProxyableInterfaceType(Class) proxyable} interface of
     * {@code foreign} that is loadable there — the library's own interfaces plus any under a
     * registered trusted package.
     */
    private static @NotNull Object crossProxy(@NotNull Class<?> declaredInterface, @NotNull Object foreign) {
        ClassLoader receiverLoader = declaredInterface.getClassLoader();

        LinkedHashSet<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(declaredInterface);
        for (Class<?> foreignInterface : allInterfacesOf(foreign.getClass())) {
            if (!BridgeTypeContract.isProxyableInterfaceType(foreignInterface)) continue;
            try {
                Class<?> local = Class.forName(foreignInterface.getName(), false, receiverLoader);
                if (local.isInterface() && linksCleanlyFrom(local, receiverLoader)) interfaces.add(local);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Genuinely best-effort: an older library copy simply does not have this
                // interface, and the declared interface is already in the list, so the proxy
                // stays usable. Nothing is hidden by skipping it.
            }
        }

        GenericPandasticBootstrapHandler handler = new GenericPandasticBootstrapHandler(foreign);
        try {
            return Proxy.newProxyInstance(receiverLoader, interfaces.toArray(new Class<?>[0]), handler);
        } catch (IllegalArgumentException unlinkable) {
            // Belt and braces: linksCleanlyFrom should have pruned every mixed-copy interface,
            // but the JDK's visibility rules are the final authority. Fall back to the declared
            // interface alone — a usable proxy beats a dead registration.
            PandasticCoreLog.warn("Proxy interface set " + interfaces + " did not link in " + receiverLoader
                    + " (" + unlinkable.getMessage() + "); retrying with only " + declaredInterface.getName() + ".");
            return Proxy.newProxyInstance(receiverLoader, new Class<?>[]{declaredInterface}, handler);
        }
    }

    /**
     * Tracks interface/receiver pairs already reported by {@link #linksCleanlyFrom}, so a hot
     * adaptation path warns once per pair instead of per call.
     */
    private static final Set<String> REPORTED_UNLINKABLE = ConcurrentHashMap.newKeySet();

    /**
     * Whether a candidate proxy interface can actually be <em>linked</em> by a proxy class
     * defined in {@code receiverLoader}.
     *
     * <p>{@code Class.forName} answering is not enough. On a Paper server the receiver's plugin
     * classloader can resolve another plugin's class through the cross-plugin lookup — and that
     * class's method signatures reference <em>that plugin's</em> shaded copies of the library
     * types. The JDK's {@code ProxyBuilder} then rejects the whole interface set, because every
     * type referenced from a proxy method must resolve to the <b>identical</b> {@code Class} from
     * the defining loader ({@code ensureVisible}). That is exactly how a consumer registering a
     * trusted-package service used to kill its own registration: the leader-side proxy tried to
     * mix the leader's library copies with the consumer's.
     *
     * <p>This replicates the JDK's check up front: the interface itself and every non-primitive
     * type referenced by its (inherited) public method signatures — returns, parameters,
     * declared exceptions, array elements — must resolve from {@code receiverLoader} to the very
     * {@code Class} the interface links against. Interfaces that fail are skipped with a one-time
     * warning: the receiver's copy of the library physically cannot host them, and the typed
     * lookup path re-proxies against the caller's own copy anyway.
     */
    private static boolean linksCleanlyFrom(@NotNull Class<?> candidate, @Nullable ClassLoader receiverLoader) {
        try {
            if (!resolvesIdentically(candidate, receiverLoader)) {
                return reportUnlinkable(candidate, candidate, receiverLoader);
            }
            for (Method method : candidate.getMethods()) {
                if (!resolvesIdentically(method.getReturnType(), receiverLoader)) {
                    return reportUnlinkable(candidate, method.getReturnType(), receiverLoader);
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (!resolvesIdentically(parameter, receiverLoader)) {
                        return reportUnlinkable(candidate, parameter, receiverLoader);
                    }
                }
                for (Class<?> exception : method.getExceptionTypes()) {
                    if (!resolvesIdentically(exception, receiverLoader)) {
                        return reportUnlinkable(candidate, exception, receiverLoader);
                    }
                }
            }
            return true;
        } catch (LinkageError broken) {
            return reportUnlinkable(candidate, candidate, receiverLoader);
        }
    }

    /**
     * Whether {@code receiverLoader} resolves {@code type}'s name to exactly {@code type}.
     * Primitives and arrays-of-primitives always do; arrays are checked by element type.
     */
    private static boolean resolvesIdentically(@NotNull Class<?> type, @Nullable ClassLoader receiverLoader) {
        Class<?> element = type;
        while (element.isArray()) element = element.getComponentType();
        if (element.isPrimitive()) return true;
        try {
            return Class.forName(element.getName(), false, receiverLoader) == element;
        } catch (ClassNotFoundException | LinkageError unresolvable) {
            return false;
        }
    }

    private static boolean reportUnlinkable(@NotNull Class<?> candidate,
                                            @NotNull Class<?> offending,
                                            @Nullable ClassLoader receiverLoader) {
        String key = candidate.getName() + "->" + System.identityHashCode(receiverLoader);
        if (REPORTED_UNLINKABLE.add(key)) {
            PandasticCoreLog.warn("Skipping proxy interface " + candidate.getName() + " for receiver loader "
                    + receiverLoader + ": it references " + offending.getName() + " from a different"
                    + " library copy, so a proxy there cannot link it. The declared interface still"
                    + " proxies; typed lookups re-proxy against the caller's own copy.");
        }
        return false;
    }

    /**
     * Unwraps a bridge proxy to the original object it fronts, returning the value unchanged when
     * it is not one of this library's proxies. Public for the service registry: a typed
     * cross-copy lookup must compare and re-proxy against the <em>original</em> registered
     * object, because the leader-side holding proxy only carries the interfaces the leader's own
     * loader could link.
     *
     * @param value any value, possibly a bridge proxy
     * @return the proxied original, or {@code value} itself
     */
    public static @NotNull Object unwrapIfBridgeProxy(@NotNull Object value) {
        return unwrapBridgeProxy(value);
    }

    /**
     * Adapts a foreign library object that arrived in a position declared as
     * {@code java.lang.Object}, where the declared type says nothing about what crossed and the
     * concrete runtime shape has to decide instead. The strategies — and their order — mirror the
     * declared-type ladder in {@link #adapt(Type, Object, ClassLoader)} exactly:
     * <ol>
     *     <li>a {@link Dto} crosses by value, through the same {@link BridgeDtoTransfer} entry
     *     point the declared-Dto branch uses. The declared target handed to the transfer is the
     *     <em>receiver's</em> own copy of the {@code Dto} interface, which lets the transfer
     *     resolve the concrete local counterpart itself and produce its standard, descriptive
     *     failure when the receiver's library copy lacks the type;</li>
     *     <li>an object with at least one
     *     {@linkplain BridgeTypeContract#isProxyableInterfaceType(Class) proxyable} interface
     *     loadable on the receiving side crosses as the same cross-proxy the declared-interface
     *     branch builds, with the first loadable local interface standing in for the declared one
     *     ({@link #crossProxy} then adds every other loadable proxyable interface anyway);</li>
     *     <li>anything else — a plain library class, a library enum constant — is rejected with
     *     the adapter's standard contract violation, never handed over as a latent
     *     {@link ClassCastException}.</li>
     * </ol>
     *
     * @param foreign        a library-named value that is not holdable as the receiver's own type
     * @param receiverLoader the receiving side's classloader, already known to be non-null
     * @return a value the receiving side can hold
     * @throws IllegalStateException if the value fits none of the three strategies
     */
    private static @NotNull Object adaptForeignObjectPosition(@NotNull Object foreign,
                                                              @NotNull ClassLoader receiverLoader) {
        Class<?> foreignType = foreign.getClass();

        if (BridgeTypeContract.isDto(foreignType)) {
            Class<?> receiverDto;
            try {
                receiverDto = Class.forName(BridgeTypeContract.DTO_FQN, false, receiverLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException(
                        "Bridge contract violation: a Dto of type " + foreignType.getName()
                                + " (loader " + foreignType.getClassLoader() + ") reached a position declared"
                                + " as java.lang.Object, but the receiving side (loader " + receiverLoader
                                + ") has no " + BridgeTypeContract.DTO_FQN + " to re-create it as.", e);
            }
            return transferAsReceiver(receiverDto, foreign);
        }

        for (Class<?> foreignInterface : allInterfacesOf(foreignType)) {
            if (!BridgeTypeContract.isProxyableInterfaceType(foreignInterface)) continue;
            Class<?> local;
            try {
                local = Class.forName(foreignInterface.getName(), false, receiverLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                // An older library copy simply does not have this interface; another one of the
                // object's interfaces may still resolve, and running out of them fails loudly below.
                continue;
            }
            if (local.isInterface()) return crossProxy(local, foreign);
        }

        throw new IllegalStateException(
                "Bridge contract violation: a value of type " + foreignType.getName()
                        + " (loader " + foreignType.getClassLoader() + ") was handed through a position"
                        + " declared as java.lang.Object, but it is a foreign library class the receiving"
                        + " side (loader " + receiverLoader + ") can neither hold, proxy nor copy. "
                        + "Bridged signatures may only expose parent-visible types, library interfaces, "
                        + "or Dtos (see BridgeTypeContract).");
    }

    /**
     * Runs {@link BridgeDtoTransfer#transfer} with the declared type's loader pinned as the
     * {@linkplain #RECEIVER_CONTEXT receiver context}, so that {@code Object}-typed components of
     * the record being rebuilt — whose conversion re-enters {@link #adapt(Type, Object, ClassLoader)}
     * through the public entry point — can still tell a foreign library value from a local one.
     */
    private static @NotNull Object transferAsReceiver(@NotNull Class<?> declared, @NotNull Object value) {
        ClassLoader previous = RECEIVER_CONTEXT.get();
        RECEIVER_CONTEXT.set(declared.getClassLoader());
        try {
            return BridgeDtoTransfer.transfer(declared, value);
        } finally {
            restoreReceiverContext(previous);
        }
    }

    /**
     * The classloader of the side that will hold the value currently being adapted, used only for
     * positions whose declared type erases to {@code java.lang.Object}.
     * <p>
     * Derivation mirrors how the surrounding calls obtain the receiver for every other declared
     * type:
     * <ul>
     *     <li>if an enclosing adaptation pinned the {@link #RECEIVER_CONTEXT} — argument
     *     adaptation pins the target method's declaring class loader, a {@link Dto} transfer pins
     *     the declared Dto's loader — that pin wins;</li>
     *     <li>otherwise this class's own loader: return values are adapted by the receiving side's
     *     copy of this class (the proxy whose handler runs the adaptation was created in the
     *     receiver's loader by that same copy), so outside the pinned cases the executing copy
     *     <em>is</em> the receiving side. The one exception is a proxy handed <em>as an
     *     argument</em> to the foreign side, whose handler belongs to the argument's producer; for
     *     an {@code Object}-typed return on such a proxy the derived receiver is the producer, so
     *     the producer's own objects pass through exactly as every value in that position did
     *     before this branch existed, rather than being misadapted.</li>
     * </ul>
     *
     * @return the receiver loader, or {@code null} if this library copy was defined by the
     * bootstrap loader and no context was pinned — in which case the caller must fail rather than
     * pass a foreign value through
     */
    private static @Nullable ClassLoader currentReceiverLoader() {
        ClassLoader pinned = RECEIVER_CONTEXT.get();
        if (pinned != null) return pinned;
        return BridgeValueAdapter.class.getClassLoader();
    }

    /**
     * Restores the {@link #RECEIVER_CONTEXT} to what it was before an adaptation pinned it,
     * removing the thread-local outright at the outermost level so nothing is left behind on a
     * long-lived pool thread.
     */
    private static void restoreReceiverContext(@Nullable ClassLoader previous) {
        if (previous == null) {
            RECEIVER_CONTEXT.remove();
        } else {
            RECEIVER_CONTEXT.set(previous);
        }
    }

    private static @NotNull UnaryOperator<Object> elementReader(boolean needsAdaptation,
                                                                @Nullable Type declaredElementType,
                                                                @Nullable ClassLoader sourceLoader) {
        if (!needsAdaptation || declaredElementType == null) return UnaryOperator.identity();
        return element -> adapt(declaredElementType, element, sourceLoader);
    }

    private static @NotNull UnaryOperator<Object> elementWriter(boolean needsAdaptation,
                                                                @Nullable Type declaredElementType,
                                                                @Nullable ClassLoader sourceLoader) {
        if (!needsAdaptation || declaredElementType == null) return UnaryOperator.identity();
        Class<?> localErasure = BridgeTypeContract.erase(declaredElementType);
        return element -> {
            if (element == null) return null;
            if (!BridgeTypeContract.isLibraryType(localErasure)) return element;
            if (sourceLoader == null) {
                throw new IllegalStateException("Cannot write " + localErasure.getName()
                        + " back through the bridge: owning classloader unknown.");
            }
            Class<?> foreignCounterpart;
            try {
                foreignCounterpart = Class.forName(localErasure.getName(), false, sourceLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Cannot write " + localErasure.getName()
                        + " back through the bridge: type not present in the owning classloader.", e);
            }
            return adapt(foreignCounterpart, element, localErasure.getClassLoader());
        };
    }

    /**
     * Unwraps one of this library's own bridge proxies back to the object it delegates to, so a
     * value that travels follower → leader → follower does not accumulate proxy layers.
     * <h2>Why this is a trust boundary</h2>
     * The only thing {@link Proxy#getInvocationHandler} guarantees is that some object implements
     * {@link InvocationHandler}. Unwrapping means reflectively invoking a method on it and then
     * treating the result as a trusted leader-side object, so a handler that merely <em>looked</em>
     * like ours — a hostile class planted in a same-named package — would be enough to substitute
     * an arbitrary object into the bridge. Matching on a name prefix, as this did before, is not a
     * check at all.
     * <p>
     * A foreign handler is accepted only when all of the following hold:
     * <ol>
     *     <li>it is an instance of the local {@link PandasticBootstrapHandler} (the same-classloader
     *     case, which needs no reflection at all); or</li>
     *     <li>its class name is exactly {@link GenericPandasticBootstrapHandler}'s — not a prefix
     *     match — and</li>
     *     <li>it declares an interface whose name is exactly {@link PandasticBootstrapHandler}'s, and</li>
     *     <li>resolving that interface name through the handler's <em>own</em> classloader yields
     *     that very same {@link Class}, which rules out a handler that shadows the name locally
     *     while its loader resolves it to the genuine library copy, and</li>
     *     <li>the {@code target()} method is public on a public type, reached through
     *     {@link java.lang.invoke.MethodHandles#publicLookup()} with no access escalation.</li>
     * </ol>
     * Any failure leaves the value untouched — the safe outcome, since a non-unwrapped value is
     * merely adapted again — and is logged rather than swallowed.
     * <h2>Residual risk</h2>
     * A classloader that provides a wholesale hostile copy of this library satisfies every check
     * above, because in that loader the hostile copy <em>is</em> the library. No structural test
     * can distinguish it; only controlling which plugins are installed can.
     */
    private static @NotNull Object unwrapBridgeProxy(@NotNull Object value) {
        if (!Proxy.isProxyClass(value.getClass())) return value;

        InvocationHandler handler = Proxy.getInvocationHandler(value);
        if (handler instanceof PandasticBootstrapHandler localHandler) {
            return localHandler.target();
        }

        Class<?> handlerType = handler.getClass();
        if (!GenericPandasticBootstrapHandler.class.getName().equals(handlerType.getName())) {
            // Not a bridge proxy: somebody else's proxy, which is none of our business.
            return value;
        }

        Class<?> declaredHandlerInterface =
                BridgeTypeContract.findInterfaceByName(handlerType, PandasticBootstrapHandler.class.getName());
        if (declaredHandlerInterface == null) {
            PandasticCoreLog.warn("Refusing to unwrap a proxy whose handler " + handlerType.getName()
                    + " does not implement " + PandasticBootstrapHandler.class.getName()
                    + "; it is impersonating a bridge handler.");
            return value;
        }

        try {
            Class<?> resolved = Class.forName(PandasticBootstrapHandler.class.getName(), false, handlerType.getClassLoader());
            if (resolved != declaredHandlerInterface) {
                PandasticCoreLog.warn("Refusing to unwrap a proxy whose handler " + handlerType.getName()
                        + " shadows " + PandasticBootstrapHandler.class.getName()
                        + " with a type its own classloader does not agree on.");
                return value;
            }

            Method target = declaredHandlerInterface.getMethod("target");
            if (!Modifier.isPublic(target.getModifiers())
                    || !Modifier.isPublic(declaredHandlerInterface.getModifiers())) {
                PandasticCoreLog.warn("Refusing to unwrap a proxy: " + declaredHandlerInterface.getName()
                        + "#target() is not public.");
                return value;
            }

            Object unwrapped = MethodHandles.publicLookup().unreflect(target).invokeWithArguments(handler);
            if (unwrapped != null) return unwrapped;
            PandasticCoreLog.warn("Bridge handler " + handlerType.getName()
                    + " returned a null target; keeping the proxy instead.");
        } catch (LinkageError | ReflectiveOperationException e) {
            PandasticCoreLog.warn("Refusing to unwrap a proxy: bridge handler " + handlerType.getName()
                    + " could not be verified.", e);
        } catch (Throwable t) {
            // invokeWithArguments propagates whatever target() threw.
            PandasticCoreLog.warn("Bridge handler " + handlerType.getName()
                    + " failed while exposing its target; keeping the proxy instead.", t);
        }
        return value;
    }

    private static @Nullable Type typeArgument(@NotNull Type type, int index) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (index < arguments.length) return arguments[index];
        }
        return null;
    }

    private static boolean mentionsLibraryType(@NotNull Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) return mentionsLibraryType(clazz.getComponentType());
            return BridgeTypeContract.isLibraryType(clazz);
        }
        if (type instanceof ParameterizedType parameterized) {
            if (mentionsLibraryType(parameterized.getRawType())) return true;
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (mentionsLibraryType(argument)) return true;
            }
            return false;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                if (mentionsLibraryType(bound)) return true;
            }
            for (Type bound : wildcard.getLowerBounds()) {
                if (mentionsLibraryType(bound)) return true;
            }
            return false;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                if (mentionsLibraryType(bound)) return true;
            }
            return false;
        }
        if (type instanceof GenericArrayType array) {
            return mentionsLibraryType(array.getGenericComponentType());
        }
        return false;
    }

    private static @NotNull Set<Class<?>> allInterfacesOf(@NotNull Class<?> type) {
        LinkedHashSet<Class<?>> found = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            pending.addAll(List.of(current.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> next = pending.poll();
            if (found.add(next)) {
                pending.addAll(List.of(next.getInterfaces()));
            }
        }
        return found;
    }
}
