package pandastic.studio.core.service.internal.profile;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.cache.PandasticProfileCache;
import pandastic.studio.core.service.profile.PandasticProfileService;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * The edition-agnostic profile service: a composition of the Bedrock and Java services over the
 * one shared cache, for callers that do not know which edition a player belongs to.
 * <h2>Resolution order</h2>
 * Every lookup peeks the shared cache first and completes immediately on a hit. On a miss the
 * order is fixed and deliberate: <b>Bedrock first, then Java</b>.
 * <ul>
 *     <li>For UUID-keyed lookups the Bedrock consultation is free when it cannot apply: the
 *     Bedrock service answers a non-Floodgate-format UUID with an immediate empty and no network
 *     traffic, so Java is reached without delay.</li>
 *     <li>For {@link #getUniqueIdentifier(String)}, a name carrying the Bedrock prefix is Bedrock
 *     by construction and is resolved through Bedrock <b>only</b> — a Java fallback could not
 *     succeed and would only leak a nonsense query. Any other name is tried against Bedrock first
 *     and falls back to Java on a miss.</li>
 * </ul>
 * <h2>Cache writes</h2>
 * This service never writes the cache itself: both delegates write their own resolutions into the
 * <em>same</em> shared instance this service peeks, so a second write here would be a duplicate.
 * <h2>Seams</h2>
 * The delegates and the prefix test are constructor-injected, which is what makes the resolution
 * strategy testable without any HTTP: tests pass recording fakes, production passes the real
 * Bedrock/Java services and {@link BedrockProfileServiceImpl#isPrefixedBedrockName}.
 *
 * @param <PandasticPluginType> the concrete plugin type this instance is bound to
 */
public final class UniversalProfileServiceImpl<PandasticPluginType extends Plugin> implements PandasticProfileService<PandasticPluginType> {

    private final PandasticPluginType plugin;
    private final PandasticProfileCacheImpl<PandasticPluginType> cache;
    private final PandasticProfileService<PandasticPluginType> bedrock;
    private final PandasticProfileService<PandasticPluginType> java;
    private final Predicate<String> bedrockOnlyName;
    private final ExecutorService executor;

    /**
     * @param plugin          the plugin this instance acts on behalf of
     * @param cache           the runtime's single shared profile cache, the same instance both
     *                        delegates write into
     * @param bedrock         the Bedrock delegate, consulted first on every miss
     * @param java            the Java delegate, the fallback
     * @param bedrockOnlyName decides whether a name is Bedrock by construction (carries the
     *                        Bedrock prefix), making the Java fallback pointless
     * @param executor        the runtime's shared thread pool
     */
    public UniversalProfileServiceImpl(
            @NotNull PandasticPluginType plugin,
            @NotNull PandasticProfileCacheImpl<PandasticPluginType> cache,
            @NotNull PandasticProfileService<PandasticPluginType> bedrock,
            @NotNull PandasticProfileService<PandasticPluginType> java,
            @NotNull Predicate<String> bedrockOnlyName,
            @NotNull ExecutorService executor
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.java = Objects.requireNonNull(java, "java");
        this.bedrockOnlyName = Objects.requireNonNull(bedrockOnlyName, "bedrockOnlyName");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<String>> getName(final @NotNull UUID uniqueIdentifier) {
        Objects.requireNonNull(uniqueIdentifier, "uniqueIdentifier");

        String cached = cache.getUniqueIdentifierToNameMap().get(uniqueIdentifier);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        return bedrock.getName(uniqueIdentifier).thenCompose(bedrockName ->
                bedrockName.isPresent()
                        ? CompletableFuture.completedFuture(bedrockName)
                        : java.getName(uniqueIdentifier));
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<UUID>> getUniqueIdentifier(final @NotNull String name) {
        Objects.requireNonNull(name, "name");

        UUID cached = cache.getNameToUniqueIdentifierMap().get(name.toLowerCase(Locale.ROOT));
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        if (bedrockOnlyName.test(name)) {
            // Prefixed names are Bedrock by construction; asking Mojang about one cannot succeed.
            return bedrock.getUniqueIdentifier(name);
        }

        return bedrock.getUniqueIdentifier(name).thenCompose(bedrockId ->
                bedrockId.isPresent()
                        ? CompletableFuture.completedFuture(bedrockId)
                        : java.getUniqueIdentifier(name));
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<SkinDto>> getSkin(final @NotNull UUID uniqueIdentifier) {
        Objects.requireNonNull(uniqueIdentifier, "uniqueIdentifier");

        SkinDto cached = cache.getUniqueIdentifierToSkinMap().get(uniqueIdentifier);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        return bedrock.getSkin(uniqueIdentifier).thenCompose(bedrockSkin ->
                bedrockSkin.isPresent()
                        ? CompletableFuture.completedFuture(bedrockSkin)
                        : java.getSkin(uniqueIdentifier));
    }

    @Override
    public @NotNull ExecutorService getExecutorService() {
        return executor;
    }

    @Override
    public @NotNull PandasticPluginType getBoundPlugin() {
        return plugin;
    }

    @Override
    public @NotNull PandasticProfileCache<PandasticPluginType, PandasticProfileService<PandasticPluginType>> getCache() {
        return cache;
    }

    @Override
    public @NotNull String getServiceName() {
        return "UniversalProfileService_" + plugin.getName();
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(getServiceName());
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Edition-agnostic profile lookups for " + plugin.getName()
                + ": shared cache first, then Bedrock, then Java.");
    }
}
