package pandastic.studio.core.bootstrap.internal.testing;

import org.bukkit.plugin.Plugin;
import pandastic.studio.core.service.api.PluginBoundPandasticService;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;

import java.util.List;

/**
 * A structurally genuine service manager: a public library class that really implements
 * {@link PandasticServiceManager}.
 * <p>
 * Every method throws, deliberately, for the same reason {@link BridgeFakeLeaderFixture}'s do:
 * deciding whether a foreign object may be bridged onto has to be a purely structural decision. A
 * check that had to invoke something on the candidate would be the very hole it is meant to close.
 */
public final class BridgeFakeServiceManagerFixture implements PandasticServiceManager {

    public BridgeFakeServiceManagerFixture() {
    }

    @Override
    public List<PandasticService> getAllServices() {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public <T extends PandasticService> List<T> getServicesByType(Class<T> serviceType) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public PandasticService getService(String name) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public <T extends PandasticService> T getService(String name, Class<T> as) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public <P extends Plugin> List<PluginBoundPandasticService<P>> getPluginBoundServices(P plugin) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public <T extends PandasticService> void registerService(T service) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public boolean unregisterService(PandasticService service) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public int unregisterAll(Plugin plugin) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public <P extends Plugin, T extends PluginBoundPandasticService<?>> boolean hasRegisteredService(P plugin,
                                                                                               Class<T> serviceType) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }

    @Override
    public <T extends PluginBoundPandasticService<?>> T getServiceForPlugin(Plugin plugin, Class<T> serviceType) {
        throw new UnsupportedOperationException("validation must not invoke anything");
    }
}
