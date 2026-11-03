package pandastic.studio.core.bootstrap.internal.handler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.api.contract.SignaturePolicy;
import pandastic.studio.core.bootstrap.internal.PandasticBootstrapReflection;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeValueAdapter;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invocation handler that forwards every call on a follower-local proxy to the leader-owned object
 * behind it.
 * <p>
 * Every call is adapted at the classloader boundary by {@link BridgeValueAdapter}: arguments
 * against the target method's declared parameter types (so the foreign side can hold them), and
 * the result against the invoked interface method's declared return type (so the local side can
 * hold it).
 * <h2>Overload safety</h2>
 * Target resolution goes through {@link PandasticBootstrapReflection#resolveByDeclaredSignature}, which
 * matches the <b>full parameter type list</b>. This used to match on name and parameter
 * <em>count</em> only, and the first {@code getMethods()} entry that happened to share an arity
 * won — so for a type with {@code describe(String)} and {@code describe(Integer)} the bridge
 * invoked whichever one reflection listed first, silently, in both directions. Resolution order
 * from {@code getMethods()} is explicitly unspecified, so that is not even reproducible between
 * JVM runs.
 * <p>
 * This is the runtime counterpart of {@link SignaturePolicy#FULL_SIGNATURE}: the contract
 * processor stamps full parameter type lists into the generated ids, and no bridge path matches on
 * arity any more. {@link SignaturePolicy#ARITY_ONLY} remains available for deliberately
 * short, human-readable contract ids, and the processor still refuses to generate ambiguous ones;
 * it never relaxes the runtime check.
 */
public class GenericPandasticBootstrapHandler implements PandasticBootstrapHandler {

    /**
     * The leader-owned object this handler forwards to.
     */
    protected final Object target;

    private final Map<Method, ResolvedCall> cache = new ConcurrentHashMap<>();

    private record ResolvedCall(Method targetMethod, MethodHandle handle) {
    }

    /**
     * @param target the foreign object every call is forwarded to
     */
    public GenericPandasticBootstrapHandler(@NotNull Object target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public @NotNull Object target() {
        return target;
    }

    @Override
    public @Nullable Object invoke(Object proxy, @NotNull Method method, Object @Nullable [] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            // equals/hashCode/toString describe the proxy itself, not the foreign object.
            return method.invoke(this, args);
        }

        ResolvedCall call = cache.computeIfAbsent(method, declared -> {
            Method targetMethod = PandasticBootstrapReflection.resolveByDeclaredSignature(target.getClass(), declared);
            return new ResolvedCall(targetMethod, PandasticBootstrapReflection.unreflectPublic(targetMethod));
        });

        ClassLoader callerLoader = method.getDeclaringClass().getClassLoader();
        Object[] adaptedArgs = BridgeValueAdapter.adaptArguments(call.targetMethod(), args, callerLoader);

        Object[] full = new Object[adaptedArgs.length + 1];
        full[0] = target;
        System.arraycopy(adaptedArgs, 0, full, 1, adaptedArgs.length);
        Object result = call.handle().invokeWithArguments(full);

        return BridgeValueAdapter.adapt(method.getGenericReturnType(), result, target.getClass().getClassLoader());
    }
}
