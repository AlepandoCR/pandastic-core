package pandastic.studio.core.bootstrap.internal.handler;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;

/**
 * Invocation handler contract used by cross-classloader bootstrap proxies.
 * <p>
 * Exposing {@link #target()} is what lets a value that travelled follower → leader → follower be
 * unwrapped instead of accumulating a proxy layer per hop. It is also a trust-sensitive member:
 * see {@code BridgeValueAdapter#unwrapBridgeProxy} for the checks a <em>foreign</em> copy of this
 * interface has to pass before its target is believed.
 */
public interface PandasticBootstrapHandler extends InvocationHandler {

    /**
     * The object this handler forwards to, which lives in the other classloader.
     */
    @NotNull Object target();
}
