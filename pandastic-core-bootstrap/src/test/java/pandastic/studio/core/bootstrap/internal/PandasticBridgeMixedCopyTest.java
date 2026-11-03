package pandastic.studio.core.bootstrap.internal;

import com.example.pandasticmarket.MarketCache;
import com.example.pandasticmarket.MarketCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgeWorkingServiceManagerFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;
import pandastic.studio.core.service.api.cache.PandasticCache;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The <b>mixed-copy</b> topology: the leader's classloader can resolve the consumer's interface —
 * by delegation here, by Paper's cross-plugin class lookup on a server — but that interface's
 * method signatures reference the <em>consumer's</em> shaded library copy, not the leader's.
 * <p>
 * This is the exact shape of a production incident: with pandastic-teleport elected leader, pandastic-market's
 * {@code registerService} died with {@code IllegalArgumentException:
 * pandastic.studio.core.service.api.PandasticService referenced from a method is not visible from class loader
 * 'pandastic-teleport'} out of {@code Proxy$ProxyBuilder.ensureVisible}, because the leader-side proxy
 * tried to implement market's interface while defined in teleport's loader — where every
 * {@code pandastic.studio.core} name resolves to teleport's own copy. {@code PandasticBridgeConsumerPackageTest}
 * could not see this: its foreign side owns a <em>self-consistent</em> doubled consumer API
 * (secondary child-first prefix), so each side's interfaces only ever reference that side's
 * library copy.
 * <p>
 * Here the leader loader is child-first for {@code pandastic.studio.core} <b>only</b>. The consumer
 * fixtures ({@link MarketCache}, {@link MarketCacheService}) resolve from the leader's loader to
 * the one test-classpath copy, whose superinterfaces and method signatures are the test
 * classpath's library copy — unlinkable from the leader's side. The pinned behaviour: the bridge
 * <em>prunes</em> such interfaces from the leader-side proxy instead of letting the JDK reject the
 * registration, and the typed lookup compensates by unwrapping to the original object and serving
 * it through the caller's own copy, where everything links.
 */
class PandasticBridgeMixedCopyTest {

    private static final String CONSUMER_PACKAGE = "com.example.pandasticmarket";
    private static final String OWNER_PLUGIN = "PandasticMarket";

    private ClassLoader teleportLoader;
    private Object foreignManager;
    private PandasticServiceManager bridge;
    private MarketCacheService service;

    @BeforeEach
    void bridgeOntoALeaderThatSeesTheConsumerApiThroughOurLoader() throws Exception {
        // No extra child-first prefixes: pandastic.studio.core is doubled, com.example.pandasticmarket is NOT —
        // the leader resolves it to the test classpath's single copy, Paper-cross-plugin style.
        teleportLoader = new IsolatedLibraryClassLoader("teleport", getClass().getClassLoader());

        assertSame(MarketCache.class, teleportLoader.loadClass(MarketCache.class.getName()),
                "precondition: the consumer interface is ONE class visible from both loaders");
        assertNotSameLibraryCopies();

        Class<?> managerType = teleportLoader.loadClass(BridgeWorkingServiceManagerFixture.class.getName());
        foreignManager = managerType.getConstructor().newInstance();
        bridge = new PandasticBridgeServiceManager(foreignManager);
        service = new MarketCacheService();

        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), OWNER_PLUGIN, CONSUMER_PACKAGE);
    }

    private void assertNotSameLibraryCopies() throws ClassNotFoundException {
        assertTrue(PandasticService.class != teleportLoader.loadClass(PandasticService.class.getName()),
                "precondition: the library really is doubled between the two sides");
    }

    @AfterEach
    void dropTrustedPrefixes() {
        PandasticJvmAnchor.anchorMap().remove(PandasticBridgePackages.BRIDGE_PACKAGES_KEY);
    }

    @Test
    @DisplayName("registering a service whose trusted interface references the caller's library copy must not throw")
    void registrationSurvivesAnUnlinkableTrustedInterface() {
        // Pre-fix this escaped as the JDK ProxyBuilder's IllegalArgumentException and the service
        // was never registered; the consumer interface must be pruned instead.
        assertDoesNotThrow(() -> bridge.registerService(service));

        assertNotNull(bridge.getService(MarketCacheService.SERVICE_NAME),
                "the registration really happened");
    }

    @Test
    @DisplayName("the leader-side proxy carries the leader's library interfaces but prunes the unlinkable consumer one")
    void leaderSideProxyIsPrunedButUsable() throws Exception {
        bridge.registerService(service);

        Object stored = foreignManager.getClass()
                .getMethod("getService", String.class)
                .invoke(foreignManager, MarketCacheService.SERVICE_NAME);
        assertNotNull(stored);
        assertTrue(Proxy.isProxyClass(stored.getClass()), "a foreign object arrives as a proxy");

        Set<String> interfaceNames = Arrays.stream(stored.getClass().getInterfaces())
                .map(Class::getName)
                .collect(Collectors.toSet());
        assertTrue(interfaceNames.contains(PandasticService.class.getName()),
                "the leader's own library interfaces are implemented");
        assertTrue(interfaceNames.contains(PandasticCache.class.getName()),
                "including the cache hierarchy, which links cleanly on the leader's side");
        assertFalse(interfaceNames.contains(MarketCache.class.getName()),
                "the consumer interface references the caller's library copy, so a proxy defined"
                        + " in the leader's loader cannot link it and it must be pruned");

        // The pruned proxy still delegates: leader-side code can call the library surface.
        Class<?> leaderPandasticService = teleportLoader.loadClass(PandasticService.class.getName());
        Object reportedName = leaderPandasticService.getMethod("getServiceName").invoke(stored);
        assertEquals(MarketCacheService.SERVICE_NAME, reportedName);
    }

    @Test
    @DisplayName("the typed lookup survives the pruning: unwrap to the original, serve it through the caller's copy")
    void typedLookupStillHonoursTheConsumerInterface() {
        bridge.registerService(service);

        MarketCache typed = bridge.getService(MarketCacheService.SERVICE_NAME, MarketCache.class);

        // The caller IS the owning copy, so the round-trip must collapse to the original object —
        // the leader-side proxy is unwrapped, not re-wrapped.
        assertSame(service, typed,
                "owner's typed lookup returns the registered instance itself, not a proxy stack");
    }

    @Test
    @DisplayName("the untyped lookup also round-trips to the original object for the owning copy")
    void untypedLookupRoundTripsToTheOriginal() {
        bridge.registerService(service);

        PandasticService view = bridge.getService(MarketCacheService.SERVICE_NAME);

        assertSame(service, view, "unwrap-before-proxy keeps follower → leader → follower lossless");
    }
}
