package pandastic.studio.core.bootstrap.internal.proxy;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeValueAdapter;

/**
 * Wraps a leader-owned service object into something the calling plugin can hold.
 * <p>
 * This is a thin, intention-revealing front for {@link BridgeValueAdapter}: the requested API type
 * is the <em>caller's</em> copy, so the adapter builds the proxy in the caller's classloader and
 * implements every library interface of the leader object that is loadable there. Passing an API
 * type the caller's classloader cannot see is a programming error.
 */
public final class PandasticServiceProxyFactory {

    private PandasticServiceProxyFactory() {
    }

    /**
     * Views a leader-owned service through the caller's own copy of {@code api}.
     *
     * @param api           the interface the caller wants to hold the service as
     * @param leaderService the leader-owned instance
     * @param <ApiType>     the caller-visible API type
     * @return a value assignable to {@code api}; the leader instance itself when both sides already
     *         share a classloader
     * @throws IllegalStateException if the service cannot legally cross the boundary as {@code api}
     */
    @SuppressWarnings("unchecked")
    public static <ApiType> @NotNull ApiType proxyAs(@NotNull Class<ApiType> api, @NotNull Object leaderService) {
        return (ApiType) BridgeValueAdapter.adapt(api, leaderService, leaderService.getClass().getClassLoader());
    }
}
