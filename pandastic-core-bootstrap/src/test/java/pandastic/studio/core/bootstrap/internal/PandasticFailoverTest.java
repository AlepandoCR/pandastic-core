package pandastic.studio.core.bootstrap.internal;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;
import pandastic.studio.core.service.api.PluginBoundPandasticService;
import pandastic.studio.core.service.api.PandasticCore;
import pandastic.studio.core.service.api.PandasticCoreProvider;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;
import pandastic.studio.core.service.profile.PandasticProfileService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leader failover on plugin unload, driven against a private anchor map.
 *
 * <p>These tests exercise {@link PandasticBootstrap#handleDisabledParticipant}, the lock-free core the
 * {@link PandasticLifecycleGuard} listener dispatches to when any plugin is disabled. The scenarios are
 * the plugman ones: the leader is unloaded while followers survive, a follower is unloaded without
 * detaching, and unrelated plugins come and go.
 */
class PandasticFailoverTest {

    private Map<String, Object> anchor;

    @BeforeEach
    void freshState() {
        this.anchor = new ConcurrentHashMap<>();
        PandasticBootstrap.resetLocalLeaderForTesting();
    }

    @AfterEach
    void clearMemo() {
        PandasticBootstrap.resetLocalLeaderForTesting();
    }

    @Test
    @DisplayName("leader unload promotes the earliest-joined surviving follower")
    void leaderUnloadPromotesEarliestFollower() {
        Plugin leaderPlugin = stubPlugin("Leader");
        Plugin alpha = stubPlugin("Alpha");
        Plugin beta = stubPlugin("Beta");

        publishFakeLeader(leaderPlugin);
        PandasticApiRegistry.registerFollower(this.anchor, alpha);
        PandasticApiRegistry.registerFollower(this.anchor, beta);
        PandasticBridgePackages.register(this.anchor, "Leader", "com.example.leaderplugin");
        PandasticBridgePackages.register(this.anchor, "Beta", "com.example.betaplugin");

        RecordingProvider provider = new RecordingProvider();
        PandasticBootstrap.handleDisabledParticipant(this.anchor, alpha, provider, leaderPlugin);

        assertEquals(1, provider.creations.get(), "the designated successor must elect exactly once");
        assertSame(provider.lastCreated, this.anchor.get(PandasticBootstrap.KEY_LEADER),
                "the promoted instance must be published as the new leader");
        assertEquals("Alpha", this.anchor.get(PandasticBootstrap.KEY_LEADER_PLUGIN),
                "the earliest-joined follower is the designated successor");
        assertFalse(followerNames().contains("Alpha"), "the promoted copy is no longer a follower");
        assertTrue(followerNames().contains("Beta"), "other followers keep their registration");
        assertNull(registeredPrefixesOf("Leader"),
                "the dead leader plugin's trusted bridge packages must die with it");
        assertNotNull(registeredPrefixesOf("Beta"),
                "survivors' trusted bridge packages must outlive the failover");
    }

    @Test
    @DisplayName("a later-joined follower does not promote itself")
    void nonDesignatedFollowerDoesNotPromote() {
        Plugin leaderPlugin = stubPlugin("Leader");
        Plugin alpha = stubPlugin("Alpha");
        Plugin beta = stubPlugin("Beta");

        publishFakeLeader(leaderPlugin);
        PandasticApiRegistry.registerFollower(this.anchor, alpha);
        PandasticApiRegistry.registerFollower(this.anchor, beta);

        RecordingProvider provider = new RecordingProvider();
        PandasticBootstrap.handleDisabledParticipant(this.anchor, beta, provider, leaderPlugin);

        assertEquals(0, provider.creations.get(), "a non-designated follower must not elect");
        assertNull(this.anchor.get(PandasticBootstrap.KEY_LEADER),
                "the dead leader's slot is cleared even by the copy that does not promote");
        assertTrue(followerNames().contains("Beta"), "the non-designated copy stays a follower");
    }

    @Test
    @DisplayName("a listener firing after the promotion is a no-op")
    void secondListenerAfterPromotionIsNoOp() {
        Plugin leaderPlugin = stubPlugin("Leader");
        Plugin alpha = stubPlugin("Alpha");
        Plugin beta = stubPlugin("Beta");

        publishFakeLeader(leaderPlugin);
        PandasticApiRegistry.registerFollower(this.anchor, alpha);
        PandasticApiRegistry.registerFollower(this.anchor, beta);

        RecordingProvider alphaProvider = new RecordingProvider();
        PandasticBootstrap.handleDisabledParticipant(this.anchor, alpha, alphaProvider, leaderPlugin);
        Object promoted = this.anchor.get(PandasticBootstrap.KEY_LEADER);
        assertNotNull(promoted);

        RecordingProvider betaProvider = new RecordingProvider();
        PandasticBootstrap.handleDisabledParticipant(this.anchor, beta, betaProvider, leaderPlugin);

        assertEquals(0, betaProvider.creations.get(), "the healthy promoted leader must be left alone");
        assertSame(promoted, this.anchor.get(PandasticBootstrap.KEY_LEADER), "leadership must not change hands twice");
    }

    @Test
    @DisplayName("an unrelated plugin disable with a healthy leader changes nothing")
    void unrelatedDisableWithHealthyLeaderDoesNothing() {
        Plugin leaderPlugin = stubPlugin("Leader");
        Plugin alpha = stubPlugin("Alpha");
        Plugin bystander = stubPlugin("Bystander");

        publishFakeLeader(leaderPlugin);
        Object originalLeader = this.anchor.get(PandasticBootstrap.KEY_LEADER);
        PandasticApiRegistry.registerFollower(this.anchor, alpha);

        RecordingProvider provider = new RecordingProvider();
        PandasticBootstrap.handleDisabledParticipant(this.anchor, alpha, provider, bystander);

        assertEquals(0, provider.creations.get());
        assertSame(originalLeader, this.anchor.get(PandasticBootstrap.KEY_LEADER));
        assertTrue(followerNames().contains("Alpha"));
    }

    @Test
    @DisplayName("leader unload with no surviving followers just clears the state")
    void leaderUnloadWithNoFollowersJustClears() {
        Plugin leaderPlugin = stubPlugin("Leader");
        Plugin isolatedSelf = stubPlugin("Isolated");

        publishFakeLeader(leaderPlugin);

        RecordingProvider provider = new RecordingProvider();
        PandasticBootstrap.handleDisabledParticipant(this.anchor, isolatedSelf, provider, leaderPlugin);

        assertEquals(0, provider.creations.get(), "an unregistered copy is never the successor");
        assertNull(this.anchor.get(PandasticBootstrap.KEY_LEADER));
        assertNull(this.anchor.get(PandasticBootstrap.KEY_LEADER_PLUGIN));
    }

    @Test
    @DisplayName("the leader copy prunes a follower that was unloaded without detaching")
    void deadFollowerIsPrunedByLeaderCopy() {
        Plugin leaderPlugin = stubPlugin("Leader");
        Plugin alpha = stubPlugin("Alpha");
        Plugin beta = stubPlugin("Beta");

        publishFakeLeader(leaderPlugin);
        PandasticApiRegistry.registerFollower(this.anchor, alpha);
        PandasticApiRegistry.registerFollower(this.anchor, beta);
        PandasticBridgePackages.register(this.anchor, "Beta", "com.example.betaplugin");

        // Alpha promotes itself first, becoming the copy whose localLeader is published.
        RecordingProvider provider = new RecordingProvider();
        PandasticBootstrap.handleDisabledParticipant(this.anchor, alpha, provider, leaderPlugin);
        RecordingServiceManager registry = provider.lastCreated.serviceManager;

        // Beta is now unloaded without ever calling shutdown().
        PandasticBootstrap.handleDisabledParticipant(this.anchor, alpha, provider, beta);

        assertFalse(followerNames().contains("Beta"), "the dead follower must leave the registry");
        assertEquals(1, registry.unregisterAllCalls.get(), "its services must be unregistered on the leader");
        assertEquals("Beta", registry.lastUnregisteredPlugin, "for the right plugin");
        assertEquals(1, provider.creations.get(), "pruning must not re-elect anything");
        assertNull(registeredPrefixesOf("Beta"),
                "the dead follower's trusted bridge packages must be pruned with its services");
    }

    @Test
    @DisplayName("successor designation is join-order first, name tie-break, exclusion honoured")
    void successorDesignationRules() {
        Plugin first = stubPlugin("Zeta");
        Plugin second = stubPlugin("Alpha");
        PandasticApiRegistry.registerFollower(this.anchor, first);
        PandasticApiRegistry.registerFollower(this.anchor, second);

        assertEquals("Zeta", PandasticApiRegistry.designatedSuccessor(this.anchor, "Leader"),
                "join order beats name order");
        assertEquals("Alpha", PandasticApiRegistry.designatedSuccessor(this.anchor, "Zeta"),
                "the excluded plugin is never designated");

        PandasticApiRegistry.unregisterFollower(this.anchor, first);
        PandasticApiRegistry.unregisterFollower(this.anchor, second);
        assertNull(PandasticApiRegistry.designatedSuccessor(this.anchor, "Leader"),
                "no followers, no successor");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void publishFakeLeader(Plugin owner) {
        FailoverFakeCore previous = new FailoverFakeCore();
        this.anchor.put(PandasticBootstrap.KEY_LEADER, previous);
        this.anchor.put(PandasticBootstrap.KEY_PROTOCOL, GeneratedBridgeContract.PROTOCOL_VERSION);
        this.anchor.put(PandasticBootstrap.KEY_LEADER_PLUGIN, owner.getName());
        this.anchor.put(PandasticBootstrap.KEY_LEADER_PLUGIN_REF, new java.lang.ref.WeakReference<>(owner));
        this.anchor.put(PandasticBootstrap.KEY_LEADER_CLASS, previous.getClass().getName());
    }

    private List<String> followerNames() {
        return PandasticApiRegistry.followers(this.anchor).stream().map(Plugin::getName).toList();
    }

    /**
     * Reads a plugin's entry straight out of the private anchor's bridge-packages slot, so the
     * assertion observes the slot itself rather than anything a helper might repair on the way.
     */
    private @Nullable Object registeredPrefixesOf(String pluginName) {
        Object slot = this.anchor.get(PandasticBridgePackages.BRIDGE_PACKAGES_KEY);
        if (!(slot instanceof Map<?, ?> byPlugin)) return null;
        return byPlugin.get(pluginName);
    }

    private static Plugin stubPlugin(String name) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> true;
            case "toString" -> "StubPlugin(" + name + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    /**
     * Records elections. Promotion must create at most one leader, and only in the designated copy.
     */
    private static final class RecordingProvider implements PandasticCoreProvider {

        final AtomicInteger creations = new AtomicInteger();
        FailoverFakeCore lastCreated;

        @Override
        public @NotNull PandasticCore createLeader(@NotNull Plugin plugin) {
            this.creations.incrementAndGet();
            this.lastCreated = new FailoverFakeCore();
            return this.lastCreated;
        }
    }

    /**
     * A real (non-proxy) {@link PandasticCore} under the library's package prefix, so that
     * {@link PandasticLeaderValidator} accepts it exactly as it would a production leader.
     */
    public static final class FailoverFakeCore implements PandasticCore {

        final RecordingServiceManager serviceManager = new RecordingServiceManager();

        @Override
        public @NotNull Collection<PandasticService> getServices() {
            return List.of();
        }

        @Override
        public @NotNull PandasticServiceManager getServiceManager() {
            return this.serviceManager;
        }

        @Override
        public <P extends Plugin> @NotNull PandasticProfileService<P> getJavaProfileService(@NotNull P plugin) {
            throw new UnsupportedOperationException("not part of failover");
        }

        @Override
        public <P extends Plugin> @NotNull PandasticProfileService<P> getBedrockProfileService(@NotNull P plugin) {
            throw new UnsupportedOperationException("not part of failover");
        }

        @Override
        public <P extends Plugin> @NotNull PandasticProfileService<P> getUniversalProfileService(@NotNull P plugin) {
            throw new UnsupportedOperationException("not part of failover");
        }

        @Override
        public @NotNull pandastic.studio.core.service.economy.PandasticEconomyService getEconomyService() {
            throw new UnsupportedOperationException("not part of failover");
        }

        @Override
        public void registerBridgePackage(@NotNull Plugin owner, @NotNull String packagePrefix) {
            throw new UnsupportedOperationException("not part of failover");
        }

        @Override
        public void shutdown() {
        }

        @Override
        public @NotNull String getServiceName() {
            return "FailoverFakeCore";
        }

        @Override
        public @NotNull Component getName() {
            return Component.text("FailoverFakeCore");
        }

        @Override
        public @NotNull Component getDescription() {
            return Component.text("Leader stand-in for failover tests.");
        }
    }

    /**
     * Minimal registry that records unregistration sweeps.
     */
    public static final class RecordingServiceManager implements PandasticServiceManager {

        final AtomicInteger unregisterAllCalls = new AtomicInteger();
        @Nullable String lastUnregisteredPlugin;
        private final List<PandasticService> services = new ArrayList<>();

        @Override
        public @NotNull List<PandasticService> getAllServices() {
            return List.copyOf(this.services);
        }

        @Override
        public <T extends PandasticService> @NotNull List<T> getServicesByType(@NotNull Class<T> serviceType) {
            return List.of();
        }

        @Override
        public @Nullable PandasticService getService(@NotNull String name) {
            return null;
        }

        @Override
        public <P extends Plugin> @NotNull List<PluginBoundPandasticService<P>> getPluginBoundServices(@NotNull P plugin) {
            return List.of();
        }

        @Override
        public <T extends PandasticService> void registerService(@NotNull T service) {
            this.services.add(service);
        }

        @Override
        public <P extends Plugin, T extends PluginBoundPandasticService<?>> boolean hasRegisteredService(
                @NotNull P plugin, @NotNull Class<T> serviceType) {
            return false;
        }

        @Override
        public <T extends PluginBoundPandasticService<?>> @Nullable T getServiceForPlugin(
                @NotNull Plugin plugin, @NotNull Class<T> serviceClass) {
            return null;
        }

        @Override
        public boolean unregisterService(@NotNull PandasticService service) {
            return this.services.remove(service);
        }

        @Override
        public int unregisterAll(@NotNull Plugin plugin) {
            this.unregisterAllCalls.incrementAndGet();
            this.lastUnregisteredPlugin = plugin.getName();
            return 0;
        }

        @Override
        public @Nullable <T extends PandasticService> T getService(@NotNull String name, @NotNull Class<T> as) {
            return null;
        }
    }
}
