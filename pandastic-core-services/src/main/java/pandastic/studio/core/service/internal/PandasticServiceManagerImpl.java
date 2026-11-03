package pandastic.studio.core.service.internal;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import pandastic.studio.core.bootstrap.internal.PandasticBridgePackages;
import pandastic.studio.core.bootstrap.internal.PandasticCoreLog;
import pandastic.studio.core.bootstrap.internal.PandasticJvmAnchor;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeTypeContract;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeValueAdapter;
import pandastic.studio.core.service.api.PluginBoundPandasticService;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one real service registry in the JVM, living in the leader's classloader.
 * <p>
 * Every other plugin reaches this instance through {@code PandasticBridgeServiceManager}, so there is
 * exactly one registry no matter how many copies of the library are loaded. That also sets the
 * design constraints: lookups happen on arbitrary threads from arbitrary plugins, so the
 * structures are concurrent, and every returned collection is an immutable snapshot rather than a
 * live view — handing a live collection across the bridge would let one plugin's iteration be
 * broken by another plugin's registration.
 * <p>
 * Registration order is preserved because it is the only ordering callers can reason about;
 * nothing else about a set of independently registered services is meaningful.
 */
public final class PandasticServiceManagerImpl implements PandasticServiceManager {

    /**
     * Registration-ordered view, and the source of truth for iteration.
     */
    private final List<PandasticService> registered = new CopyOnWriteArrayList<>();

    /**
     * Name index, enforcing uniqueness of {@link PandasticService#getServiceName()}.
     */
    private final Map<String, PandasticService> byName = new ConcurrentHashMap<>();

    @Contract(pure = true)
    @Override
    public @NotNull @Unmodifiable List<PandasticService> getAllServices() {
        return List.copyOf(registered);
    }

    @Override
    public @NotNull <T extends PandasticService> @Unmodifiable List<T> getServicesByType(@NotNull Class<T> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");

        List<T> matches = new ArrayList<>();
        for (PandasticService service : registered) {
            if (serviceType.isInstance(service)) {
                matches.add(serviceType.cast(service));
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public @Nullable PandasticService getService(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        return byName.get(name);
    }

    /**
     * {@inheritDoc}
     * <h4>Why the stored instance cannot simply be cast</h4>
     * For a follower-registered service the stored value is this registry's holding proxy, and its
     * interface set was fixed when the service crossed the bridge — restricted to the interfaces
     * <em>this</em> classloader could actually link. A consumer interface whose method signatures
     * mention the consumer's own shaded library copy is deliberately omitted from that proxy
     * (see {@code BridgeValueAdapter}'s linkability pruning), and even a requested <em>library</em>
     * interface is a different {@code Class} when the caller runs another copy. So the lookup works
     * outward from the proxy: an {@code instanceof} hit on the stored value is returned directly;
     * otherwise the request is checked for <em>satisfiability</em> against the unwrapped original —
     * a direct {@code instanceof}, or a proxyable interface of the requested <em>name</em> anywhere
     * in its hierarchy — and a satisfiable request returns the stored registration for the
     * caller-side bridge to re-view through the caller's own token, where every referenced type
     * links. Nothing else can be returned here: this method's erased return type is this copy's
     * {@code PandasticService}, which neither the original nor a caller-token proxy implements.
     * <h4>Why a mismatch throws instead of returning {@code null}</h4>
     * A name hit with the wrong type is a caller bug — the name is the registry's unique key, so
     * there is no "other" service the caller could have meant. Returning {@code null} would be
     * indistinguishable from "not registered" and would send the caller down a re-registration
     * path with a name that is in fact taken. The exception names both the registered type and the
     * requested one, at the one call site where both are known.
     */
    @Override
    public @Nullable <T extends PandasticService> T getService(@NotNull String name, @NotNull Class<T> as) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(as, "as");

        PandasticService service = byName.get(name);
        if (service == null) return null;
        if (as.isInstance(service)) return as.cast(service);

        Object original = BridgeValueAdapter.unwrapIfBridgeProxy(service);
        boolean sameNameElsewhere =
                BridgeTypeContract.findInterfaceByName(original.getClass(), as.getName()) != null;
        if (as.isInstance(original)
                || (sameNameElsewhere && BridgeTypeContract.isProxyableInterfaceType(as))) {
            // The request is satisfiable, but not by a value this method can legally return: its
            // erased return type is THIS copy's PandasticService, and both the unwrapped original and a
            // proxy built around the caller's token belong to the caller's copy. So the stored
            // registration is returned instead — it is always a PandasticService of this copy — and the
            // caller-side bridge unwraps or re-proxies it through the caller's own token, where
            // every referenced type links. The cast is erasure-safe for exactly that reason:
            // T's bound erases to this copy's PandasticService, which `service` statically is.
            @SuppressWarnings("unchecked")
            T storedView = (T) service;
            return storedView;
        }
        throw new ClassCastException("Service '" + name + "' is registered as "
                + original.getClass().getName() + ", which does not match the requested type "
                + as.getName()
                + (sameNameElsewhere
                        ? " — it does implement an interface of that name from another classloader,"
                        + " but the requested interface is neither a library type nor under a"
                        + " package registered via PandasticCore.registerBridgePackage, so it cannot"
                        + " cross the boundary."
                        : "."));
    }

    @Override
    public @NotNull <P extends Plugin> @Unmodifiable List<PluginBoundPandasticService<P>> getPluginBoundServices(@NotNull P plugin) {
        Objects.requireNonNull(plugin, "plugin");

        List<PluginBoundPandasticService<P>> matches = new ArrayList<>();
        for (PandasticService service : registered) {
            if (!(service instanceof PluginBoundPandasticService<?> bound)) continue;
            if (!plugin.equals(bound.getBoundPlugin())) continue;

            // The bound plugin was just proven equal to the requested one, so the wildcard really
            // is P; the compiler simply cannot see a runtime equality check.
            @SuppressWarnings("unchecked")
            PluginBoundPandasticService<P> typed = (PluginBoundPandasticService<P>) bound;
            matches.add(typed);
        }
        return List.copyOf(matches);
    }

    @Override
    public <T extends PandasticService> void registerService(@NotNull T service) {
        Objects.requireNonNull(service, "service");

        String name = service.getServiceName();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Service " + service.getClass().getName()
                    + " reports a blank service name; it cannot be registered or looked up.");
        }

        PandasticService previous = byName.putIfAbsent(name, service);
        if (previous == service) return;
        if (previous != null) {
            throw new IllegalStateException("A different service is already registered as '" + name
                    + "' (" + previous.getClass().getName() + " vs " + service.getClass().getName()
                    + "). Replacing a shared service would strand every plugin already holding the"
                    + " previous one.");
        }
        registered.add(service);
    }

    /**
     * {@inheritDoc}
     * <h4>Matching strategy</h4>
     * The registered instance is located by reference identity first and, failing that, by
     * {@link PandasticService#getServiceName() service-name} equality against the name index. The
     * fallback is what makes bridged unregistration work at all: when a follower registered the
     * service, this registry holds a leader-side proxy wrapping the follower's object, and the
     * follower's later unregister call delivers a <em>fresh</em> proxy over the same object — never
     * the reference that was stored. The service name, which this registry already enforces as the
     * unique key, is the identity that survives the boundary (a proxy delegates
     * {@code getServiceName()} to the object it wraps, so both sides report the same string); it is
     * preferred over unwrapping both proxies to their common target because it also matches when
     * the stored proxy cannot be verified for unwrapping.
     * <h4>Concurrency</h4>
     * Removal mirrors registration in reverse: {@link #registerService} claims {@link #byName}
     * first and then appends to {@link #registered}, so this method removes from
     * {@link #registered} first and releases the name last, preserving the invariant that every
     * listed service is also indexed. The list removal is the linearization point — exactly one of
     * two racing unregister calls removes the element and returns {@code true} — and the name entry
     * is released only by that winner, and only if it still maps to the removed instance.
     */
    @Override
    public boolean unregisterService(@NotNull PandasticService service) {
        Objects.requireNonNull(service, "service");

        PandasticService target = null;
        for (PandasticService candidate : registered) {
            if (candidate == service) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            String name = service.getServiceName();
            if (name == null || name.isBlank()) return false;
            target = byName.get(name);
            if (target == null) return false;
        }
        return removeRegistration(target);
    }

    /**
     * {@inheritDoc}
     * <h4>Why plugin name, not plugin instance</h4>
     * Candidates are matched on {@link Plugin#getName() name} equality of their
     * {@link PluginBoundPandasticService#getBoundPlugin() bound plugin}, as the interface documents: a
     * reloaded plugin is a new {@code Plugin} instance in a new classloader, so an identity match
     * would skip precisely the registrations a reload needs cleaned up. A candidate whose
     * {@code getBoundPlugin()} throws — a half-unloaded foreign service, say — is skipped and
     * reported rather than allowed to abort the sweep, because this method runs on the follower's
     * shutdown path and must remove as much as it can.
     * <p>
     * Leader-side teardown does not use this: {@link #clear()} drops the whole registry at once.
     * <h4>Trusted bridge packages are swept too</h4>
     * This method is the plugin-lifecycle sweep, so it also drops every bridge package prefix the
     * plugin registered via {@code PandasticCore.registerBridgePackage}: an unloaded plugin's prefix
     * must not keep its interfaces proxyable. The prefixes live in the JVM-global anchor, which is
     * read here directly because the sweep must work regardless of which classloader the dying
     * plugin's library copy lived in.
     */
    @Override
    public int unregisterAll(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String pluginName = plugin.getName();

        int removed = 0;
        // CopyOnWriteArrayList iteration is a snapshot, so removing while iterating is safe.
        for (PandasticService candidate : registered) {
            if (!(candidate instanceof PluginBoundPandasticService<?> bound)) continue;
            if (!isBoundTo(bound, pluginName)) continue;
            if (removeRegistration(candidate)) removed++;
        }

        PandasticBridgePackages.removeAll(PandasticJvmAnchor.anchorMap(), pluginName);
        return removed;
    }

    /**
     * Whether {@code bound} belongs to the plugin with the given name, never letting a misbehaving
     * service break the caller's sweep.
     */
    private static boolean isBoundTo(@NotNull PluginBoundPandasticService<?> bound, @NotNull String pluginName) {
        try {
            Plugin boundPlugin = bound.getBoundPlugin();
            return pluginName.equals(boundPlugin.getName());
        } catch (RuntimeException | LinkageError e) {
            PandasticCoreLog.warn("Could not ask a registered service which plugin it is bound to;"
                    + " skipping it while unregistering services of plugin '" + pluginName + "'.", e);
            return false;
        }
    }

    /**
     * Removes one resolved registration from both structures, identity-based throughout.
     * <p>
     * {@code removeIf} with an identity predicate rather than {@code remove(instance)}: removal
     * must never depend on a service's own {@code equals}, which a foreign proxy is free to
     * implement surprisingly. The name index is cleaned by value identity for the same reason, and
     * conditionally — a concurrent re-registration that already claimed the name keeps its entry.
     *
     * @param instance the exact instance held by {@link #registered}
     * @return {@code true} if this call removed it, {@code false} if another call got there first
     */
    private boolean removeRegistration(@NotNull PandasticService instance) {
        if (!registered.removeIf(candidate -> candidate == instance)) return false;
        byName.values().removeIf(indexed -> indexed == instance);
        return true;
    }

    @Override
    public <P extends Plugin, T extends PluginBoundPandasticService<?>> boolean hasRegisteredService(@NotNull P plugin,
                                                                                                @NotNull Class<T> serviceType) {
        return getServiceForPlugin(plugin, serviceType) != null;
    }

    @Override
    public @Nullable <T extends PluginBoundPandasticService<?>> T getServiceForPlugin(@NotNull Plugin plugin,
                                                                                 @NotNull Class<T> serviceType) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(serviceType, "serviceType");

        for (PandasticService service : registered) {
            if (!serviceType.isInstance(service)) continue;
            T typed = serviceType.cast(service);
            if (plugin.equals(typed.getBoundPlugin())) return typed;
        }
        return null;
    }

    /**
     * Drops every registration.
     * <p>
     * Not part of {@link PandasticServiceManager}: it is leader-side teardown, invoked once by
     * {@link PandasticCoreImpl#shutdown()} after the anchor has been released, and must never be
     * reachable from a follower. Followers remove their own registrations through
     * {@link #unregisterAll(Plugin)} instead; the two do not conflict, because this wholesale
     * wipe only runs when the leader itself dies, at which point there is nothing left to
     * unregister selectively.
     */
    void clear() {
        registered.clear();
        byName.clear();
    }
}
