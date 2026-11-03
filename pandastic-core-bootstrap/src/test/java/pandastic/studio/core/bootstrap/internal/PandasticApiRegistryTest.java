package pandastic.studio.core.bootstrap.internal;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The follower registry lives in a JVM-global map, which makes every reference it holds a
 * potential classloader leak with no upper bound on its lifetime.
 * <p>
 * Two things depend on getting that right. The obvious one is memory: a {@code /reload} or a
 * plugin update replaces a plugin's classloader, and anything still referencing the old one keeps
 * its whole class graph alive for the life of the process. The less obvious one is correctness —
 * the leader may only tear the shared thread pool and HTTP client down once no follower is left,
 * so a registry that never forgets a dead follower means the shared runtime is never released
 * either, and each reload starts another one.
 */
class PandasticApiRegistryTest {

    @Test
    @DisplayName("a follower that goes away is forgotten, even while it still holds its own bridge")
    void theRegistryDoesNotPinAFollowersPlugin() throws Exception {
        Map<String, Object> anchor = new ConcurrentHashMap<>();

        // Every plugin keeps its PandasticCore in a field, so plugin and bridge reference each other.
        // That cycle is unreachable once the server drops the plugin -- unless the registry is
        // holding one end of it.
        Object bridge = new Object();
        Plugin plugin = stubPlugin("Follower", bridge);

        PandasticApiRegistry.registerFollower(anchor, plugin);
        assertEquals(1, PandasticApiRegistry.followerCount(anchor));
        assertTrue(PandasticApiRegistry.followers(anchor).contains(plugin));

        WeakReference<Plugin> watch = new WeakReference<>(plugin);
        plugin = null;
        bridge = null;

        for (int attempt = 0; attempt < 200 && watch.get() != null; attempt++) {
            System.gc();
            Thread.sleep(5);
        }

        assertNull(watch.get(), "the registry must not keep a disabled follower's plugin -- and with"
                + " it that plugin's entire classloader -- alive in a JVM-global map");
        assertEquals(0, PandasticApiRegistry.followerCount(anchor),
                "a collected follower must be pruned, or the leader can never release the shared runtime");
    }

    @Test
    @DisplayName("an explicit detach removes the follower")
    void unregisteringDetachesTheFollower() {
        Map<String, Object> anchor = new ConcurrentHashMap<>();
        Plugin plugin = stubPlugin("Follower", new Object());

        PandasticApiRegistry.registerFollower(anchor, plugin);
        assertEquals(1, PandasticApiRegistry.followerCount(anchor));

        PandasticApiRegistry.unregisterFollower(anchor, plugin);
        assertEquals(0, PandasticApiRegistry.followerCount(anchor));
        assertFalse(PandasticApiRegistry.followers(anchor).contains(plugin));
    }

    @Test
    @DisplayName("a poisoned follower slot degrades to empty instead of breaking every participant")
    void aPoisonedFollowerSlotIsNotBelieved() {
        Map<String, Object> anchor = new ConcurrentHashMap<>();
        // The anchor is a world-writable system property, so this slot is untrusted input just like
        // the leader slot. Reading it must not be able to throw a ClassCastException into the
        // bootstrap path of every other plugin in the JVM.
        anchor.put(PandasticApiRegistry.FOLLOWERS_KEY, "not a map at all");
        assertEquals(0, PandasticApiRegistry.followerCount(anchor));

        Map<Object, Object> wrongShape = new ConcurrentHashMap<>();
        wrongShape.put("surprise", "also not a weak reference");
        anchor.put(PandasticApiRegistry.FOLLOWERS_KEY, wrongShape);
        assertEquals(0, PandasticApiRegistry.followerCount(anchor));

        Plugin plugin = stubPlugin("Follower", new Object());
        PandasticApiRegistry.registerFollower(anchor, plugin);
        assertEquals(1, PandasticApiRegistry.followerCount(anchor),
                "and a usable registry must be restored rather than left poisoned");
    }

    /**
     * A minimal {@link Plugin} that reports a name and holds its bridge, which is all the registry
     * touches. A proxy rather than a class, because {@code Plugin} is wide and none of the rest of
     * it is relevant here.
     */
    private static Plugin stubPlugin(String name, Object bridge) {
        InvocationHandler handler = new StubPluginHandler(name, bridge);
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    private record StubPluginHandler(String name, Object bridge) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> true;
                case "toString" -> "StubPlugin(" + name + ")";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
