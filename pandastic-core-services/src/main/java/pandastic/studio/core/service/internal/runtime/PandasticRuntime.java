package pandastic.studio.core.service.internal.runtime;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.internal.profile.PandasticProfileCacheImpl;
import pandastic.studio.core.util.api.data.Dto;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The heavy, shareable resources the elected leader owns on behalf of every plugin in the JVM.
 * <p>
 * This is the concrete reason leader election exists. Without it, five plugins each shading this
 * library would each start their own cached thread pool and their own HTTP client, with five
 * connection pools and five sets of idle threads doing the same work. With it, exactly one
 * instance of each lives in the leader's classloader and every other plugin reaches it through the
 * bridge.
 * <p>
 * The runtime is deliberately <em>not</em> reachable across the bridge: it is a library class, not
 * an interface or a {@link Dto}, so {@code BridgeTypeContract} rejects it on
 * a bridged signature. Followers consume it indirectly, through services that expose
 * parent-visible types such as {@link ExecutorService} — which is the right granularity anyway,
 * since a follower has no business shutting this down.
 *
 * @see pandastic.studio.core.bootstrap.internal.PandasticBootstrap#releaseLeader for when it is torn down
 */
public final class PandasticRuntime {

    /**
     * How long {@link #shutdown()} waits for in-flight tasks before abandoning them.
     */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ExecutorService commonPool;
    private final HttpClient httpClient;
    private final PandasticProfileCacheImpl<?> profileCache;

    /**
     * Starts the shared runtime. Called once, by the plugin that wins leader election.
     */
    public PandasticRuntime() {
        this.commonPool = Executors.newCachedThreadPool();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.profileCache = new PandasticProfileCacheImpl<>();
    }

    /**
     * The thread pool shared by every plugin's asynchronous services.
     * <p>
     * Callers may submit work; they must not shut it down.
     */
    public @NotNull ExecutorService getCommonPool() {
        return commonPool;
    }

    /**
     * The HTTP client shared by every plugin, and with it a single connection pool.
     */
    public @NotNull HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * The single player-profile cache shared by every profile service of every plugin.
     * <p>
     * Exactly one instance per runtime, for the same reason as the pool and the HTTP client: two
     * plugins resolving the same player must hit one cache entry and one remote lookup. The
     * wildcard is honest — the cache's plugin type parameter is semantically unused (no cached
     * datum mentions it), so {@code PandasticCoreImpl} views this instance as whatever {@code P} the
     * requesting plugin needs.
     */
    public @NotNull PandasticProfileCacheImpl<?> getProfileCache() {
        return profileCache;
    }

    /**
     * Stops the shared runtime.
     * <p>
     * Only {@code PandasticBootstrap.releaseLeader} calls this, and only once the last follower has
     * detached. It waits a bounded time for in-flight work instead of returning immediately,
     * because tasks submitted by other plugins are still running on these threads during a
     * server reload.
     */
    public void shutdown() {
        commonPool.shutdown();
        try {
            if (!commonPool.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                commonPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            commonPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        httpClient.close();
        profileCache.clear();
    }
}
