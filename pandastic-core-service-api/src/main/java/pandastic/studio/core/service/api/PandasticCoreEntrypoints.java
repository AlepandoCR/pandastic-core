package pandastic.studio.core.service.api;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Discovers this shaded copy's {@link PandasticCoreEntrypoint}.
 *
 * <p>Loaded from {@link PandasticCoreEntrypoint}'s <em>own</em> classloader so each shaded copy
 * resolves the entrypoint packaged next to it rather than one from another plugin's copy —
 * the same discipline the bootstrap applies when discovering the leader provider. The lookup
 * is memoized because it sits on the path of every {@link PandasticCore#instance} call.
 */
final class PandasticCoreEntrypoints {

    private static volatile PandasticCoreEntrypoint cached;

    private PandasticCoreEntrypoints() {
    }

    /**
     * The entrypoint for this copy of the library.
     *
     * @return the discovered entrypoint
     * @throws IllegalStateException if none or several are registered, which means the
     *                               bootstrap module was packaged incorrectly
     */
    static @NotNull PandasticCoreEntrypoint entrypoint() {
        PandasticCoreEntrypoint existing = cached;
        if (existing != null) return existing;

        synchronized (PandasticCoreEntrypoints.class) {
            if (cached != null) return cached;

            ClassLoader ownLoader = PandasticCoreEntrypoint.class.getClassLoader();
            List<PandasticCoreEntrypoint> discovered = new ArrayList<>(1);
            try {
                for (PandasticCoreEntrypoint entrypoint : ServiceLoader.load(PandasticCoreEntrypoint.class, ownLoader)) {
                    discovered.add(entrypoint);
                }
            } catch (ServiceConfigurationError e) {
                throw new IllegalStateException("Failed to load a " + PandasticCoreEntrypoint.class.getName()
                        + " from " + ownLoader + ".", e);
            }

            if (discovered.size() != 1) {
                throw new IllegalStateException("Expected exactly one " + PandasticCoreEntrypoint.class.getName()
                        + " in " + ownLoader + " but found " + discovered.size()
                        + ". The bootstrap module registers one under META-INF/services/"
                        + PandasticCoreEntrypoint.class.getName() + "; check the packaging.");
            }

            cached = discovered.getFirst();
            return cached;
        }
    }
}
