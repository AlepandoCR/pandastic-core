package pandastic.studio.core.service.api;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;

/**
 * A service that performs work off the server thread on an executor it exposes.
 * <p>
 * The executor is normally the leader-owned common pool, which is precisely the kind of heavy
 * resource the leader/follower design exists to share: every plugin submitting through this
 * service lands on the same threads instead of each shading its own pool.
 *
 * @see SyncPandasticService for the main-thread counterpart
 */
public interface AsyncPandasticService extends PandasticService {

    /**
     * Executor this service submits its work to.
     * <p>
     * Callers may submit to it, but must not shut it down: its lifecycle belongs to the leader
     * runtime and other plugins are sharing it.
     */
    @NotNull ExecutorService getExecutorService();
}
