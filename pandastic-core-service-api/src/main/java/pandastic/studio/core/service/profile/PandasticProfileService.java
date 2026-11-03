package pandastic.studio.core.service.profile;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.AsyncPandasticService;
import pandastic.studio.core.service.api.CacheHolderPandasticService;
import pandastic.studio.core.service.api.PluginBoundPandasticService;
import pandastic.studio.core.service.api.cache.PandasticProfileCache;
import pandastic.studio.core.util.api.component.PandasticComponentUtil;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous player-profile lookups: name by UUID, UUID by name, and skin by UUID.
 * <p>
 * Obtain instances from {@link pandastic.studio.core.service.api.PandasticCore}, which offers three flavours
 * backed by one shared cache: a Java-edition service, a Bedrock-edition service (Floodgate /
 * Geyser Global API), and a universal service composing the two. Each instance is
 * {@linkplain PluginBoundPandasticService bound} to the plugin that requested it, but all of them — for
 * every plugin in the JVM — read and write the leader runtime's single
 * {@link PandasticProfileCache}, so a player resolved once is resolved for everyone.
 * <h2>Result contract</h2>
 * Every method peeks the shared cache first and returns an already-completed future on a hit.
 * On a miss the lookup runs on the {@linkplain AsyncPandasticService#getExecutorService() shared
 * executor}; a successful resolution is written back into the cache (both directions for the
 * name/UUID pair) before the future completes. A player that cannot be resolved — unknown,
 * malformed, remote API down — completes with {@link Optional#empty()}; the futures never
 * complete exceptionally for an ordinary miss or transport failure.
 *
 * @param <PandasticPluginType> the concrete plugin type this service instance is bound to
 */
public interface PandasticProfileService<PandasticPluginType extends Plugin> extends AsyncPandasticService, PluginBoundPandasticService<PandasticPluginType>, CacheHolderPandasticService<PandasticProfileService<PandasticPluginType>, PandasticProfileCache<PandasticPluginType, PandasticProfileService<PandasticPluginType>>> {

    /**
     * Resolves the current player name of a UUID.
     * <p>
     * For a Bedrock (Floodgate-format) UUID the resolved name carries the configured Bedrock
     * prefix, exactly as the player appears in game.
     *
     * @param uniqueIdentifier the player's UUID
     * @return a future completing with the name, or {@link Optional#empty()} if the UUID could
     *         not be resolved; never completes exceptionally for a miss
     */
    @NotNull CompletableFuture<@NotNull Optional<String>> getName(final @NotNull UUID uniqueIdentifier);

    /**
     * Resolves the UUID of a player name.
     * <p>
     * Bedrock-aware implementations accept both prefixed and bare gamertags; the returned UUID is
     * then Floodgate-format (most significant bits zero, the XUID in the least significant bits).
     *
     * @param name the player name, matched case-insensitively
     * @return a future completing with the UUID, or {@link Optional#empty()} if the name could
     *         not be resolved; never completes exceptionally for a miss
     */
    @NotNull CompletableFuture<@NotNull Optional<UUID>> getUniqueIdentifier(final @NotNull String name);

    /**
     * Resolves the Mojang-signed skin of a UUID.
     * <p>
     * The result crosses the classloader bridge by value ({@link SkinDto} is a
     * {@link pandastic.studio.core.util.api.data.Dto}), so callers always hold their own copy.
     *
     * @param uniqueIdentifier the player's UUID
     * @return a future completing with the skin, or {@link Optional#empty()} if the player has no
     *         retrievable signed skin; never completes exceptionally for a miss
     */
    @NotNull CompletableFuture<@NotNull Optional<SkinDto>> getSkin(final @NotNull UUID uniqueIdentifier);

    /**
     * An inline player-head component for the player: one UUID in, a fully identified head out.
     * <p>
     * Resolves the player's <b>name and signed skin concurrently</b> — both through the shared
     * profile cache, so a warm player costs no lookup at all — and builds the head via
     * {@link PandasticComponentUtil#playerHead(UUID, String, SkinDto)} carrying the id, the name and the
     * {@code textures} property. The result renders immediately, offline-capable and identical for
     * every viewer, with no client-side profile lookup.
     * <p>
     * The skin is the gate: without a resolvable skin there is nothing to render and the result is
     * empty — fall back to an item, {@code PandasticComponentUtil.playerHeadByLookup(uniqueIdentifier)},
     * or plain text. A missing <em>name</em> does not block the head; it is attached only when it
     * resolved.
     *
     * @param uniqueIdentifier the player, Java or Bedrock
     * @return the head component, or empty when no skin could be resolved
     */
    default @NotNull CompletableFuture<@NotNull Optional<Component>> getPlayerHeadComponent(final @NotNull UUID uniqueIdentifier) {
        return getSkin(uniqueIdentifier).thenCombine(getName(uniqueIdentifier), (skin, name) ->
                skin.map(resolved -> PandasticComponentUtil.playerHead(
                        uniqueIdentifier, name.orElse(null), resolved)));
    }
}
