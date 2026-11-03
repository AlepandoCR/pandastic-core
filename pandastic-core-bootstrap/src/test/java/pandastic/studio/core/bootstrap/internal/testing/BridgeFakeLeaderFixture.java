package pandastic.studio.core.bootstrap.internal.testing;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticCore;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;
import pandastic.studio.core.service.profile.PandasticProfileService;

import java.util.Collection;
import java.util.List;

/**
 * A structurally genuine leader: a public library class implementing {@link PandasticCore}.
 * <p>
 * Every method throws, deliberately. Validating a leader must be a purely structural decision —
 * if accepting one required invoking anything on it, the check itself would be the attack surface
 * it is supposed to close. A test that passes with this fixture proves nothing was called.
 */
public final class BridgeFakeLeaderFixture implements PandasticCore {

    public BridgeFakeLeaderFixture() {
    }

    @Contract(pure = true)
    @Override
    public @NotNull Collection<PandasticService> getServices() {
        return List.of();
    }

    @Override
    public @NotNull PandasticServiceManager getServiceManager() {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public <P extends Plugin> @NotNull PandasticProfileService<P> getJavaProfileService(@NotNull P plugin) {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public <P extends Plugin> @NotNull PandasticProfileService<P> getBedrockProfileService(@NotNull P plugin) {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public <P extends Plugin> @NotNull PandasticProfileService<P> getUniversalProfileService(@NotNull P plugin) {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public @NotNull pandastic.studio.core.service.economy.PandasticEconomyService getEconomyService() {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public void registerBridgePackage(@NotNull Plugin owner, @NotNull String packagePrefix) {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public @NotNull String getServiceName() {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public @NotNull Component getName() {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }

    @Override
    public @NotNull Component getDescription() {
        throw new UnsupportedOperationException("leader validation must not invoke anything");
    }
}
