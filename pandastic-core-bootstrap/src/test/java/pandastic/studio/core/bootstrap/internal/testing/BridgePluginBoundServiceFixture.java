package pandastic.studio.core.bootstrap.internal.testing;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import pandastic.studio.core.service.api.PluginBoundPandasticService;

import java.util.Objects;

/**
 * A follower-owned, plugin-bound service for the cross-loader unregistration tests.
 * <p>
 * It is a library <em>class</em>, so a foreign leader can never hold it directly: crossing the
 * bridge forces the value adapter to wrap it in a leader-side proxy, which is what makes the
 * identity mismatch these tests exercise real rather than simulated. The bound {@link Plugin} and
 * the service name are parent-visible and cross unchanged.
 */
public final class BridgePluginBoundServiceFixture implements PluginBoundPandasticService<Plugin> {

    private final String serviceName;
    private final Plugin boundPlugin;

    public BridgePluginBoundServiceFixture(String serviceName, Plugin boundPlugin) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.boundPlugin = Objects.requireNonNull(boundPlugin, "boundPlugin");
    }

    @Override
    public Plugin getBoundPlugin() {
        return boundPlugin;
    }

    @Override
    public String getServiceName() {
        return serviceName;
    }

    @Override
    public Component getName() {
        return Component.text(serviceName);
    }

    @Override
    public Component getDescription() {
        return Component.text("cross-loader test service " + serviceName);
    }
}
