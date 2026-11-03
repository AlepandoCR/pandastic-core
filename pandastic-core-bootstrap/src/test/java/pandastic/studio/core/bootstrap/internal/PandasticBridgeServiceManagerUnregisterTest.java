package pandastic.studio.core.bootstrap.internal;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgePluginBoundServiceFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeWorkingServiceManagerFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two protocol-2 unregistration methods, exercised across a real classloader boundary.
 * <p>
 * What is under test here is the <em>bridge layer</em>: that the generated contract ids resolve
 * against a foreign copy of the library, that the service argument is adapted into a leader-side
 * proxy on the way in — which is precisely why identity matching cannot work and the name-based
 * contract exists — and that the {@code boolean}/{@code int} results cross back intact. The
 * matching semantics of the real registry are pinned by {@code PandasticServiceManagerImplTest} in
 * {@code pandastic-core-services}, which this module cannot load; the leader side here is
 * {@link BridgeWorkingServiceManagerFixture}, a faithful stand-in.
 */
class PandasticBridgeServiceManagerUnregisterTest {

    private Object foreignManager;
    private PandasticServiceManager bridge;
    private Plugin pluginA;
    private Plugin pluginB;

    @BeforeEach
    void bridgeOntoAForeignLeader() throws Exception {
        ClassLoader leaderLoader = new IsolatedLibraryClassLoader("leader", getClass().getClassLoader());
        Class<?> foreignType = leaderLoader.loadClass(BridgeWorkingServiceManagerFixture.class.getName());
        assertNotSame(BridgeWorkingServiceManagerFixture.class, foreignType,
                "the leader's registry must really live in another classloader");

        foreignManager = foreignType.getConstructor().newInstance();
        bridge = new PandasticBridgeServiceManager(foreignManager);
        pluginA = fakePlugin("PluginA");
        pluginB = fakePlugin("PluginB");
    }

    @Test
    @DisplayName("a follower can unregister the service it registered, despite the proxy in between")
    void unregisterServiceWorksAcrossTheBoundary() throws Exception {
        BridgePluginBoundServiceFixture service = new BridgePluginBoundServiceFixture("svc.a", pluginA);
        bridge.registerService(service);

        // Prove the identity mismatch is real: the leader stored a proxy, not the test's object.
        Object stored = leaderSideLookup("svc.a");
        assertNotNull(stored);
        assertNotSame(service, stored, "a library-typed service cannot cross by reference");
        assertTrue(Proxy.isProxyClass(stored.getClass()), "it crosses as a leader-side proxy");
        assertFalse(stored instanceof PandasticService,
                "the proxy implements the leader's interfaces, not this classloader's");

        assertTrue(bridge.unregisterService(service),
                "passing the follower's own object must still remove the registration");
        assertNull(leaderSideLookup("svc.a"), "the leader's registry really let go of the proxy");
        assertFalse(bridge.unregisterService(service), "the second call reports nothing removed");
    }

    @Test
    @DisplayName("a plugin-wide sweep removes the follower's services and leaves everyone else's")
    void unregisterAllWorksAcrossTheBoundary() {
        bridge.registerService(new BridgePluginBoundServiceFixture("svc.a1", pluginA));
        bridge.registerService(new BridgePluginBoundServiceFixture("svc.a2", pluginA));
        bridge.registerService(new BridgePluginBoundServiceFixture("svc.b1", pluginB));

        assertEquals(2, bridge.unregisterAll(pluginA), "the count crosses back as a real int");
        assertNull(bridge.getService("svc.a1"));
        assertNull(bridge.getService("svc.a2"));
        assertNotNull(bridge.getService("svc.b1"), "plugin B's registration is untouched");

        assertEquals(0, bridge.unregisterAll(pluginA), "a second sweep finds nothing");
    }

    /**
     * Reads what the foreign registry actually holds under a name, bypassing the bridge so the
     * assertion sees the stored object itself rather than a follower-side re-wrap of it.
     */
    private Object leaderSideLookup(String name) throws Exception {
        Method getService = foreignManager.getClass().getMethod("getService", String.class);
        return getService.invoke(foreignManager, name);
    }

    /**
     * A parent-visible {@link Plugin} proxy: {@code Plugin} comes from the server, not the library,
     * so the same instance is holdable on both sides and only its name matters to the sweep.
     */
    private static Plugin fakePlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                PandasticBridgeServiceManagerUnregisterTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> "FakePlugin(" + name + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "FakePlugin supports getName() only, not " + method.getName());
                });
    }
}
