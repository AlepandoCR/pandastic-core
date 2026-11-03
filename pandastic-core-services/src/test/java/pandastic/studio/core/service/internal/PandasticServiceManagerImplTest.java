package pandastic.studio.core.service.internal;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.PandasticBridgePackages;
import pandastic.studio.core.bootstrap.internal.PandasticJvmAnchor;
import pandastic.studio.core.service.api.PluginBoundPandasticService;
import pandastic.studio.core.service.api.PandasticService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry's unregistration contract, which closes the library's main remaining classloader
 * leak: a follower-registered service used to stay in the leader's registry — and pin the
 * follower's classloader — until the leader itself died.
 * <p>
 * The tests run leader-side on purpose: the matching strategy ({@code ==} first, service-name
 * fallback) is entirely the implementation's, and it must hold regardless of whether the argument
 * arrived directly or as a bridge proxy. The proxy case is simulated with a plain JDK proxy
 * delegating to the registered object, which reproduces exactly what the bridge hands the leader:
 * a different reference reporting the same service name.
 */
class PandasticServiceManagerImplTest {

    private PandasticServiceManagerImpl manager;
    private Plugin pluginA;
    private Plugin pluginB;

    @BeforeEach
    void freshRegistry() {
        manager = new PandasticServiceManagerImpl();
        pluginA = fakePlugin("PluginA");
        pluginB = fakePlugin("PluginB");
    }

    @Test
    @DisplayName("unregisterAll removes exactly the plugin's bound services and reports the count")
    void unregisterAllRemovesOnlyThatPluginsServices() {
        TestService a1 = new TestService("a.one", pluginA);
        TestService a2 = new TestService("a.two", pluginA);
        TestService b1 = new TestService("b.one", pluginB);
        PlainService shared = new PlainService("shared");
        manager.registerService(a1);
        manager.registerService(a2);
        manager.registerService(b1);
        manager.registerService(shared);

        assertEquals(2, manager.unregisterAll(pluginA));

        assertEquals(List.of(b1, shared), manager.getAllServices(),
                "registration order of the survivors is preserved");
        assertNull(manager.getService("a.one"), "the name index must forget removed services");
        assertNull(manager.getService("a.two"));
        assertTrue(manager.getPluginBoundServices(pluginA).isEmpty(),
                "plugin lookups must no longer see plugin A");
        assertNull(manager.getServiceForPlugin(pluginA, TestService.class));
        assertFalse(manager.hasRegisteredService(pluginA, TestService.class));

        assertSame(b1, manager.getService("b.one"), "plugin B is untouched");
        assertEquals(List.of(b1), manager.getPluginBoundServices(pluginB));
        assertSame(shared, manager.getService("shared"),
                "services that are not plugin-bound are never swept");

        assertEquals(0, manager.unregisterAll(pluginA), "a second sweep finds nothing");
    }

    @Test
    @DisplayName("unregisterAll matches by plugin name, so a reloaded plugin instance still cleans up")
    void unregisterAllMatchesByPluginName() {
        manager.registerService(new TestService("a.one", pluginA));

        // A /reload produces a brand-new Plugin instance (and classloader) with the same name;
        // that new incarnation must still be able to clean up what the old one registered.
        Plugin reloadedA = fakePlugin("PluginA");
        assertNotSame(pluginA, reloadedA);

        assertEquals(1, manager.unregisterAll(reloadedA));
        assertNull(manager.getService("a.one"));
    }

    @Test
    @DisplayName("typed getService returns the instance itself in the shared-classloader case")
    void typedGetServiceReturnsTheInstance() {
        TestService a1 = new TestService("a.one", pluginA);
        manager.registerService(a1);

        assertSame(a1, manager.getService("a.one", PandasticService.class),
                "same classloader: no proxy, the real object");
        assertSame(a1, manager.getService("a.one", TestService.class),
                "any type the service actually is works, down to the concrete class");
        assertNull(manager.getService("never.registered", PandasticService.class),
                "an unknown name is an absence, not an error");
    }

    @Test
    @DisplayName("typed getService throws ClassCastException on a name hit with the wrong type")
    void typedGetServiceThrowsOnTypeMismatch() {
        manager.registerService(new PlainService("shared"));

        // A name hit with the wrong type is a caller bug: null here would read as "not
        // registered" and send the caller down a re-registration path with a taken name.
        assertThrows(ClassCastException.class,
                () -> manager.getService("shared", TestService.class));
        assertSame(manager.getService("shared"), manager.getService("shared", PandasticService.class),
                "the registration itself is untouched by the failed typed lookup");
    }

    @Test
    @DisplayName("unregisterAll also drops the plugin's trusted bridge packages from the anchor")
    void unregisterAllDropsTrustedBridgePackages() {
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), "PluginA", "com.example.pkga");
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), "PluginB", "com.example.pkgb");
        manager.registerService(new TestService("a.one", pluginA));

        try {
            manager.unregisterAll(pluginA);

            assertFalse(PandasticBridgePackages.isTrustedPrefixOf("com.example.pkga.Gone"),
                    "an unloaded plugin's prefix must not keep its interfaces proxyable");
            assertTrue(PandasticBridgePackages.isTrustedPrefixOf("com.example.pkgb.Stays"),
                    "the sweep is scoped to the one plugin");
        } finally {
            PandasticJvmAnchor.anchorMap().remove(PandasticBridgePackages.BRIDGE_PACKAGES_KEY);
        }
    }

    @Test
    @DisplayName("unregisterService removes exactly one registration and is not repeatable")
    void unregisterServiceRemovesExactlyOne() {
        TestService a1 = new TestService("a.one", pluginA);
        TestService a2 = new TestService("a.two", pluginA);
        manager.registerService(a1);
        manager.registerService(a2);

        assertTrue(manager.unregisterService(a1));
        assertEquals(List.of(a2), manager.getAllServices(), "only the requested service goes");
        assertNull(manager.getService("a.one"));
        assertSame(a2, manager.getService("a.two"));

        assertFalse(manager.unregisterService(a1), "the second call must report nothing removed");
        assertFalse(manager.unregisterService(new TestService("never.registered", pluginB)));
    }

    @Test
    @DisplayName("unregisterService matches a proxy over the registered object by service name")
    void unregisterServiceMatchesThroughProxies() {
        // What the bridge really stores leader-side: not the follower's object but a proxy over it.
        TestService original = new TestService("a.one", pluginA);
        PandasticService registeredProxy = delegatingProxy(original);
        assertNotSame(original, registeredProxy);
        manager.registerService(registeredProxy);

        // The follower later passes its own object (or another fresh proxy) — never the stored
        // reference. The service name is the identity that survives.
        assertTrue(manager.unregisterService(original),
                "identity fails here by construction, so the name fallback must carry");
        assertTrue(manager.getAllServices().isEmpty());
        assertNull(manager.getService("a.one"));

        manager.registerService(new TestService("b.one", pluginB));
        PandasticService otherProxy = delegatingProxy(new TestService("b.one", pluginB));
        assertTrue(manager.unregisterService(otherProxy),
                "a fresh proxy over an equal-named service also resolves to the one registration");
    }

    @Test
    @DisplayName("a removed name is immediately registrable again")
    void nameIndexStaysConsistentAfterRemoval() {
        TestService first = new TestService("a.one", pluginA);
        manager.registerService(first);
        assertTrue(manager.unregisterService(first));

        TestService second = new TestService("a.one", pluginB);
        assertDoesNotThrow(() -> manager.registerService(second),
                "unregistration must release the unique name, not strand it");
        assertSame(second, manager.getService("a.one"));
        assertEquals(List.of(second), manager.getAllServices());

        assertEquals(1, manager.unregisterAll(pluginB));
        assertDoesNotThrow(() -> manager.registerService(first),
                "the same holds after a plugin-wide sweep");
        assertSame(first, manager.getService("a.one"));
    }

    @Test
    @DisplayName("a service that cannot name its plugin is skipped, not fatal to the sweep")
    void unregisterAllSurvivesAMisbehavingService() {
        manager.registerService(new TestService("a.one", pluginA));
        manager.registerService(new PluginBoundPandasticService<Plugin>() {
            @Override
            public @NotNull Plugin getBoundPlugin() {
                throw new IllegalStateException("plugin already torn down");
            }

            @Override
            public @NotNull String getServiceName() {
                return "a.broken";
            }

            @Override
            public @NotNull Component getName() {
                return Component.text("broken");
            }

            @Override
            public @NotNull Component getDescription() {
                return Component.text("throws from getBoundPlugin");
            }
        });

        assertEquals(1, manager.unregisterAll(pluginA),
                "the sweep removes what it can and skips what it cannot ask");
        assertNull(manager.getService("a.one"));
        assertTrue(manager.getService("a.broken") != null,
                "the unanswerable service stays registered rather than being guessed about");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Just enough of a {@link Plugin}: a JDK proxy answering {@code getName()} and the
     * {@link Object} contract, loudly refusing everything else. Unregistration may only ever need
     * the name — that is the point of the name-based contract.
     */
    private static Plugin fakePlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                PandasticServiceManagerImplTest.class.getClassLoader(),
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

    /**
     * Stands in for the leader-side bridge proxy: a different reference that answers every
     * {@link PandasticService} method by delegating to the wrapped object.
     */
    private static PandasticService delegatingProxy(PandasticService target) {
        return (PandasticService) Proxy.newProxyInstance(
                PandasticServiceManagerImplTest.class.getClassLoader(),
                new Class<?>[]{PandasticService.class},
                (proxy, method, args) -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static final class TestService implements PluginBoundPandasticService<Plugin> {

        private final String serviceName;
        private final Plugin boundPlugin;

        TestService(String serviceName, Plugin boundPlugin) {
            this.serviceName = serviceName;
            this.boundPlugin = boundPlugin;
        }

        @Override
        public @NotNull Plugin getBoundPlugin() {
            return boundPlugin;
        }

        @Override
        public @NotNull String getServiceName() {
            return serviceName;
        }

        @Override
        public @NotNull Component getName() {
            return Component.text(serviceName);
        }

        @Override
        public @NotNull Component getDescription() {
            return Component.text("test service " + serviceName);
        }
    }

    private static final class PlainService implements PandasticService {

        private final String serviceName;

        PlainService(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public @NotNull String getServiceName() {
            return serviceName;
        }

        @Override
        public @NotNull Component getName() {
            return Component.text(serviceName);
        }

        @Override
        public @NotNull Component getDescription() {
            return Component.text("plain test service " + serviceName);
        }
    }
}
