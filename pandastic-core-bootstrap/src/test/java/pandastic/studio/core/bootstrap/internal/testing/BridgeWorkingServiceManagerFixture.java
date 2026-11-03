package pandastic.studio.core.bootstrap.internal.testing;

import org.bukkit.plugin.Plugin;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeTypeContract;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeValueAdapter;
import pandastic.studio.core.service.api.PluginBoundPandasticService;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A functioning, minimal service registry for cross-loader tests.
 * <p>
 * {@link BridgeFakeServiceManagerFixture} exists to prove that validation never invokes anything,
 * so all of its methods throw. This fixture is its counterpart for tests that need the calls to
 * actually <em>work</em> end to end: it is loaded through {@link IsolatedLibraryClassLoader} as
 * the "leader's copy" and bridged onto, so registrations arrive as leader-side proxies over the
 * test's objects — the exact situation unregistration has to cope with.
 * <p>
 * Its matching semantics deliberately mirror {@code PandasticServiceManagerImpl} (which lives in
 * {@code pandastic-core-services} and is therefore not loadable here): reference identity first, then
 * {@link PandasticService#getServiceName() service-name} equality; plugin-bound sweeps match on
 * {@link Plugin#getName() plugin name}. The real implementation's semantics are pinned by its own
 * test suite; this fixture only has to be a faithful stand-in for the leader side of the bridge.
 */
public final class BridgeWorkingServiceManagerFixture implements PandasticServiceManager {

    /**
     * Name-keyed registrations in registration order. Guarded by {@code this}; the tests are
     * single-threaded, the lock just keeps the fixture honest.
     */
    private final Map<String, PandasticService> byName = new LinkedHashMap<>();

    public BridgeWorkingServiceManagerFixture() {
    }

    @Override
    public synchronized List<PandasticService> getAllServices() {
        return List.copyOf(byName.values());
    }

    @Override
    public synchronized <T extends PandasticService> List<T> getServicesByType(Class<T> serviceType) {
        List<T> matches = new ArrayList<>();
        for (PandasticService service : byName.values()) {
            if (serviceType.isInstance(service)) matches.add(serviceType.cast(service));
        }
        return List.copyOf(matches);
    }

    @Override
    public synchronized PandasticService getService(String name) {
        return byName.get(name);
    }

    @Override
    public synchronized <T extends PandasticService> T getService(String name, Class<T> as) {
        // Mirrors PandasticServiceManagerImpl: instanceof on the stored value first, then satisfiability
        // against the unwrapped original (the stored value may be this side's holding proxy, whose
        // interface set was pruned to what THIS loader could link). A satisfiable request returns
        // the STORED registration — the erased return type is this copy's PandasticService, so neither
        // the original nor a caller-token proxy could be returned — and the caller-side bridge
        // re-views it. A genuine mismatch stays a loud ClassCastException, never "not registered".
        PandasticService service = byName.get(name);
        if (service == null) return null;
        if (as.isInstance(service)) return as.cast(service);

        Object original = BridgeValueAdapter.unwrapIfBridgeProxy(service);
        if (as.isInstance(original)
                || (BridgeTypeContract.findInterfaceByName(original.getClass(), as.getName()) != null
                        && BridgeTypeContract.isProxyableInterfaceType(as))) {
            // Erasure-safe: T's bound erases to this copy's PandasticService, which `service` is.
            @SuppressWarnings("unchecked")
            T storedView = (T) service;
            return storedView;
        }
        throw new ClassCastException("Service '" + name + "' is registered as "
                + original.getClass().getName() + ", which does not match " + as.getName() + ".");
    }

    @Override
    public synchronized <P extends Plugin> List<PluginBoundPandasticService<P>> getPluginBoundServices(P plugin) {
        List<PluginBoundPandasticService<P>> matches = new ArrayList<>();
        for (PandasticService service : byName.values()) {
            if (!(service instanceof PluginBoundPandasticService<?> bound)) continue;
            if (!plugin.getName().equals(bound.getBoundPlugin().getName())) continue;
            @SuppressWarnings("unchecked")
            PluginBoundPandasticService<P> typed = (PluginBoundPandasticService<P>) bound;
            matches.add(typed);
        }
        return List.copyOf(matches);
    }

    @Override
    public synchronized <T extends PandasticService> void registerService(T service) {
        Objects.requireNonNull(service, "service");
        String name = service.getServiceName();
        PandasticService previous = byName.putIfAbsent(name, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("A different service is already registered as '" + name + "'.");
        }
    }

    @Override
    public synchronized boolean unregisterService(PandasticService service) {
        Objects.requireNonNull(service, "service");
        if (byName.values().removeIf(registered -> registered == service)) return true;
        return byName.remove(service.getServiceName()) != null;
    }

    @Override
    public synchronized int unregisterAll(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String pluginName = plugin.getName();
        int before = byName.size();
        byName.values().removeIf(service -> service instanceof PluginBoundPandasticService<?> bound
                && pluginName.equals(bound.getBoundPlugin().getName()));
        return before - byName.size();
    }

    @Override
    public synchronized <P extends Plugin, T extends PluginBoundPandasticService<?>> boolean hasRegisteredService(
            P plugin, Class<T> serviceType) {
        return getServiceForPlugin(plugin, serviceType) != null;
    }

    @Override
    public synchronized <T extends PluginBoundPandasticService<?>> T getServiceForPlugin(Plugin plugin,
                                                                                   Class<T> serviceType) {
        for (PandasticService service : byName.values()) {
            if (!serviceType.isInstance(service)) continue;
            T typed = serviceType.cast(service);
            if (plugin.getName().equals(typed.getBoundPlugin().getName())) return typed;
        }
        return null;
    }
}
