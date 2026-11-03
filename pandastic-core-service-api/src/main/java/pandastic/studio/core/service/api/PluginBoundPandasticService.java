package pandastic.studio.core.service.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * A service instance that belongs to one specific plugin rather than to the runtime as a whole.
 * <p>
 * Most services are singletons shared by everyone. Some, however, have to be scoped: anything
 * that reads per-plugin configuration, writes into a plugin's data folder, registers listeners
 * on a plugin's behalf or schedules tasks against a plugin needs to know <em>which</em> plugin it
 * is acting for, because that plugin can be disabled independently of the leader.
 * <p>
 * The registry indexes these instances by their bound plugin, which is what
 * {@link PandasticServiceManager#getServiceForPlugin(Plugin, Class)} and
 * {@link PandasticServiceManager#getPluginBoundServices(Plugin)} resolve against.
 *
 * @param <P> the concrete plugin type this instance is bound to
 */
public interface PluginBoundPandasticService<P extends Plugin> extends PandasticService {

    /**
     * The plugin this service instance acts on behalf of.
     * <p>
     * {@code Plugin} is parent-visible (it comes from the server, not from this library), so the
     * reference crosses the leader/follower boundary unchanged and stays comparable by identity on
     * both sides.
     */
    @NotNull P getBoundPlugin();
}
