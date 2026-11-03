package pandastic.studio.core.service.internal.profile;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.cache.PandasticProfileCache;
import pandastic.studio.core.service.profile.PandasticProfileService;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * A profile-service fake for exercising {@link UniversalProfileServiceImpl}'s resolution strategy
 * without any HTTP: every lookup appends to a shared call log (so consultation <em>order</em> is
 * observable, not just counts) and answers from canned values.
 */
final class RecordingProfileService implements PandasticProfileService<Plugin> {

    private final String id;
    private final Plugin plugin;
    private final List<String> callLog;

    private Optional<String> nameAnswer = Optional.empty();
    private Optional<UUID> uniqueIdentifierAnswer = Optional.empty();
    private Optional<SkinDto> skinAnswer = Optional.empty();

    /**
     * @param id      tag this fake writes into the call log, e.g. {@code "bedrock"}
     * @param plugin  the plugin the fake claims to be bound to
     * @param callLog the log shared between fakes, so inter-service ordering is recorded
     */
    RecordingProfileService(String id, Plugin plugin, List<String> callLog) {
        this.id = id;
        this.plugin = plugin;
        this.callLog = callLog;
    }

    RecordingProfileService answerName(String name) {
        this.nameAnswer = Optional.of(name);
        return this;
    }

    RecordingProfileService answerUniqueIdentifier(UUID uniqueIdentifier) {
        this.uniqueIdentifierAnswer = Optional.of(uniqueIdentifier);
        return this;
    }

    RecordingProfileService answerSkin(SkinDto skin) {
        this.skinAnswer = Optional.of(skin);
        return this;
    }

    /**
     * How many lookups reached this fake, across all three methods.
     */
    long lookups() {
        return callLog.stream().filter(entry -> entry.startsWith(id + ":")).count();
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<String>> getName(final @NotNull UUID uniqueIdentifier) {
        callLog.add(id + ":getName");
        return CompletableFuture.completedFuture(nameAnswer);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<UUID>> getUniqueIdentifier(final @NotNull String name) {
        callLog.add(id + ":getUniqueIdentifier");
        return CompletableFuture.completedFuture(uniqueIdentifierAnswer);
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<SkinDto>> getSkin(final @NotNull UUID uniqueIdentifier) {
        callLog.add(id + ":getSkin");
        return CompletableFuture.completedFuture(skinAnswer);
    }

    @Override
    public @NotNull ExecutorService getExecutorService() {
        throw new UnsupportedOperationException("the universal service must not touch a delegate's executor");
    }

    @Override
    public @NotNull Plugin getBoundPlugin() {
        return plugin;
    }

    @Override
    public @NotNull PandasticProfileCache<Plugin, PandasticProfileService<Plugin>> getCache() {
        throw new UnsupportedOperationException("the universal service peeks its own cache, never a delegate's");
    }

    @Override
    public @NotNull String getServiceName() {
        return "RecordingProfileService_" + id;
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(getServiceName());
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Recording fake for resolution-order tests.");
    }
}
