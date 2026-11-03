package pandastic.studio.core.bootstrap.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;
import pandastic.studio.core.bootstrap.api.contract.SignaturePolicy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Shared helpers for the reflection/{@link MethodHandle} bridges.
 * <p>
 * Two rules are centralised here because getting either of them wrong individually, at one of the
 * several call sites, is a security bug:
 * <ul>
 *     <li><b>methods are matched on their full parameter type list</b>, never on arity, so an
 *     overload can never be silently substituted for another across the boundary;</li>
 *     <li><b>only the public declared surface is reachable</b>: see
 *     {@link #unreflectPublic(Method)}.</li>
 * </ul>
 * Types are compared by fully-qualified <em>name</em> rather than by {@link Class} identity, which
 * is not an approximation but the only identity two unrelocated copies of this library share: each
 * plugin classloader has its own {@code Class} object for the very same type.
 */
public final class PandasticBootstrapReflection {

    private PandasticBootstrapReflection() {
    }

    /**
     * Cache key for a resolved call, keyed by generated contract id.
     */
    public static @NotNull String cacheKeyByContractId(@NotNull String contractId) {
        return "contract:" + contractId;
    }

    /**
     * Loads the build-time spec for a generated contract id.
     *
     * @throws IllegalStateException if the id is unknown, which means the caller and the generated
     *                               contract were built from different sources
     */
    public static @NotNull GeneratedBridgeContract.Spec loadGeneratedSpec(@NotNull String contractId) {
        GeneratedBridgeContract.Spec spec = GeneratedBridgeContract.Methods.spec(contractId);
        if (spec == null) {
            throw new IllegalStateException("No spec found for contract id: " + contractId);
        }
        return spec;
    }

    /**
     * Resolves the method a generated contract id refers to on a foreign type.
     * <p>
     * The spec carries the erased parameter type names the contract processor recorded at build
     * time, which is the runtime half of {@link SignaturePolicy#FULL_SIGNATURE}.
     */
    public static @NotNull Method resolveByGeneratedSpec(@NotNull Class<?> targetType,
                                                         @NotNull GeneratedBridgeContract.Spec generatedSpec) {
        Method resolved = findBySignature(targetType, generatedSpec.javaName(), generatedSpec.paramTypeFqns());
        if (resolved != null) return resolved;

        throw new IllegalStateException(
                "Could not find method for contract id '" + generatedSpec.namespace() + "#" + generatedSpec.javaName() +
                        "' with params " + generatedSpec.paramTypeFqns() + " on " + targetType.getName());
    }

    /**
     * Resolves, on a foreign type, the method that corresponds to a locally declared one.
     * <p>
     * Used by the generic proxy handler, where no generated contract id is involved: the local
     * interface method <em>is</em> the contract. Matching uses its erased parameter type names, so
     * two overloads sharing a name and an arity stay distinguishable.
     *
     * @param targetType     the foreign type to search
     * @param declaredMethod the local interface method being invoked
     * @throws IllegalStateException if the foreign type has no method with that exact signature,
     *                               which means the two library copies disagree and guessing would
     *                               mean invoking something the caller did not ask for
     */
    public static @NotNull Method resolveByDeclaredSignature(@NotNull Class<?> targetType,
                                                             @NotNull Method declaredMethod) {
        List<String> parameterTypeFqns = new ArrayList<>();
        for (Class<?> parameterType : declaredMethod.getParameterTypes()) {
            parameterTypeFqns.add(parameterType.getName());
        }

        Method resolved = findBySignature(targetType, declaredMethod.getName(), parameterTypeFqns);
        if (resolved != null) return resolved;

        throw new IllegalStateException("Could not find method " + declaredMethod.getName()
                + parameterTypeFqns + " on " + targetType.getName()
                + " (declared by " + declaredMethod.getDeclaringClass().getName() + ")."
                + " The bridge refuses to fall back to a same-arity overload.");
    }

    /**
     * Finds the public method of {@code targetType} with exactly this name and erased parameter
     * type names.
     * <p>
     * Compiler-generated bridge methods are only used when no real declaration matches: they do
     * forward correctly, but the real one carries the accurate generic signature that
     * {@code BridgeValueAdapter} needs to adapt arguments.
     * <h2>Why the first match is not good enough</h2>
     * {@code getMethods()} reports a method once per type that declares it, so an implementation
     * and the interface it overrides both appear — {@code PandasticServiceManagerImpl.getServicesByType}
     * and {@code PandasticServiceManager.getServicesByType} have the same name and the same erasure, and
     * neither is a bridge method. Returning whichever one reflection listed first made the choice
     * depend on an order the specification explicitly leaves unspecified. It does not change which
     * code runs, because the handle is virtual, but it does change the {@code Method} whose
     * <em>generic</em> signature drives argument adaptation: the interface declaration may erase a
     * type variable where the implementation names a concrete type, and an element type that
     * mentions no library type is handed over unadapted. The most-derived declaration is picked
     * instead, which is the one that carries the most specific signature.
     */
    private static @Nullable Method findBySignature(@NotNull Class<?> targetType,
                                                    @NotNull String javaName,
                                                    @NotNull List<String> parameterTypeFqns) {
        Method best = null;
        Method bridgeFallback = null;
        for (Method candidate : targetType.getMethods()) {
            if (!candidate.getName().equals(javaName)) continue;
            if (candidate.getParameterCount() != parameterTypeFqns.size()) continue;

            Class<?>[] candidateParameters = candidate.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < candidateParameters.length; i++) {
                if (!candidateParameters[i].getName().equals(parameterTypeFqns.get(i))) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;

            if (candidate.isBridge() || candidate.isSynthetic()) {
                bridgeFallback = moreSpecific(bridgeFallback, candidate);
                continue;
            }
            best = moreSpecific(best, candidate);
        }
        return best != null ? best : bridgeFallback;
    }

    /**
     * Picks the more specific of two declarations of the same signature, deterministically.
     * <p>
     * Most-derived wins; a class declaration beats an interface one; and two unrelated interfaces
     * are ordered by name, so the answer never depends on reflection's enumeration order even in
     * the case where neither declaration is more specific than the other.
     */
    private static @NotNull Method moreSpecific(@Nullable Method current, @NotNull Method candidate) {
        if (current == null) return candidate;

        Class<?> currentOwner = current.getDeclaringClass();
        Class<?> candidateOwner = candidate.getDeclaringClass();
        if (currentOwner == candidateOwner) return current;

        if (currentOwner.isAssignableFrom(candidateOwner)) return candidate;
        if (candidateOwner.isAssignableFrom(currentOwner)) return current;

        if (currentOwner.isInterface() != candidateOwner.isInterface()) {
            return currentOwner.isInterface() ? candidate : current;
        }
        return currentOwner.getName().compareTo(candidateOwner.getName()) <= 0 ? current : candidate;
    }

    /**
     * Turns a resolved foreign method into an invocable {@link MethodHandle} <b>without
     * escalating access</b>.
     * <h2>Why no {@code setAccessible}</h2>
     * The call sites used to fall back to {@code setAccessible(true)} plus a full-power
     * {@link MethodHandles#lookup()} whenever {@link MethodHandles#publicLookup()} refused. That
     * fallback quietly widened the bridge from "the declared bridged surface" to "every method the
     * leader happens to have", including its private internals, reachable by any plugin in the JVM
     * that can reach the anchor. Only the public API is a contract; everything else is an
     * implementation detail that must stay unreachable.
     * <p>
     * If the resolved method is declared by a non-public type, the equivalent declaration is looked
     * up on a public supertype first — the ordinary case of a package-private implementation class
     * behind a public interface — so legitimate layouts keep working without any escalation.
     *
     * @throws IllegalStateException if the method cannot be reached publicly
     */
    public static @NotNull MethodHandle unreflectPublic(@NotNull Method method) {
        Method publiclyDeclared = publicDeclarationOf(method);
        Class<?> owner = publiclyDeclared.getDeclaringClass();

        if (!Modifier.isPublic(publiclyDeclared.getModifiers()) || !Modifier.isPublic(owner.getModifiers())) {
            throw new IllegalStateException("Refusing to bridge " + owner.getName() + "#"
                    + publiclyDeclared.getName() + ": the bridge only invokes public methods on public"
                    + " types, and will not escalate access to reach a non-public member.");
        }

        try {
            return MethodHandles.publicLookup().unreflect(publiclyDeclared);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Refusing to bridge " + owner.getName() + "#"
                    + publiclyDeclared.getName() + ": it is not reachable through a public lookup"
                    + " (is its package exported?). Access will not be escalated.", e);
        }
    }

    /**
     * Re-resolves {@code method} onto a public supertype when its declaring class is not public.
     *
     * @return a public declaration of the same signature, or {@code method} unchanged if none exists
     */
    private static @NotNull Method publicDeclarationOf(@NotNull Method method) {
        Class<?> declaringClass = method.getDeclaringClass();
        if (Modifier.isPublic(declaringClass.getModifiers())) return method;

        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> current = declaringClass; current != null; current = current.getSuperclass()) {
            if (current != declaringClass && Modifier.isPublic(current.getModifiers())) {
                Method candidate = declaredIn(current, method);
                if (candidate != null) return candidate;
            }
            pending.addAll(List.of(current.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> next = pending.poll();
            if (Modifier.isPublic(next.getModifiers())) {
                Method candidate = declaredIn(next, method);
                if (candidate != null) return candidate;
            }
            pending.addAll(List.of(next.getInterfaces()));
        }
        return method;
    }

    private static @Nullable Method declaredIn(@NotNull Class<?> owner, @NotNull Method method) {
        try {
            return owner.getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
