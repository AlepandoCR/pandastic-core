package pandastic.studio.core.service.api.cache;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.profile.PandasticProfileService;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.Map;
import java.util.UUID;

/**
 * The shared player-profile cache behind every {@link PandasticProfileService}.
 * <p>
 * The leader runtime owns exactly one instance, and every profile service of every plugin — Java,
 * Bedrock and universal alike — reads and writes it. That is the point of exposing the maps
 * directly: a name resolved by one plugin's service is a cache hit for all the others.
 * <h2>Map semantics</h2>
 * All three maps are live, concurrent views of the cache's state. Name keys in
 * {@link #getNameToUniqueIdentifierMap()} are stored lowercase, so lookups must lowercase first;
 * the values of {@link #getUniqueIdentifierToNameMap()} keep the player's real capitalisation
 * (including the Bedrock prefix for Bedrock players). Across the classloader bridge the two
 * name/UUID maps cross as the <em>same live instance</em> (their entries are parent-visible), and
 * the skin map crosses as a live translating view whose {@link SkinDto} values are copied by value
 * in each direction — so writes from a follower land in the leader's map either way.
 *
 * @param <PandasticPluginType>         the plugin type of the owning service; carried only to satisfy
 *                                {@link PandasticProfileService}'s own bound, since the cached data is
 *                                plugin-independent
 * @param <PandasticProfileServiceType> the profile service type that owns this cache
 */
public interface PandasticProfileCache<PandasticPluginType extends Plugin, PandasticProfileServiceType extends PandasticProfileService<PandasticPluginType>> extends PandasticCache<PandasticProfileServiceType>{

    /**
     * Live view of the UUID-to-name direction.
     * <p>
     * Values are display names as the player carries them in game: real capitalisation, Bedrock
     * prefix included for Bedrock players.
     *
     * @return the live concurrent map, shared by every plugin in the runtime
     */
    @NotNull
    Map<UUID, String> getUniqueIdentifierToNameMap();

    /**
     * Live view of the name-to-UUID direction.
     * <p>
     * Keys are stored lowercase; lowercase the name before looking it up.
     *
     * @return the live concurrent map, shared by every plugin in the runtime
     */
    @NotNull Map<String, UUID> getNameToUniqueIdentifierMap();

    /**
     * Live view of the UUID-to-skin direction.
     * <p>
     * Values are {@link SkinDto}s and therefore cross the classloader bridge by value: each side
     * of the bridge holds its own copy, while reads and writes still hit this one map.
     *
     * @return the live concurrent map, shared by every plugin in the runtime
     */
    @NotNull Map<UUID, SkinDto> getUniqueIdentifierToSkinMap();

}
