package pandastic.studio.core.service.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.api.contract.BridgeApi;
import pandastic.studio.core.bootstrap.api.contract.BridgeMethod;
import pandastic.studio.core.bootstrap.api.contract.BridgeNamespace;
import pandastic.studio.core.bootstrap.api.contract.BridgeStability;
import pandastic.studio.core.util.api.data.Dto;

import java.util.List;

/**
 * Registry of every {@link PandasticService} owned by the leader runtime.
 * <p>
 * There is exactly one real service manager per JVM, living in the leader's classloader. Every
 * other plugin talks to it through a bridge implementation that forwards each call reflectively,
 * which is why this type is annotated as a bridge API: {@code BridgeContractProcessor} turns the
 * {@link BridgeMethod} annotations below into the deterministic method ids the bridge resolves
 * against, so a follower never has to guess an overload by name.
 * <h2>What may appear in these signatures</h2>
 * Only interfaces, {@link Dto}s and parent-visible types such as
 * {@link Plugin}, {@link Class} and {@link String}. A library <em>class</em> here would be
 * unholdable on the receiving side; {@code BridgeTypeContract} enforces that rule.
 */
@BridgeApi(BridgeNamespace.PANDASTIC_SERVICE_MANAGER)
public interface PandasticServiceManager {

    /**
     * Every registered service, in registration order.
     *
     * @return an immutable snapshot; services registered afterwards are not reflected
     */
    @BridgeMethod
    @NotNull List<PandasticService> getAllServices();

    /**
     * Every registered service assignable to {@code serviceType}.
     * <p>
     * The type token is matched against the <em>leader's</em> copy of the type: the bridge
     * translates a follower's {@code Class} object into the leader's classloader first, because a
     * leader-side {@code isInstance} check against a follower's {@code Class} would never match.
     *
     * @param serviceType interface or class to filter by
     * @param <T>         the requested service type
     * @return an immutable snapshot, empty if nothing matches
     */
    @BridgeMethod
    @NotNull <T extends PandasticService> List<T> getServicesByType(@NotNull Class<T> serviceType);

    /**
     * Looks a service up by its {@link PandasticService#getServiceName() stable identifier}.
     * <p>
     * <b>Across a classloader boundary this returns an {@link PandasticService}-only view.</b> The
     * bridge cannot know which concrete interface the caller wants, so the proxy it hands back
     * implements the caller's {@code PandasticService} plus whatever other interfaces are proxyable
     * (library interfaces, and interfaces under a
     * {@linkplain PandasticCore#registerBridgePackage(org.bukkit.plugin.Plugin, String) registered
     * bridge package}); a cast to a consumer-defined interface whose package was never
     * registered fails with a {@link ClassCastException}. Use
     * {@link #getService(String, Class)} to get your own interface back explicitly.
     *
     * @param name the service identifier
     * @return the service, or {@code null} if no service is registered under that name
     */
    @BridgeMethod
    @Nullable PandasticService getService(@NotNull String name);

    /**
     * Looks a service up by its {@link PandasticService#getServiceName() stable identifier} and returns
     * it as the caller's own {@code as} interface.
     * <p>
     * This is the lookup to use for consumer-defined service interfaces: unlike
     * {@link #getService(String)}, which can only promise an {@link PandasticService} view across a
     * classloader boundary, this overload carries the requested type through the bridge — the
     * type token is {@linkplain #getServicesByType(Class) translated into the leader's
     * classloader} on the way in, and the result is viewed through the caller's own copy of
     * {@code as} on the way out.
     * <h4>When {@code PandasticCore.registerBridgePackage} is needed</h4>
     * A library interface needs nothing extra. A consumer-defined interface is honoured only when
     * it is proxyable, which means its package must be
     * {@linkplain PandasticCore#registerBridgePackage(org.bukkit.plugin.Plugin, String) registered}
     * <em>before the service crosses the bridge</em>: a service registered by a follower is
     * stored leader-side as a proxy whose interface set is fixed at registration time, so a
     * prefix registered afterwards cannot retrofit it. When caller, service and leader all share
     * one classloader the instance is returned directly and no registration is involved.
     * <h4>Failure mode</h4>
     * A name hit whose service does not implement {@code as} <em>in the leader's view</em> is a
     * caller bug, not an absence, and throws a {@link ClassCastException} naming both types
     * rather than returning {@code null} — {@code null} would read as "not registered" and send
     * the caller down a pointless re-registration path. An unregistered cross-classloader
     * consumer interface fails the same way, because the leader cannot see the caller's type.
     *
     * @param name the service identifier
     * @param as   the interface to view the service through, from the caller's own classloader
     * @param <T>  the requested service type
     * @return the service viewed as {@code as}, or {@code null} if no service is registered
     *         under that name
     * @throws ClassCastException if a service is registered under {@code name} but is not an
     *                            {@code as} in the leader's view
     * @since protocol 3; a leader built from an older copy of the library cannot serve this
     *        call — but protocol mismatch already degrades to isolated mode at election, so a
     *        caller of this method is always talking to a protocol-3 leader
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 3)
    @Nullable <T extends PandasticService> T getService(@NotNull String name, @NotNull Class<T> as);

    /**
     * Every plugin-bound service registered for {@code plugin}.
     *
     * @param plugin the plugin whose scoped services are requested
     * @param <P>    the plugin type
     * @return an immutable snapshot, empty if the plugin has no scoped services
     */
    @BridgeMethod
    @NotNull <P extends Plugin> List<PluginBoundPandasticService<P>> getPluginBoundServices(@NotNull P plugin);

    /**
     * Registers a service under its {@link PandasticService#getServiceName() identifier}.
     * <p>
     * Registration is idempotent for the same instance and rejected for a different instance
     * under an already-taken name: silently replacing a shared service would strand every plugin
     * that already holds the previous one.
     *
     * @param service the service to register
     * @param <T>     the service type
     * @throws IllegalStateException    if a different service is already registered under that name
     * @throws IllegalArgumentException if the service reports a blank identifier
     */
    @BridgeMethod
    <T extends PandasticService> void registerService(@NotNull T service);

    /**
     * Removes the registration that {@code service} refers to, freeing its name for re-use.
     * <p>
     * This is the counterpart to {@link #registerService(PandasticService)} and the reason it exists is a
     * classloader leak: a follower-registered service is held by the leader's registry as a strong
     * reference — across the bridge, as a leader-side proxy wrapping the follower's object — which
     * pins the follower's entire plugin classloader until the leader itself dies. A plugin that
     * registers services must unregister them when it shuts down; the library's own follower
     * shutdown does this wholesale via {@link #unregisterAll(Plugin)}.
     * <h4>How the registration is matched</h4>
     * First by reference identity, which covers the leader's own registrations. When the caller is
     * a follower, identity cannot work: the registry holds a leader-side proxy over the follower's
     * object, and each crossing of the bridge mints a fresh proxy, so no two crossings are ever the
     * same reference. The match therefore falls back to
     * {@link PandasticService#getServiceName() service-name} equality — the registry's unique key, and a
     * plain {@link String}, which is the only identity that survives the classloader boundary
     * unchanged. A proxy delegates {@code getServiceName()} to the object it wraps, so the name of
     * the caller's instance and the name of the registered proxy agree by construction.
     *
     * @param service the service whose registration should be removed; the instance originally
     *                registered, or any object reporting the same service name
     * @return {@code true} if a registration was removed, {@code false} if nothing was registered
     *         under that identity (including a second call for the same service)
     * @since protocol 2; a leader built from an older copy of the library cannot serve this call,
     *        and the bridge reports that as an {@link IllegalStateException} rather than guessing
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 2)
    boolean unregisterService(@NotNull PandasticService service);

    /**
     * Removes every {@link PluginBoundPandasticService} bound to {@code plugin}, freeing their names.
     * <p>
     * This is the lifecycle half of unregistration: the follower-side {@code shutdown()} calls it
     * before detaching, so a plugin that is disabled or reloaded takes its registrations — and with
     * them the only strong references pinning its classloader inside the leader — out of the
     * registry. Services that are not plugin-bound are never touched, because nothing ties them to
     * one plugin's lifetime.
     * <h4>Bound-ness is decided by plugin <em>name</em></h4>
     * A service belongs to {@code plugin} when its {@link PluginBoundPandasticService#getBoundPlugin()}
     * reports the same {@link Plugin#getName() plugin name}. Name equality is deliberate: a
     * reloaded plugin is a <em>new</em> {@code Plugin} instance in a new classloader, so instance
     * equality would never match exactly when unregistration matters most — cleaning up what a
     * previous incarnation of the same plugin left behind. Plugin names are unique per server, and
     * they are the only plugin identity that survives both the classloader boundary and a reload.
     * <p>
     * The leader does not need this for its own teardown: leader shutdown clears the whole registry
     * at once. This call is for the opposite order, a follower dying while the leader lives on.
     *
     * @param plugin the plugin whose bound services should be removed
     * @return the number of registrations removed, {@code 0} if the plugin had none
     * @since protocol 2; a leader built from an older copy of the library cannot serve this call,
     *        and the bridge reports that as an {@link IllegalStateException} rather than guessing
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 2)
    int unregisterAll(@NotNull Plugin plugin);

    /**
     * Whether {@code plugin} already has a service of the given type registered.
     *
     * @param plugin      the plugin to check
     * @param serviceType the plugin-bound service type to look for
     * @param <P>         the plugin type
     * @param <T>         the plugin-bound service type
     * @return {@code true} if such a service is registered for that plugin
     */
    @BridgeMethod
    <P extends Plugin, T extends PluginBoundPandasticService<?>> boolean hasRegisteredService(@NotNull P plugin,
                                                                                        @NotNull Class<T> serviceType);

    /**
     * Resolves the plugin-bound service of the given type registered for {@code plugin}.
     *
     * @param plugin      the plugin whose scoped instance is requested
     * @param serviceType the plugin-bound service type to look for
     * @param <T>         the plugin-bound service type
     * @return the service, or {@code null} if that plugin has no such service
     */
    @BridgeMethod
    @Nullable <T extends PluginBoundPandasticService<?>> T getServiceForPlugin(@NotNull Plugin plugin,
                                                                         @NotNull Class<T> serviceType);
}
