package pandastic.studio.core.service.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Service-provider interface that supplies the leader implementation.
 * <p>
 * The bootstrap elects a leader but deliberately does not know how to build one. Dependencies run
 * one way only: the implementation module depends on this API module, never the reverse. The
 * bootstrap therefore discovers the implementation through {@link java.util.ServiceLoader}, loaded
 * from <em>this interface's own classloader</em>, so each shaded copy finds the implementation
 * packaged alongside it and no fully-qualified implementation class name has to be hardcoded
 * anywhere.
 * <p>
 * Implementations are registered under
 * {@code META-INF/services/pandastic.studio.core.service.api.PandasticCoreProvider} and must have a public
 * no-argument constructor. Exactly one implementation is expected per shaded copy.
 */
public interface PandasticCoreProvider {

    /**
     * Creates a brand-new leader instance owned by {@code plugin}.
     * <p>
     * Called at most once per elected leader, while the bootstrap holds the anchor lock. The
     * returned instance owns the shared runtime (thread pool, HTTP client, caches, registry) and
     * will be published to the JVM-global anchor, so it must be fully usable on return.
     *
     * @param plugin the plugin that won leader election and will own the shared runtime
     * @return the freshly created leader
     */
    @NotNull PandasticCore createLeader(@NotNull Plugin plugin);
}
