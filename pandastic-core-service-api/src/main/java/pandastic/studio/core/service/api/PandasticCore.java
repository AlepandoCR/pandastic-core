package pandastic.studio.core.service.api;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.bootstrap.api.contract.BridgeApi;
import pandastic.studio.core.bootstrap.api.contract.BridgeMethod;
import pandastic.studio.core.bootstrap.api.contract.BridgeNamespace;
import pandastic.studio.core.bootstrap.api.contract.BridgeStability;
import pandastic.studio.core.service.economy.PandasticEconomyService;
import pandastic.studio.core.service.profile.PandasticProfileService;

import java.util.Collection;

/**
 * Entry point to the shared runtime, and the root of the bridged surface.
 * <p>
 * Call {@link #instance(JavaPlugin)} from your plugin's {@code onEnable}. The first plugin in the
 * JVM to do so is elected <b>leader</b> and receives the real implementation, which owns the
 * shared thread pool, HTTP client, caches and service registry. Every later plugin receives a
 * <b>follower</b> bridge: a local object of this same interface that forwards each call
 * reflectively to the leader's instance. Both kinds behave identically from the caller's side,
 * which is the whole point — plugins share one runtime despite each shading its own copy of these
 * classes.
 * <h2>Shutdown</h2>
 * Call {@link #shutdown()} from {@code onDisable}, always, from every plugin. A follower detaches
 * itself; the leader releases its election and tears the shared runtime down only once the last
 * follower is gone. Skipping it leaks the shared runtime and keeps a disabled plugin's
 * classloader alive.
 *
 * @see PandasticServiceManager for the service registry this exposes
 */
@BridgeApi(BridgeNamespace.PANDASTIC_LIB_API)
public interface PandasticCore extends PandasticService {

    /**
     * Joins the shared runtime, with logging enabled.
     * <p>
     * Equivalent to {@code instance(plugin, true)}. The leader/follower decision is made once per
     * JVM and is invisible to the caller.
     *
     * @param plugin the calling plugin
     * @return the leader implementation, or a follower bridge to it
     */
    static @NotNull PandasticCore instance(@NotNull JavaPlugin plugin) {
        return instance(plugin, true);
    }

    /**
     * Joins the shared runtime.
     *
     * @param plugin  the calling plugin
     * @param logging whether to report the election outcome to {@code plugin}'s logger; disable it
     *                only if you have your own diagnostics, as these lines are the simplest way to
     *                confirm two plugins really ended up sharing one runtime
     * @return the leader implementation, or a follower bridge to it
     */
    static @NotNull PandasticCore instance(@NotNull JavaPlugin plugin, boolean logging) {
        return PandasticCoreEntrypoints.entrypoint().join(plugin, logging);
    }

    /**
     * Every service currently registered in the shared runtime.
     * <p>
     * A convenience view over {@link PandasticServiceManager#getAllServices()}; prefer the manager when
     * you need typed or plugin-scoped lookups.
     *
     * @return an immutable snapshot
     */
    @BridgeMethod
    @NotNull Collection<PandasticService> getServices();

    /**
     * The shared service registry.
     * <p>
     * On the leader this is the real registry; on a follower it is a bridge over it. Either way
     * every plugin sees the same set of services.
     */
    @BridgeMethod
    @NotNull PandasticServiceManager getServiceManager();

    /**
     * The Java-edition profile service bound to {@code plugin}.
     * <p>
     * Resolves names, UUIDs and skins of Java-edition (Mojang) accounts. Instances are created
     * lazily, one per requesting plugin, but every instance shares the leader runtime's single
     * profile cache, HTTP client and thread pool, so two plugins asking for the same player hit
     * one cache entry and one remote lookup.
     *
     * @param plugin the plugin the returned service acts on behalf of
     * @param <P>    the concrete plugin type
     * @return the plugin's Java profile service, created on first request
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 2)
    <P extends Plugin> @NotNull PandasticProfileService<P> getJavaProfileService(@NotNull P plugin);

    /**
     * The Bedrock-edition profile service bound to {@code plugin}.
     * <p>
     * Resolves gamertags, Floodgate-format UUIDs (most significant bits zero, the XUID in the
     * least significant bits) and skins of Bedrock accounts, using Floodgate when that plugin is
     * installed and the Geyser Global API otherwise. Instances are created lazily, one per
     * requesting plugin, but every instance shares the leader runtime's single profile cache,
     * HTTP client and thread pool.
     *
     * @param plugin the plugin the returned service acts on behalf of
     * @param <P>    the concrete plugin type
     * @return the plugin's Bedrock profile service, created on first request
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 2)
    <P extends Plugin> @NotNull PandasticProfileService<P> getBedrockProfileService(@NotNull P plugin);

    /**
     * The edition-agnostic profile service bound to {@code plugin}.
     * <p>
     * Composes the {@linkplain #getBedrockProfileService(Plugin) Bedrock} and
     * {@linkplain #getJavaProfileService(Plugin) Java} services over the same shared cache: every
     * lookup peeks the cache first and, on a miss, consults <b>Bedrock first, then Java</b>. A
     * name that carries the Bedrock prefix is resolved through Bedrock only. Use this service
     * when the caller does not know (or care) which edition a player belongs to.
     *
     * @param plugin the plugin the returned service acts on behalf of
     * @param <P>    the concrete plugin type
     * @return the plugin's universal profile service, created on first request
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 2)
    <P extends Plugin> @NotNull PandasticProfileService<P> getUniversalProfileService(@NotNull P plugin);

    /**
     * The server-wide economy service, backed by Vault's registered provider.
     * <p>
     * Shared, not plugin-bound: money is global state, and one leader-owned instance is what makes
     * {@code charge} and {@code transfer} atomic across plugins. See {@link PandasticEconomyService} for
     * the threading and failure model.
     *
     * @return the shared economy service
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 2)
    @NotNull PandasticEconomyService getEconomyService();

    /**
     * Declares that interfaces under {@code packagePrefix} may cross the classloader bridge as
     * proxies, exactly like the library's own interfaces.
     * <p>
     * By default the bridge only proxies {@code pandastic.studio.core.*} interfaces, so a service that
     * implements a consumer-defined interface (a plugin's own
     * {@code pandastic.studio.market.api.MarketAnalyticsService}, say) loses that interface whenever it
     * crosses a classloader boundary. Registering the package lifts that limit for
     * <b>interfaces only</b>: a trusted prefix never makes a concrete class crossable, never
     * widens the {@code Dto} value rules, and never changes what may appear in bridged
     * signatures.
     * <h4>Register before the service crosses</h4>
     * A service registered by a follower is stored leader-side as a proxy whose interface set is
     * fixed the moment it crosses. Register the prefix first — typically right here in
     * {@code onEnable}, before {@code registerService} — or the stored proxy will not carry the
     * consumer interface and typed lookups against it will fail.
     * <h4>Validation and scope</h4>
     * The prefix must be a well-formed, dot-separated package name of at least two segments, and
     * may not overlap the library's own namespace ({@code pandastic.studio.core} or any prefix of it) or
     * platform namespaces ({@code java}, {@code javax}, {@code jdk}, {@code sun},
     * {@code com.sun}, {@code org.bukkit}, {@code io.papermc}, {@code net.kyori}) — a
     * wildcard-ish prefix would defeat the bridge's type contract entirely. Violations throw
     * {@link IllegalArgumentException}.
     * <h4>Lifecycle and telemetry</h4>
     * Registrations are tied to {@code owner}: they are dropped when the owning plugin's
     * services are swept ({@link PandasticServiceManager#unregisterAll(Plugin)}), when the plugin is
     * disabled without detaching, and on shutdown — an unloaded plugin's prefix must not keep
     * its interfaces proxyable. Every registration widens a security boundary and is therefore
     * logged at INFO, naming the owning plugin and the prefix; that line is operator telemetry
     * and must not be silenced.
     *
     * @param owner         the plugin on whose behalf the prefix is trusted; its lifetime bounds
     *                      the registration's
     * @param packagePrefix the package prefix to trust, e.g. {@code "pandastic.studio.market.api"}
     * @throws IllegalArgumentException if the prefix is malformed or overlaps a reserved
     *                                  namespace
     * @since protocol 3; a leader built from an older copy of the library cannot serve this
     *        call — but protocol mismatch already degrades to isolated mode at election, so a
     *        caller of this method is always talking to a protocol-3 leader
     */
    @BridgeMethod(stability = BridgeStability.DERIVED_ID, sinceProtocol = 3)
    void registerBridgePackage(@NotNull Plugin owner, @NotNull String packagePrefix);

    /**
     * Detaches the calling plugin from the shared runtime.
     * <p>
     * On a follower this only unregisters the follower and drops its local caches; the shared
     * runtime keeps running for the other plugins. On the leader it releases the election and, if
     * no followers remain, shuts the shared runtime down. Idempotent.
     */
    @BridgeMethod
    void shutdown();
}
