package pandastic.studio.core.bootstrap.internal;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticCore;
import pandastic.studio.core.service.api.PandasticCoreEntrypoint;

/**
 * The {@link PandasticCoreEntrypoint} this module registers under {@code META-INF/services},
 * handing {@link PandasticCore#instance} over to {@link PandasticBootstrap#init}.
 */
public final class PandasticCoreEntrypointImpl implements PandasticCoreEntrypoint {

    /**
     * Public no-arg constructor, as {@link java.util.ServiceLoader} requires.
     */
    public PandasticCoreEntrypointImpl() {
    }

    @Override
    public @NotNull PandasticCore join(@NotNull JavaPlugin plugin, boolean logging) {
        return PandasticBootstrap.init(plugin, logging);
    }
}
