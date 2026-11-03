package pandastic.studio.core.bootstrap.internal.service;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeIds;
import pandastic.studio.core.bootstrap.internal.PandasticApiRegistry;
import pandastic.studio.core.bootstrap.internal.PandasticBootstrap;
import pandastic.studio.core.bootstrap.internal.PandasticBootstrapReflection;
import pandastic.studio.core.bootstrap.internal.PandasticBridgePackages;
import pandastic.studio.core.bootstrap.internal.PandasticBridgeServiceManager;
import pandastic.studio.core.bootstrap.internal.PandasticCoreLog;
import pandastic.studio.core.bootstrap.internal.PandasticJvmAnchor;
import pandastic.studio.core.bootstrap.internal.PandasticLeaderValidator;
import pandastic.studio.core.bootstrap.internal.PandasticLibrary;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeValueAdapter;
import pandastic.studio.core.bootstrap.internal.proxy.PandasticServiceProxyFactory;
import pandastic.studio.core.service.api.PandasticCore;
import pandastic.studio.core.service.api.PandasticCoreProvider;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;
import pandastic.studio.core.service.economy.PandasticEconomyService;
import pandastic.studio.core.service.profile.PandasticProfileService;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The follower side of {@link PandasticCore}: a local object of the caller's own type that forwards every
 * bridged call to the leader's instance.
 * <p>
 * Nothing is cached about the leader except things tagged with the leader they belong to, because
 * the leader can change: its owning plugin may be disabled and a new leader elected in another
 * classloader. Each call therefore re-reads the current leader through
 * {@link PandasticBootstrap#ensureLeader}, which re-validates what it finds in the anchor before
 * returning it, and both the resolved {@link MethodHandle}s and the service-manager bridge are
 * discarded when the leader they were built against is no longer the elected one.
 */
public final class PandasticReflectiveApi implements PandasticCore {

    private final JavaPlugin caller;
    private final PandasticCoreProvider provider;
    private final boolean logging;

    private final Map<String, ResolvedLeaderCall> callCache = new ConcurrentHashMap<>();

    private volatile PandasticServiceManager serviceManagerProxy;

    /**
     * The leader {@link #serviceManagerProxy} was built from, compared by identity.
     * <p>
     * A leader is not forever: its owning plugin can be disabled and a new leader elected in
     * another classloader. The old leader's registry has been cleared by then, so a cached bridge
     * onto it accepts registrations that nobody will ever see and reports no services — silently,
     * which is the worst possible failure for a registry. Tagging the cache with the leader it
     * belongs to turns that into a transparent rebuild.
     */
    private volatile Object serviceManagerLeader;

    private PandasticReflectiveApi(@NotNull JavaPlugin caller, @NotNull PandasticCoreProvider provider, boolean logging) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.logging = logging;
    }

    /**
     * Creates a follower bridge for {@code caller}.
     *
     * @param caller   the plugin that lost leader election
     * @param provider factory used if the leader ever has to be re-elected in this classloader
     * @param logging  whether re-election messages go to the caller's logger
     */
    public static @NotNull PandasticCore create(@NotNull JavaPlugin caller,
                                          @NotNull PandasticCoreProvider provider,
                                          boolean logging) {
        return new PandasticReflectiveApi(caller, provider, logging);
    }

    @Override
    public @NotNull Collection<PandasticService> getServices() {
        Object raw = invokeLeaderByContractId(GeneratedBridgeIds.PandasticLibApi.getServices, new Object[]{});
        if (!(raw instanceof Collection<?> services)) return List.of();

        List<PandasticService> local = new ArrayList<>(services.size());
        for (Object leaderService : services) {
            if (leaderService == null) continue;
            local.add(PandasticServiceProxyFactory.proxyAs(PandasticService.class, leaderService));
        }
        return List.copyOf(local);
    }

    @Override
    public @NotNull PandasticServiceManager getServiceManager() {
        Object leader = PandasticBootstrap.ensureLeader(caller, provider, logging);

        PandasticServiceManager existing = serviceManagerProxy;
        if (existing != null && serviceManagerLeader == leader) return existing;

        synchronized (this) {
            if (serviceManagerProxy != null && serviceManagerLeader == leader) return serviceManagerProxy;

            Object leaderServiceManager = cachedLeaderServiceManager();
            if (leaderServiceManager == null) {
                leaderServiceManager = invokeLeaderByContractId(
                        GeneratedBridgeIds.PandasticLibApi.getServiceManager, new Object[]{});
            }

            serviceManagerProxy = new PandasticBridgeServiceManager(leaderServiceManager);
            serviceManagerLeader = leader;
            return serviceManagerProxy;
        }
    }

    /**
     * Reads the service manager the leader cached in the anchor at election time, <b>if it can be
     * trusted</b>.
     * <p>
     * The slot is only an optimisation that saves one reflective call, and its absence is normal (a
     * leader whose {@code getServiceManager()} failed at election time never populated it). What it
     * is <em>not</em> is trustworthy: it sits in the same world-writable {@link System} property as
     * the leader, and whatever comes out of it becomes the target of every service call this
     * follower makes. An attacker that writes only this slot leaves a legitimate leader in place,
     * so none of the leader-side checks fire — which is exactly why the same structural check is
     * applied here.
     * <p>
     * A rejected or unreadable slot is reported and the caller falls back to fetching the manager
     * through the validated leader, which is the authoritative path anyway.
     */
    private @Nullable Object cachedLeaderServiceManager() {
        Object cached;
        try {
            cached = PandasticJvmAnchor.anchorMap().get(PandasticBootstrap.KEY_LEADER_SERVICE_MANAGER);
        } catch (RuntimeException e) {
            PandasticCoreLog.debug("Could not read the cached leader service manager from the anchor;"
                    + " falling back to a bridged call.", e);
            return null;
        }

        if (cached == null) return null;
        if (PandasticLeaderValidator.isTrustedBridgeTarget(cached, PandasticBridgeServiceManager.REQUIRED_INTERFACE_FQN)) {
            return cached;
        }

        PandasticCoreLog.warn("Ignoring the anchor's cached service manager: "
                + PandasticLeaderValidator.describeRejection(cached, PandasticBridgeServiceManager.REQUIRED_INTERFACE_FQN)
                + ". Fetching it through the validated leader instead. That slot lives in a"
                + " world-writable system property, so this is either a key collision or an attempt to"
                + " intercept every plugin's service calls.");
        return null;
    }

    @Override
    public @NotNull <P extends Plugin> PandasticProfileService<P> getJavaProfileService(@NotNull P plugin) {
        return profileService(GeneratedBridgeIds.PandasticLibApi.getJavaProfileService__Plugin, plugin);
    }

    @Override
    public @NotNull <P extends Plugin> PandasticProfileService<P> getBedrockProfileService(@NotNull P plugin) {
        return profileService(GeneratedBridgeIds.PandasticLibApi.getBedrockProfileService__Plugin, plugin);
    }

    @Override
    public @NotNull <P extends Plugin> PandasticProfileService<P> getUniversalProfileService(@NotNull P plugin) {
        return profileService(GeneratedBridgeIds.PandasticLibApi.getUniversalProfileService__Plugin, plugin);
    }

    @Override
    public @NotNull PandasticEconomyService getEconomyService() {
        Object leaderService = invokeLeaderByContractId(GeneratedBridgeIds.PandasticLibApi.getEconomyService, new Object[]{});
        if (leaderService == null) {
            throw new IllegalStateException("The leader returned no economy service; the bridged"
                    + " contract declares getEconomyService() @NotNull.");
        }
        // Server-global and plugin-agnostic, so no argument crosses; the leader-owned instance is
        // re-wrapped as this copy's own interface like every other bridged service.
        return PandasticServiceProxyFactory.proxyAs(PandasticEconomyService.class, leaderService);
    }

    /**
     * Fetches a leader-owned profile service by contract id and wraps it for the caller.
     * <p>
     * The plugin argument crosses unchanged — {@link Plugin} is parent-visible — so the leader
     * registers the service against the very same plugin instance a later lookup compares with.
     * The returned object is leader-owned and unholdable here, which is why it is re-wrapped
     * through {@link PandasticServiceProxyFactory} as this copy's own {@link PandasticProfileService}.
     * <p>
     * The cast to {@code PandasticProfileService<P>} is unchecked but sound: the type parameter carries
     * no state, and the leader built the instance for exactly the plugin passed in.
     */
    private <P extends Plugin> @NotNull PandasticProfileService<P> profileService(@NotNull String contractId,
                                                                            @NotNull P plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Object leaderService = invokeLeaderByContractId(contractId, new Object[]{plugin});
        if (leaderService == null) {
            throw new IllegalStateException("The leader returned no service for contract id "
                    + contractId + "; the bridged contract declares this method @NotNull.");
        }

        @SuppressWarnings("unchecked")
        Class<PandasticProfileService<P>> api = (Class<PandasticProfileService<P>>) (Class<?>) PandasticProfileService.class;
        return PandasticServiceProxyFactory.proxyAs(api, leaderService);
    }

    /**
     * Forwarded to the leader so the prefix lands in the JVM-global anchor slot every library
     * copy's bridge reads. Both arguments are parent-visible ({@link Plugin}, {@link String}), so
     * they cross unchanged; validation runs leader-side and its
     * {@link IllegalArgumentException} propagates back to the caller as-is.
     */
    @Override
    public void registerBridgePackage(@NotNull Plugin owner, @NotNull String packagePrefix) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(packagePrefix, "packagePrefix");
        invokeLeaderByContractId(GeneratedBridgeIds.PandasticLibApi.registerBridgePackage__Plugin__String,
                new Object[]{owner, packagePrefix});
    }

    @Override
    public void shutdown() {
        // Follower shutdown detaches only: the shared runtime belongs to the leader and other
        // plugins are still using it. But before detaching, this plugin's registrations are pulled
        // out of the leader's registry — they are the only strong references pinning this plugin's
        // classloader inside the leader, so leaving them behind leaks the classloader on every
        // reload for as long as the leader lives. The reverse order (leader dying first) needs no
        // such call: the leader's own shutdown clears its whole registry.
        //
        // Best-effort, through the already-built bridge only. Going through getServiceManager()
        // here could elect a brand-new leader in this shutting-down plugin's classloader, which is
        // the last thing a disable sequence should do — and a follower that never obtained the
        // service manager cannot have registered anything either.
        PandasticServiceManager manager = serviceManagerProxy;
        if (manager != null) {
            try {
                int removed = manager.unregisterAll(caller);
                if (removed > 0) {
                    PandasticCoreLog.debug("Follower " + caller.getName() + " unregistered " + removed
                            + " service(s) from the leader's registry on shutdown.");
                }
            } catch (RuntimeException | LinkageError e) {
                // A dead or older-protocol leader must not make shutdown throw; the worst case is
                // the pre-existing leak, which is worth a warning but never a failed disable.
                PandasticCoreLog.warn("Follower " + caller.getName() + " could not unregister its services"
                        + " from the leader's registry. Any service this plugin registered keeps its"
                        + " classloader pinned until the leader shuts down.", e);
            }
        }

        try {
            Map<String, Object> anchor = PandasticJvmAnchor.anchorMap();
            PandasticApiRegistry.unregisterFollower(anchor, caller);
            // Belt and braces next to the leader-side sweep above: if the leader was unreachable
            // or old, this plugin's trusted bridge packages must still die with it.
            PandasticBridgePackages.removeAll(anchor, caller.getName());
        } catch (RuntimeException e) {
            PandasticCoreLog.warn("Follower " + caller.getName() + " could not detach from the anchor. The"
                    + " leader may now refuse to tear the shared runtime down, because it still counts"
                    + " this plugin as attached.", e);
        }

        serviceManagerProxy = null;
        serviceManagerLeader = null;
        callCache.clear();
    }

    @Override
    public @NotNull String getServiceName() {
        return PandasticLibrary.bridgeServiceName(caller.getName());
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(getServiceName());
    }

    @Override
    public @NotNull Component getDescription() {
        // Described locally, on purpose: this object is the follower's own view of the bridge, and
        // the leader's wording would be misleading here (it would claim to own the runtime).
        return Component.text("Reflection bridge from " + caller.getName()
                + " onto the " + PandasticLibrary.DISPLAY_NAME + " leader runtime.");
    }

    /**
     * Invokes a bridged leader method identified by its generated contract id.
     * <p>
     * Resolution and invocation are deliberately separated. Resolution failures mean the leader
     * went away or changed shape, so they are worth one retry against a freshly elected leader.
     * Failures thrown by the leader method itself are <b>not</b> retried: the previous
     * implementation caught every {@link Throwable} and replayed the whole call, which turned a
     * legitimate exception from, say, {@code registerService} into two registrations and hid the
     * original stack trace behind the second attempt's.
     */
    private Object invokeLeaderByContractId(@NotNull String contractId, Object[] args) {
        Objects.requireNonNull(contractId, "contractId");

        Object[] actualArgs = args == null ? new Object[0] : args;
        GeneratedBridgeContract.Spec spec = PandasticBootstrapReflection.loadGeneratedSpec(contractId);
        String key = PandasticBootstrapReflection.cacheKeyByContractId(contractId);

        Object leader = PandasticBootstrap.ensureLeader(caller, provider, logging);
        ResolvedLeaderCall call = callCache.get(key);
        if (call == null || !call.leaderClass().equals(leader.getClass())) {
            try {
                call = resolveLeaderCall(leader.getClass(), spec);
            } catch (RuntimeException e) {
                // The leader we just read cannot serve this contract id. Force a re-election once:
                // a stale leader whose plugin was disabled is the expected cause.
                callCache.remove(key);
                PandasticCoreLog.warn("Leader " + leader.getClass().getName() + " cannot serve contract id "
                        + contractId + "; re-electing once before giving up.", e);
                leader = PandasticBootstrap.ensureLeader(caller, provider, logging);
                call = resolveLeaderCall(leader.getClass(), spec);
            }
            callCache.put(key, call);
        }

        try {
            return invokeAdapted(call, leader, actualArgs);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Leader call " + contractId + " failed.", t);
        }
    }

    private Object invokeAdapted(@NotNull ResolvedLeaderCall call, @NotNull Object leader, Object[] args) throws Throwable {
        // Adapt arguments to types the leader's classloader can hold (library-typed args become
        // proxies, Dtos become the leader's own copies).
        Object[] adapted = BridgeValueAdapter.adaptArguments(call.method(), args, getClass().getClassLoader());
        Object[] full = new Object[adapted.length + 1];
        full[0] = leader;
        System.arraycopy(adapted, 0, full, 1, adapted.length);
        return call.handle().invokeWithArguments(full);
    }

    private static @NotNull ResolvedLeaderCall resolveLeaderCall(@NotNull Class<?> leaderClass,
                                                                 @NotNull GeneratedBridgeContract.Spec spec) {
        Method targetMethod = PandasticBootstrapReflection.resolveByGeneratedSpec(leaderClass, spec);
        return new ResolvedLeaderCall(leaderClass, targetMethod, PandasticBootstrapReflection.unreflectPublic(targetMethod));
    }

    /**
     * A resolved leader call, tagged with the leader class it was resolved against so a
     * re-election invalidates it instead of invoking a handle bound to the old leader's type.
     */
    private record ResolvedLeaderCall(Class<?> leaderClass, Method method, MethodHandle handle) {
    }
}
