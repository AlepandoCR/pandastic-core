package pandastic.studio.core.service.internal;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticCore;
import pandastic.studio.core.service.api.PandasticCoreProvider;

/**
 * Registers {@link PandasticCoreImpl} as this copy's leader implementation.
 * <p>
 * Discovered through {@link java.util.ServiceLoader}, which is what keeps the bootstrap free of a
 * hardcoded implementation class name while still respecting the one-way dependency from
 * implementation to API. The declaration lives in
 * {@code META-INF/services/pandastic.studio.core.service.api.PandasticCoreProvider}.
 */
public final class PandasticCoreProviderImpl implements PandasticCoreProvider {

    /**
     * Required by {@link java.util.ServiceLoader}.
     */
    public PandasticCoreProviderImpl() {
    }

    @Override
    public @NotNull PandasticCore createLeader(@NotNull Plugin plugin) {
        return new PandasticCoreImpl(plugin);
    }
}
