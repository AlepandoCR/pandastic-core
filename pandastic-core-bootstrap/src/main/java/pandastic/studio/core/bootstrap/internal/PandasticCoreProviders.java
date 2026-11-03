package pandastic.studio.core.bootstrap.internal;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticCoreProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds this shaded copy's leader implementation.
 * <p>
 * The bootstrap must not name the implementation class: dependencies run API ← implementation, and
 * writing the fully-qualified name of {@code PandasticCoreImpl} here would both invert that and add
 * exactly the kind of hardcoded address this library avoids. {@link ServiceLoader} answers the
 * question declaratively instead, and it is loaded from {@link PandasticCoreProvider}'s <em>own</em>
 * classloader so each shaded copy resolves to the provider packaged next to it rather than to
 * whichever plugin happens to be first on some other search path.
 * <p>
 * The lookup is cached because it is on the path of every leader election and every follower
 * re-election attempt.
 */
public final class PandasticCoreProviders {

    private static volatile PandasticCoreProvider cached;

    private PandasticCoreProviders() {
    }

    /**
     * The provider for this copy of the library.
     *
     * @throws IllegalStateException if no provider is registered, which means the implementation
     *                               module was not packaged into the plugin
     */
    public static @NotNull PandasticCoreProvider provider() {
        PandasticCoreProvider existing = cached;
        if (existing != null) return existing;

        synchronized (PandasticCoreProviders.class) {
            if (cached != null) return cached;

            ClassLoader ownLoader = PandasticCoreProvider.class.getClassLoader();
            List<PandasticCoreProvider> discovered = new ArrayList<>(1);
            try {
                for (PandasticCoreProvider provider : ServiceLoader.load(PandasticCoreProvider.class, ownLoader)) {
                    discovered.add(provider);
                }
            } catch (ServiceConfigurationError e) {
                throw new IllegalStateException("Failed to load a " + PandasticCoreProvider.class.getName()
                        + " from " + ownLoader + ".", e);
            }

            if (discovered.isEmpty()) {
                throw new IllegalStateException("No " + PandasticCoreProvider.class.getName()
                        + " is registered in " + ownLoader + ". The implementation module must be packaged"
                        + " with this library; it registers itself under META-INF/services/"
                        + PandasticCoreProvider.class.getName() + ".");
            }
            if (discovered.size() > 1) {
                PandasticCoreLog.warn("Found " + discovered.size() + " " + PandasticCoreProvider.class.getSimpleName()
                        + " implementations in " + ownLoader + "; using " + discovered.getFirst().getClass().getName()
                        + ". Exactly one is expected per shaded copy.");
            }

            cached = discovered.getFirst();
            return cached;
        }
    }
}
