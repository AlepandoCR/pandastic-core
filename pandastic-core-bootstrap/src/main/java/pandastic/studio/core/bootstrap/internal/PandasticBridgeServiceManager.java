package pandastic.studio.core.bootstrap.internal;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeIds;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeTypeContract;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeValueAdapter;
import pandastic.studio.core.bootstrap.internal.proxy.PandasticServiceProxyFactory;
import pandastic.studio.core.service.api.PluginBoundPandasticService;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follower-side {@link PandasticServiceManager} that forwards every lookup to the leader's registry.
 * <p>
 * Returned services are follower-local proxies ({@link PandasticServiceProxyFactory}); arguments are
 * adapted by {@link BridgeValueAdapter} so the leader can hold them. {@code Class} tokens used as
 * <b>filters</b> are translated into the leader's classloader first — the leader's
 * {@code isInstance} checks would otherwise silently never match a follower's {@code Class}
 * object, and "no services found" is a much harder failure to diagnose than an error.
 * <p>
 * Unlike {@code PandasticReflectiveApi}, this class binds to one leader-side object for its lifetime: it
 * is created from the service manager the follower resolved once, and is discarded wholesale when
 * the follower detaches.
 * <h2>The target is untrusted until checked</h2>
 * One of the two ways that object is obtained is a read from the anchor's cached-service-manager
 * slot, which lives in the same world-writable {@link System} property as the leader. Binding to it
 * means resolving method names on its concrete class and invoking them through a
 * {@link MethodHandle}, so an unchecked target would let anything in the JVM receive every service
 * lookup and every {@code registerService} from every plugin — while leaving a legitimate leader
 * untouched in its own slot, so none of the leader-side checks would ever fire. The constructor
 * therefore applies the same structural check the leader gets
 * ({@link PandasticLeaderValidator#isTrustedBridgeTarget}) and refuses to construct otherwise.
 */
public final class PandasticBridgeServiceManager implements PandasticServiceManager {

    /**
     * Interface a bridged service manager must declare, taken from this copy's own type so it
     * cannot drift from the type whose methods are actually invoked.
     */
    public static final String REQUIRED_INTERFACE_FQN = PandasticServiceManager.class.getName();

    private final Object leaderServiceManager;
    private final Map<String, ResolvedLeaderCall> callCache = new ConcurrentHashMap<>();

    private record ResolvedLeaderCall(Method method, MethodHandle handle) {
    }

    /**
     * @param leaderServiceManager the leader-owned service manager, as read from the anchor or
     *                             fetched through the bridge
     * @throws IllegalStateException if the object does not structurally qualify as a service
     *                               manager, which means it was not published by a leader of this
     *                               library
     */
    public PandasticBridgeServiceManager(@NotNull Object leaderServiceManager) {
        Objects.requireNonNull(leaderServiceManager, "leaderServiceManager");
        if (!PandasticLeaderValidator.isTrustedBridgeTarget(leaderServiceManager, REQUIRED_INTERFACE_FQN)) {
            throw new IllegalStateException("Refusing to bridge service-manager calls onto "
                    + PandasticLeaderValidator.describeRejection(leaderServiceManager, REQUIRED_INTERFACE_FQN)
                    + ". A bridge target must be a public " + BridgeTypeContract.LIBRARY_PACKAGE_PREFIX
                    + "* type declaring " + REQUIRED_INTERFACE_FQN + "; duck typing is not enough,"
                    + " because this object receives every service call from every plugin in the JVM.");
        }
        this.leaderServiceManager = leaderServiceManager;
    }

    @Override
    public @NotNull List<PandasticService> getAllServices() {
        Object raw = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.getAllServices, new Object[]{});
        if (!(raw instanceof List<?> services)) return List.of();

        List<PandasticService> local = new ArrayList<>(services.size());
        for (Object leaderService : services) {
            if (leaderService == null) continue;
            local.add(PandasticServiceProxyFactory.proxyAs(PandasticService.class, leaderService));
        }
        return List.copyOf(local);
    }

    @Override
    public @NotNull <T extends PandasticService> List<T> getServicesByType(@NotNull Class<T> serviceType) {
        Object raw = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.getServicesByType__Class,
                new Object[]{translateFilterClass(serviceType)});
        if (!(raw instanceof List<?> services)) return List.of();

        List<T> local = new ArrayList<>(services.size());
        for (Object leaderService : services) {
            if (leaderService == null) continue;
            local.add(PandasticServiceProxyFactory.proxyAs(serviceType, leaderService));
        }
        return List.copyOf(local);
    }

    @Override
    public @Nullable PandasticService getService(@NotNull String name) {
        Object leaderService = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.getService__String,
                new Object[]{name});
        if (leaderService == null) return null;

        // The concrete interface is unknown here; a PandasticService proxy still exposes the name,
        // description and any other proxyable interface the leader object implements. Consumers
        // that need their own interface honoured use the typed overload below.
        return PandasticServiceProxyFactory.proxyAs(PandasticService.class, leaderService);
    }

    @Override
    public @Nullable <T extends PandasticService> T getService(@NotNull String name, @NotNull Class<T> as) {
        // Same shape as getServicesByType: translate the type token into the leader's classloader
        // so the leader-side cast can match, then view the result through the caller's own token.
        Object leaderService = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.getService__String__Class,
                new Object[]{name, translateFilterClass(as)});
        if (leaderService == null) {
            explainMissingService(name, as);
            return null;
        }
        return PandasticServiceProxyFactory.proxyAs(as, leaderService);
    }

    /**
     * Says why a typed lookup came back empty.
     *
     * <h4>Why this is worth the lines</h4>
     *
     * A {@code null} from {@code getService} has three unrelated causes and the caller cannot tell
     * them apart: nobody registered that name, the name is registered but the requested interface
     * does not exist in the leader's classloader, or the interface exists but is not proxyable so
     * the bridge refuses to carry it. All three look like "the other plugin is not installed",
     * which is the wrong conclusion for two of them — and a consumer that wraps the call in a
     * {@code catch (RuntimeException) { return null; }} loses even the exception text.
     * <p>
     * The third case is the one that bites a follower asking for another follower's service: the
     * requested interface lives in the <em>registering</em> plugin's jar, and unless its package
     * was published with {@code PandasticCore.registerBridgePackage} the bridge has no mandate to proxy
     * it. The lookup then fails while both plugins are running and healthy, which is close to
     * undiagnosable from the outside.
     *
     * @param name the service name that was asked for
     * @param as   the interface it was asked for as
     */
    private void explainMissingService(@NotNull String name, @NotNull Class<?> as) {
        Object untyped = invokeLeaderByContractId(
                GeneratedBridgeIds.PandasticServiceManager.getService__String, new Object[]{name});
        if (untyped == null) {
            // Not a warning: asking for a service that is not installed is a normal thing for an
            // optional integration to do, and warning about it would punish soft dependencies.
            PandasticCoreLog.debug("No service is registered under the name '" + name
                    + "'. Either the plugin that owns it is not installed, it has not enabled yet,"
                    + " or it registered under a different name.");
            return;
        }

        if (!BridgeTypeContract.isProxyableInterfaceType(as)) {
            PandasticCoreLog.warn("'" + name + "' IS registered, but it cannot be handed over as "
                    + as.getName() + ": that type is neither one of this library's own interfaces"
                    + " nor under a package published with PandasticCore.registerBridgePackage, so the"
                    + " bridge has no mandate to proxy it. The plugin that REGISTERS the service"
                    + " must publish its API package - registerBridgePackage(plugin, \""
                    + packagePrefixOf(as) + "\") - and must do so BEFORE it registers the service."
                    + (as.isInterface() ? "" : " It is also not an interface, which no amount of"
                            + " registration can make proxyable."));
            return;
        }

        boolean visibleToLeader;
        try {
            Class.forName(as.getName(), false, leaderServiceManager.getClass().getClassLoader());
            visibleToLeader = true;
        } catch (ClassNotFoundException | LinkageError notThere) {
            visibleToLeader = false;
        }
        if (!visibleToLeader) {
            PandasticCoreLog.warn("'" + name + "' IS registered and " + as.getName() + " is proxyable,"
                    + " but the leader's classloader cannot see that type, so the leader-side cast"
                    + " can never match. The usual causes are two plugins built against different"
                    + " versions of the interface, or a leader that cannot reach the registering"
                    + " plugin's classes at all.");
            return;
        }
        PandasticCoreLog.warn("'" + name + "' IS registered and " + as.getName() + " is both proxyable"
                + " and visible to the leader, yet the typed lookup returned nothing - so the"
                + " registered object does not implement the requested interface. Check what type"
                + " the owning plugin actually registered under that name.");
    }

    /**
     * The package prefix a plugin would publish to make this type proxyable.
     *
     * @param type the interface that was refused
     * @return its package name, or the type name when it has no package
     */
    private static @NotNull String packagePrefixOf(@NotNull Class<?> type) {
        int lastDot = type.getName().lastIndexOf('.');
        return lastDot < 0 ? type.getName() : type.getName().substring(0, lastDot);
    }

    @Override
    public @NotNull <P extends Plugin> List<PluginBoundPandasticService<P>> getPluginBoundServices(@NotNull P plugin) {
        Object raw = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.getPluginBoundServices__Plugin,
                new Object[]{plugin});
        if (!(raw instanceof List<?> services)) return List.of();

        @SuppressWarnings("unchecked")
        Class<PluginBoundPandasticService<P>> api = (Class<PluginBoundPandasticService<P>>) (Class<?>) PluginBoundPandasticService.class;

        List<PluginBoundPandasticService<P>> local = new ArrayList<>(services.size());
        for (Object leaderService : services) {
            if (leaderService == null) continue;
            local.add(PandasticServiceProxyFactory.proxyAs(api, leaderService));
        }
        return List.copyOf(local);
    }

    @Override
    public <T extends PandasticService> void registerService(@NotNull T service) {
        invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.registerService__PandasticService,
                new Object[]{service});
    }

    @Override
    public boolean unregisterService(@NotNull PandasticService service) {
        // The argument is adapted exactly like in registerService, so the leader receives a fresh
        // proxy rather than the one it stored; the leader-side registry resolves that by service
        // name, as the interface documents.
        Object raw = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.unregisterService__PandasticService,
                new Object[]{service});
        return raw instanceof Boolean removed && removed;
    }

    @Override
    public int unregisterAll(@NotNull Plugin plugin) {
        // Plugin is parent-visible, so it crosses unchanged, same as in getPluginBoundServices.
        Object raw = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.unregisterAll__Plugin,
                new Object[]{plugin});
        return raw instanceof Integer removed ? removed : 0;
    }

    @Override
    public <P extends Plugin, T extends PluginBoundPandasticService<?>> boolean hasRegisteredService(@NotNull P plugin,
                                                                                                @NotNull Class<T> serviceType) {
        Object raw = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.hasRegisteredService__Plugin__Class,
                new Object[]{plugin, translateFilterClass(serviceType)});
        return raw instanceof Boolean registered && registered;
    }

    @Override
    public @Nullable <T extends PluginBoundPandasticService<?>> T getServiceForPlugin(@NotNull Plugin plugin,
                                                                                 @NotNull Class<T> serviceType) {
        Object leaderService = invokeLeaderByContractId(GeneratedBridgeIds.PandasticServiceManager.getServiceForPlugin__Plugin__Class,
                new Object[]{plugin, translateFilterClass(serviceType)});
        if (leaderService == null) return null;
        return PandasticServiceProxyFactory.proxyAs(serviceType, leaderService);
    }

    /**
     * Translates a {@code Class} token into the leader's classloader so leader-side
     * {@code isInstance}/cast checks can match.
     * <p>
     * Library types are always translated. A consumer interface is translated once its package is
     * {@linkplain BridgeTypeContract#isProxyableInterfaceType(Class) registered as trusted} —
     * without that, the token crosses unchanged, which matches only services the leader can
     * already hold through the caller's own {@code Class} (the shared-classloader case). Every
     * other type is parent-visible or deliberately untrusted and crosses unchanged.
     * <p>
     * Falls back to the local token when the leader's loader does not have the type, which matches
     * nothing. That outcome is reported: it means the two sides disagree on the API, and a
     * silently empty result would look like "no such service is registered".
     */
    private @NotNull Class<?> translateFilterClass(@NotNull Class<?> localType) {
        if (!BridgeTypeContract.isLibraryType(localType)
                && !BridgeTypeContract.isProxyableInterfaceType(localType)) {
            return localType;
        }
        try {
            return Class.forName(localType.getName(), false, leaderServiceManager.getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            PandasticCoreLog.warn("The leader's classloader has no " + localType.getName()
                    + ", so filtering by it cannot match anything. Either the two plugins are running"
                    + " different versions of this library, or a trusted consumer type is not visible"
                    + " where the leader lives.", e);
            return localType;
        }
    }

    private Object invokeLeaderByContractId(@NotNull String contractId, Object[] args) {
        Objects.requireNonNull(contractId, "contractId");

        Object[] actualArgs = args == null ? new Object[0] : args;
        GeneratedBridgeContract.Spec spec = PandasticBootstrapReflection.loadGeneratedSpec(contractId);
        String key = PandasticBootstrapReflection.cacheKeyByContractId(contractId);

        ResolvedLeaderCall call = callCache.computeIfAbsent(key, ignored -> {
            Method targetMethod = PandasticBootstrapReflection.resolveByGeneratedSpec(leaderServiceManager.getClass(), spec);
            return new ResolvedLeaderCall(targetMethod, PandasticBootstrapReflection.unreflectPublic(targetMethod));
        });

        try {
            Object[] adapted = BridgeValueAdapter.adaptArguments(call.method(), actualArgs, getClass().getClassLoader());
            Object[] full = new Object[adapted.length + 1];
            full[0] = leaderServiceManager;
            System.arraycopy(adapted, 0, full, 1, adapted.length);
            return call.handle().invokeWithArguments(full);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Leader service manager call " + contractId + " failed.", t);
        }
    }
}
