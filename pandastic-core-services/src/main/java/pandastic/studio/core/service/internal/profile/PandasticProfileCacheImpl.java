package pandastic.studio.core.service.internal.profile;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.cache.PandasticProfileCache;
import pandastic.studio.core.service.profile.PandasticProfileService;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one profile cache of the leader runtime.
 * <p>
 * {@code PandasticRuntime} creates exactly one instance alongside the thread pool and the HTTP client,
 * and every profile service of every plugin — Java, Bedrock and universal alike — is constructed
 * around that same instance. Three concurrent maps, nothing else: both name/UUID directions and
 * the skins. Name keys are stored lowercase through {@link #rememberName}, which is the single
 * write path the services use so the two directions can never disagree on key normalisation.
 * <h2>Ownership</h2>
 * {@link #getOwnerService()} reports the <em>first</em> profile service created against the
 * runtime, recorded by {@link #adoptOwner} at creation time. The cache predates all of its
 * services (it is born with the runtime), so "the service that created it" is by definition the
 * service whose creation first touched it; later services share the cache without claiming it.
 * <h2>Generics</h2>
 * The plugin type parameter exists only to satisfy {@link PandasticProfileCache}'s bound — no cached
 * datum mentions it. That is what makes it sound for {@code PandasticCoreImpl} to view the single
 * instance as {@code PandasticProfileCacheImpl<P>} for every requesting plugin's {@code P}.
 *
 * @param <PandasticPluginType> the plugin type of the services viewing this cache; semantically unused
 */
public final class PandasticProfileCacheImpl<PandasticPluginType extends Plugin> implements PandasticProfileCache<PandasticPluginType, PandasticProfileService<PandasticPluginType>> {

    /**
     * Stable registry identifier of the runtime's single profile cache.
     */
    public static final String SERVICE_NAME = "ProfileCache";

    private final Map<UUID, String> uniqueIdentifierToName = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameToUniqueIdentifier = new ConcurrentHashMap<>();
    private final Map<UUID, SkinDto> uniqueIdentifierToSkin = new ConcurrentHashMap<>();

    /**
     * The first profile service created against the runtime; set once by {@link #adoptOwner}.
     */
    private final AtomicReference<PandasticProfileService<PandasticPluginType>> owner = new AtomicReference<>();

    @Override
    public @NotNull Map<UUID, String> getUniqueIdentifierToNameMap() {
        return uniqueIdentifierToName;
    }

    @Override
    public @NotNull Map<String, UUID> getNameToUniqueIdentifierMap() {
        return nameToUniqueIdentifier;
    }

    @Override
    public @NotNull Map<UUID, SkinDto> getUniqueIdentifierToSkinMap() {
        return uniqueIdentifierToSkin;
    }

    @Override
    public void clear() {
        uniqueIdentifierToName.clear();
        nameToUniqueIdentifier.clear();
        uniqueIdentifierToSkin.clear();
    }

    @Override
    public @NotNull PandasticProfileService<PandasticPluginType> getOwnerService() {
        PandasticProfileService<PandasticPluginType> adopted = owner.get();
        if (adopted == null) {
            // Unreachable through the public surface: the cache only becomes visible to callers
            // when the first profile service is created, and adoption happens in that same
            // creation step, before registration.
            throw new IllegalStateException("The profile cache has no owner yet: no profile service"
                    + " has been created against this runtime.");
        }
        return adopted;
    }

    /**
     * Records the service that owns this cache, once.
     * <p>
     * Called by the leader implementation for every profile service it creates; only the first
     * call wins, which encodes the ownership rule documented on the class.
     *
     * @param candidate the service whose creation touched the cache
     */
    public void adoptOwner(@NotNull PandasticProfileService<PandasticPluginType> candidate) {
        Objects.requireNonNull(candidate, "candidate");
        owner.compareAndSet(null, candidate);
    }

    /**
     * Writes a resolved name into <b>both</b> directions: {@code uuid → name} with the player's
     * real capitalisation, and {@code lowercase(name) → uuid} for case-insensitive lookups.
     * <p>
     * This is the services' single write path for the name pair, so the two maps cannot drift
     * apart on key normalisation.
     *
     * @param uniqueIdentifier the resolved UUID
     * @param name             the resolved display name (prefix included for Bedrock players)
     */
    void rememberName(@NotNull UUID uniqueIdentifier, @NotNull String name) {
        uniqueIdentifierToName.put(uniqueIdentifier, name);
        nameToUniqueIdentifier.put(name.toLowerCase(Locale.ROOT), uniqueIdentifier);
    }

    /**
     * Writes a resolved skin.
     *
     * @param uniqueIdentifier the player's UUID
     * @param skin             the resolved signed skin
     */
    void rememberSkin(@NotNull UUID uniqueIdentifier, @NotNull SkinDto skin) {
        uniqueIdentifierToSkin.put(uniqueIdentifier, skin);
    }

    @Override
    public @NotNull String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(SERVICE_NAME);
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Shared player-profile cache: name/UUID in both directions plus"
                + " signed skins, one instance for every plugin in the runtime.");
    }
}
