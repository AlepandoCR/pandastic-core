package pandastic.studio.core.service.internal.profile;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Proxy;

/**
 * Builds just enough of a {@link Plugin} for unit tests: a dynamic proxy answering
 * {@code getName()} and the {@link Object} contract, and refusing everything else loudly. The
 * profile services only read the plugin's name (service identifiers, User-Agent) outside of I/O
 * paths, and these tests never touch I/O.
 */
final class FakePlugins {

    private FakePlugins() {
    }

    static Plugin named(String name) {
        return (Plugin) Proxy.newProxyInstance(
                FakePlugins.class.getClassLoader(),
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
