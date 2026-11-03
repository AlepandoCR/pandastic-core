package pandastic.studio.core.bootstrap.internal;

import com.example.pandasticmarket.MarketCache;
import com.example.pandasticmarket.MarketCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.proxy.PandasticServiceProxyFactory;
import pandastic.studio.core.bootstrap.internal.testing.BridgeWorkingServiceManagerFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;
import pandastic.studio.core.service.api.cache.PandasticCache;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer-interface seam of the bridge, end to end across a real classloader boundary:
 * typed {@code getService(String, Class)}, {@code PandasticCore.registerBridgePackage} trust, and the
 * consumer-cache live-map guarantee.
 * <p>
 * The foreign side ("PandasticMarket", the leader here) is an {@link IsolatedLibraryClassLoader} with
 * {@code com.example.pandasticmarket.} as a <b>secondary child-first prefix</b>, so it owns its own
 * copy of {@link MarketCache} whose superinterfaces are its own library copy — a consumer API
 * shaded next to its library, which is the shape where name-based trust is the only identity
 * left. The test classpath plays the calling plugin and holds the other {@link MarketCache}.
 * <p>
 * The trusted prefixes live in the real {@link System}-properties anchor
 * ({@link PandasticBridgePackages}), because both copies' bridge code must observe one registration;
 * each test cleans the slot up so the JVM-global state never leaks between tests.
 */
class PandasticBridgeConsumerPackageTest {

    private static final String CONSUMER_PACKAGE = "com.example.pandasticmarket";
    private static final String OWNER_PLUGIN = "PandasticMarket";

    private ClassLoader marketLoader;
    private Object foreignManager;
    private Object foreignService;
    private PandasticServiceManager bridge;

    @BeforeEach
    void bridgeOntoAForeignLeaderThatOwnsItsConsumerApi() throws Exception {
        marketLoader = new IsolatedLibraryClassLoader("market", getClass().getClassLoader(),
                CONSUMER_PACKAGE + ".");

        Class<?> foreignInterface = marketLoader.loadClass(MarketCache.class.getName());
        assertNotSame(MarketCache.class, foreignInterface,
                "the consumer interface must really be doubled, like a shaded consumer API");

        Class<?> managerType = marketLoader.loadClass(BridgeWorkingServiceManagerFixture.class.getName());
        foreignManager = managerType.getConstructor().newInstance();
        bridge = new PandasticBridgeServiceManager(foreignManager);

        // PandasticMarket registers its cache-holder service with the leader it shares a loader with.
        Class<?> serviceType = marketLoader.loadClass(MarketCacheService.class.getName());
        foreignService = serviceType.getConstructor().newInstance();
        managerType.getMethod("registerService", marketLoader.loadClass(PandasticService.class.getName()))
                .invoke(foreignManager, foreignService);
    }

    @AfterEach
    void dropTrustedPrefixes() {
        PandasticJvmAnchor.anchorMap().remove(PandasticBridgePackages.BRIDGE_PACKAGES_KEY);
    }

    @Test
    @DisplayName("(i) typed getService returns a proxy castable to the consumer interface once its prefix is trusted")
    void typedLookupHonoursTheConsumerInterfaceOnceTrusted() {
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), OWNER_PLUGIN, CONSUMER_PACKAGE);

        MarketCache typed = bridge.getService(MarketCacheService.SERVICE_NAME, MarketCache.class);

        assertNotNull(typed);
        assertTrue(Proxy.isProxyClass(typed.getClass()),
                "a foreign concrete class can only arrive as a proxy");
        assertInstanceOf(PandasticService.class, typed, "the caller's library interfaces come along");
        assertInstanceOf(PandasticCache.class, typed, "including the cache hierarchy");
        assertEquals(MarketCacheService.SERVICE_NAME, typed.getServiceName(),
                "bridged calls through the typed proxy reach the foreign object");
    }

    @Test
    @DisplayName("(ii) the one-arg lookup is a PandasticService-only view: no consumer interface without trust")
    void oneArgLookupIsAPandasticServiceOnlyViewWithoutTrust() {
        PandasticService view = bridge.getService(MarketCacheService.SERVICE_NAME);

        assertNotNull(view);
        assertFalse(view instanceof MarketCache,
                "documented view: the untyped lookup cannot carry an untrusted consumer interface");
        assertEquals(MarketCacheService.SERVICE_NAME, view.getServiceName(),
                "the PandasticService surface itself still works");
    }

    @Test
    @DisplayName("(ii+) once trusted, even the one-arg lookup's proxy carries the consumer interface")
    void oneArgLookupCarriesTheConsumerInterfaceOnceTrusted() {
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), OWNER_PLUGIN, CONSUMER_PACKAGE);

        PandasticService view = bridge.getService(MarketCacheService.SERVICE_NAME);

        assertInstanceOf(MarketCache.class, view,
                "the trusted prefix governs the ADDITIONAL interfaces of every proxy, so the"
                        + " PandasticService-declared path picks the consumer interface up too");
    }

    @Test
    @DisplayName("(iii) without trust the typed lookup fails loudly — the requested type does not confer trust")
    void typedLookupWithoutTrustFailsLoudly() {
        // End to end: the untranslated caller-side token reaches the leader, whose cast cannot
        // match its own copy of the service — a loud name-collision CCE, never a miscast proxy.
        assertThrows(ClassCastException.class,
                () -> bridge.getService(MarketCacheService.SERVICE_NAME, MarketCache.class));

        // Adapter-level half of the same rule: explicitly requesting an untrusted consumer
        // interface does not reach crossProxy at all; the declared type alone is not trust.
        assertThrows(IllegalStateException.class,
                () -> PandasticServiceProxyFactory.proxyAs(MarketCache.class, foreignService));
    }

    @Test
    @DisplayName("(iv) removing a plugin's prefixes narrows fresh proxies back to the library surface")
    void removalRestoresTheUntrustedView() {
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), OWNER_PLUGIN, CONSUMER_PACKAGE);
        assertInstanceOf(MarketCache.class, bridge.getService(MarketCacheService.SERVICE_NAME),
                "sanity: the trust is in effect before removal");

        PandasticBridgePackages.removeAll(PandasticJvmAnchor.anchorMap(), OWNER_PLUGIN);

        assertFalse(PandasticBridgePackages.isTrustedPrefixOf(CONSUMER_PACKAGE + ".MarketCache"),
                "the anchor slot no longer trusts the prefix");
        Object slot = PandasticJvmAnchor.anchorMap().get(PandasticBridgePackages.BRIDGE_PACKAGES_KEY);
        assertTrue(slot instanceof Map<?, ?> byPlugin && byPlugin.get(OWNER_PLUGIN) == null,
                "the slot itself has forgotten the owning plugin");

        PandasticService fresh = bridge.getService(MarketCacheService.SERVICE_NAME);
        assertFalse(fresh instanceof MarketCache,
                "a FRESH proxy no longer implements the consumer interface (existing proxies are"
                        + " unaffected by design: their interface list was fixed at creation)");
    }

    @Test
    @DisplayName("(vi) a consumer cache's Map accessor crosses as the same live map, both directions")
    void consumerCacheMapCrossesLiveInBothDirections() throws Exception {
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), OWNER_PLUGIN, CONSUMER_PACKAGE);

        MarketCache cache = bridge.getService(MarketCacheService.SERVICE_NAME, MarketCache.class);
        assertNotNull(cache);
        Map<String, String> observed = cache.marketData();

        // Parent-visible element types: the bridge must hand the backing map over by reference.
        Object backing = foreignService.getClass().getMethod("marketData").invoke(foreignService);
        assertSame(backing, observed, "no copy, no translating view: one genuinely shared map");

        // Caller writes, owner observes.
        observed.put("DIAMOND", "64");
        assertEquals("64", ((Map<?, ?>) backing).get("DIAMOND"));

        // Owner writes, caller observes — live, not a snapshot.
        @SuppressWarnings("unchecked")
        Map<String, String> ownerSide = (Map<String, String>) backing;
        ownerSide.put("EMERALD", "32");
        assertEquals("32", observed.get("EMERALD"));
    }

    @Test
    @DisplayName("typed lookup of an unknown name stays null, trusted or not")
    void typedLookupOfUnknownNameIsNull() {
        assertNull(bridge.getService("no.such.service", PandasticService.class));
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), OWNER_PLUGIN, CONSUMER_PACKAGE);
        assertNull(bridge.getService("no.such.service", MarketCache.class));
    }
}
