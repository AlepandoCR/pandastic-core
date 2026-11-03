package pandastic.studio.core.service.api;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Service-provider interface behind {@link PandasticCore#instance(JavaPlugin, boolean)}.
 *
 * <p>The API module cannot reference the bootstrap machinery — dependencies run
 * API &larr; bootstrap &larr; implementation, and the follower bridge classes themselves
 * implement this API. {@code pandastic-core-bootstrap} registers its implementation of this interface
 * under {@code META-INF/services}, and {@link PandasticCore#instance} discovers it through
 * {@link java.util.ServiceLoader}, keeping the dependency arrow pointing one way without
 * hardcoding any class name.
 */
public interface PandasticCoreEntrypoint {

    /**
     * Joins the shared runtime: elects this plugin leader if none exists, otherwise returns a
     * follower bridge onto the current leader.
     *
     * @param plugin  the calling plugin
     * @param logging whether to report the election outcome to {@code plugin}'s logger
     * @return the leader implementation, or a follower bridge to it
     */
    @NotNull PandasticCore join(@NotNull JavaPlugin plugin, boolean logging);
}
