package pandastic.studio.core.service.internal;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.internal.PandasticBootstrap;
import pandastic.studio.core.bootstrap.internal.PandasticBridgePackages;
import pandastic.studio.core.bootstrap.internal.PandasticJvmAnchor;
import pandastic.studio.core.bootstrap.internal.PandasticLibrary;
import pandastic.studio.core.service.api.PandasticCore;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;
import pandastic.studio.core.service.economy.PandasticEconomyService;
import pandastic.studio.core.service.internal.economy.PandasticEconomyServiceImpl;
import pandastic.studio.core.service.internal.profile.BedrockProfileServiceImpl;
import pandastic.studio.core.service.internal.profile.JavaProfileServiceImpl;
import pandastic.studio.core.service.internal.profile.PandasticProfileCacheImpl;
import pandastic.studio.core.service.internal.profile.UniversalProfileServiceImpl;
import pandastic.studio.core.service.internal.runtime.PandasticRuntime;
import pandastic.studio.core.service.profile.PandasticProfileService;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The leader implementation: the one object in the JVM that actually owns the shared runtime.
 * <p>
 * Exactly one of these exists per elected leader. It is created by {@link PandasticCoreProviderImpl}
 * while {@code PandasticBootstrap} holds the anchor lock, then published to the JVM-global anchor, where
 * every other plugin's follower bridge reflectively invokes it. Everything it exposes across that
 * boundary is therefore either an interface or a parent-visible type; {@link #getRuntime()} is
 * public for the implementation module's own use but is deliberately not part of the bridged
 * surface, because {@link PandasticRuntime} is a class and could not be held by a follower.
 *
 * @see PandasticCore#instance(org.bukkit.plugin.java.JavaPlugin) the entry point plugins actually call
 */
public final class PandasticCoreImpl implements PandasticCore {

    private final Plugin owner;
    private final PandasticRuntime runtime;
    private final PandasticServiceManagerImpl serviceManager;

    /**
     * Guards {@link #shutdown()} against repeat invocation. A plugin's {@code onDisable} can run
     * after an earlier explicit shutdown, and tearing the shared runtime down twice would turn a
     * harmless double call into an exception inside an unrelated plugin's disable sequence.
     */
    private final AtomicBoolean active = new AtomicBoolean(true);

    /**
     * One lock object per requesting plugin for the get-or-create of its profile services.
     * <p>
     * Per-plugin rather than one global monitor so two plugins enabling concurrently do not
     * serialize each other's service creation, and a lock object rather than
     * {@code computeIfAbsent} on the registry because creation registers <em>two</em> services
     * (the service and, first time round, the shared cache) and adopts the cache owner — several
     * steps that must be atomic per plugin. Entries are dropped in {@link #shutdown()}.
     */
    private final Map<Plugin, Object> profileServiceLocks = new ConcurrentHashMap<>();

    /**
     * The one shared economy service, created on first request. Volatile for the double-checked
     * get-or-create in {@link #getEconomyService()}: unlike the profile services it is not
     * per-plugin, so a single instance-wide monitor suffices.
     */
    private volatile @Nullable PandasticEconomyServiceImpl economyService;

    /**
     * @param owner the plugin that won leader election and whose classloader hosts this instance
     */
    public PandasticCoreImpl(@NotNull Plugin owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.runtime = new PandasticRuntime();
        this.serviceManager = new PandasticServiceManagerImpl();
    }

    /**
     * The shared thread pool and HTTP client this leader owns.
     * <p>
     * Leader-local by design: not bridged, not reachable from another plugin. Services built in
     * this module take their executor from here and expose it as a plain
     * {@link java.util.concurrent.ExecutorService}, which <em>is</em> parent-visible and can
     * legitimately cross the bridge.
     */
    public @NotNull PandasticRuntime getRuntime() {
        return runtime;
    }

    /**
     * The plugin that owns this leader.
     */
    public @NotNull Plugin getOwner() {
        return owner;
    }

    @Override
    public @NotNull Collection<PandasticService> getServices() {
        return serviceManager.getAllServices();
    }

    @Override
    public @NotNull PandasticServiceManager getServiceManager() {
        return serviceManager;
    }

    @Override
    public @NotNull <P extends Plugin> PandasticProfileService<P> getJavaProfileService(@NotNull P plugin) {
        return javaProfileService(plugin);
    }

    @Override
    public @NotNull <P extends Plugin> PandasticProfileService<P> getBedrockProfileService(@NotNull P plugin) {
        return bedrockProfileService(plugin);
    }

    @Override
    public @NotNull <P extends Plugin> PandasticProfileService<P> getUniversalProfileService(@NotNull P plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (profileLock(plugin)) {
            UniversalProfileServiceImpl<?> existing =
                    serviceManager.getServiceForPlugin(plugin, UniversalProfileServiceImpl.class);
            if (existing != null) return boundTo(existing, plugin);

            // The universal service composes the plugin's other two, creating them on demand;
            // the per-plugin lock is reentrant, so the nested get-or-creates are safe.
            BedrockProfileServiceImpl<P> bedrock = bedrockProfileService(plugin);
            JavaProfileServiceImpl<P> java = javaProfileService(plugin);
            UniversalProfileServiceImpl<P> created = new UniversalProfileServiceImpl<>(
                    plugin, profileCacheFor(plugin), bedrock, java,
                    bedrock::isPrefixedBedrockName, runtime.getCommonPool());
            registerProfileService(created);
            return created;
        }
    }

    @Override
    public @NotNull PandasticEconomyService getEconomyService() {
        PandasticEconomyServiceImpl existing = economyService;
        if (existing != null) return existing;

        synchronized (this) {
            PandasticEconomyServiceImpl current = economyService;
            if (current == null) {
                // Scheduled through the LEADER's plugin: the service is server-global, so its
                // main-thread hops belong to the runtime owner, not to whichever plugin asked first.
                current = PandasticEconomyServiceImpl.vaultBacked(owner, runtime.getCommonPool());
                serviceManager.registerService(current);
                economyService = current;
            }
            return current;
        }
    }

    private <P extends Plugin> @NotNull JavaProfileServiceImpl<P> javaProfileService(@NotNull P plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (profileLock(plugin)) {
            JavaProfileServiceImpl<?> existing =
                    serviceManager.getServiceForPlugin(plugin, JavaProfileServiceImpl.class);
            if (existing != null) return boundTo(existing, plugin);

            JavaProfileServiceImpl<P> created = new JavaProfileServiceImpl<>(
                    plugin, profileCacheFor(plugin), runtime.getCommonPool(), runtime.getHttpClient());
            registerProfileService(created);
            return created;
        }
    }

    private <P extends Plugin> @NotNull BedrockProfileServiceImpl<P> bedrockProfileService(@NotNull P plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (profileLock(plugin)) {
            BedrockProfileServiceImpl<?> existing =
                    serviceManager.getServiceForPlugin(plugin, BedrockProfileServiceImpl.class);
            if (existing != null) return boundTo(existing, plugin);

            BedrockProfileServiceImpl<P> created = new BedrockProfileServiceImpl<>(
                    plugin, profileCacheFor(plugin), runtime.getCommonPool(), runtime.getHttpClient(),
                    BedrockProfileServiceImpl.isFloodgateAvailable(plugin));
            registerProfileService(created);
            return created;
        }
    }

    /**
     * Registers a freshly created profile service, letting it adopt the shared cache first.
     * <p>
     * {@code adoptOwner} only sets on the first call, so the cache's owner is the first profile
     * service ever created against this runtime; {@code registerService(cache)} is idempotent for
     * the same instance, so re-registering on every creation is a no-op after the first.
     */
    private <P extends Plugin> void registerProfileService(@NotNull PandasticProfileService<P> created) {
        PandasticProfileCacheImpl<P> cache = profileCacheFor(created.getBoundPlugin());
        cache.adoptOwner(created);
        serviceManager.registerService(cache);
        serviceManager.registerService(created);
    }

    /**
     * The runtime's single profile cache, viewed for a specific plugin type.
     * <p>
     * The cast is unchecked but sound: the cache's plugin type parameter is semantically unused —
     * no cached datum mentions it — so one instance legitimately serves every {@code P}.
     */
    private <P extends Plugin> @NotNull PandasticProfileCacheImpl<P> profileCacheFor(@NotNull P plugin) {
        @SuppressWarnings("unchecked")
        PandasticProfileCacheImpl<P> cache = (PandasticProfileCacheImpl<P>) runtime.getProfileCache();
        return cache;
    }

    /**
     * Re-types a registered profile service to the requesting plugin's own type parameter.
     * <p>
     * Unchecked but sound: the registry matched the instance on bound-plugin identity, so the
     * service was constructed with exactly {@code plugin} — whose type <em>is</em> {@code P}.
     */
    private static <P extends Plugin, S extends PandasticProfileService<?>> @NotNull S boundTo(
            @NotNull PandasticProfileService<?> existing, @NotNull P plugin) {
        assert existing.getBoundPlugin() == plugin;
        @SuppressWarnings("unchecked")
        S bound = (S) existing;
        return bound;
    }

    private @NotNull Object profileLock(@NotNull Plugin plugin) {
        return profileServiceLocks.computeIfAbsent(plugin, ignored -> new Object());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Writes the prefix into the JVM-global anchor slot ({@link PandasticBridgePackages}) rather than
     * any leader-local structure, because the predicate that consumes it runs in whichever library
     * copy is <em>receiving</em> a value — a follower's copy adapting a return value, this copy
     * adapting an argument — and only the anchor is visible to all of them. Validation and the
     * INFO telemetry line both live in {@link PandasticBridgePackages#register}.
     */
    @Override
    public void registerBridgePackage(@NotNull Plugin owner, @NotNull String packagePrefix) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(packagePrefix, "packagePrefix");
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), owner.getName(), packagePrefix);
    }

    @Override
    public void shutdown() {
        if (!active.compareAndSet(true, false)) return;

        // The owner plugin is going away, so its trusted bridge packages go with it. Other
        // plugins' registrations stay in the anchor: they outlive this leader and remain valid
        // for whichever leader is elected next. (If this was the last participant, the anchor
        // reset inside releaseLeader clears the slot wholesale anyway.)
        PandasticBridgePackages.removeAll(PandasticJvmAnchor.anchorMap(), owner.getName());

        // Release the election first, then drop local state. releaseLeader only runs the teardown
        // once no follower is left holding a bridge onto this instance, so the pool outlives this
        // call whenever another plugin is still using it.
        PandasticBootstrap.releaseLeader(this, runtime::shutdown);
        serviceManager.clear();
        profileServiceLocks.clear();
        economyService = null;
    }

    @Override
    public @NotNull String getServiceName() {
        return PandasticLibrary.CORE_SERVICE_NAME;
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(PandasticLibrary.DISPLAY_NAME);
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Shared " + PandasticLibrary.DISPLAY_NAME + " runtime, owned by " + owner.getName() + ".");
    }
}
