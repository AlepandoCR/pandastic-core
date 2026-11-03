package pandastic.studio.core.service.internal.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.service.api.cache.PandasticProfileCache;
import pandastic.studio.core.service.profile.PandasticProfileService;
import pandastic.studio.core.util.api.data.SkinDto;

import java.net.http.HttpClient;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * Java-edition profile lookups against <a href="https://playerdb.co">playerdb.co</a>, which
 * resolves usernames and UUIDs (dashed or raw) on one endpoint and includes the signed
 * {@code textures} property by default.
 * <p>
 * All I/O goes through the leader runtime's shared {@link HttpClient} and executor, and every
 * resolution is written into the runtime's single shared {@link PandasticProfileCacheImpl} — both
 * directions for the name/UUID pair — so the next caller from any plugin gets a completed future.
 * Misses and failures resolve to {@link Optional#empty()}, never exceptionally; failures worth a
 * human's attention are logged to the bound plugin's logger.
 *
 * @param <PandasticPluginType> the concrete plugin type this instance is bound to
 */
public final class JavaProfileServiceImpl<PandasticPluginType extends Plugin> implements PandasticProfileService<PandasticPluginType> {

    private static final String PLAYERDB_URL = "https://playerdb.co/api/player/minecraft/";

    private final PandasticPluginType plugin;
    private final PandasticProfileCacheImpl<PandasticPluginType> cache;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final String userAgent;

    /**
     * @param plugin     the plugin this instance acts on behalf of
     * @param cache      the runtime's single shared profile cache
     * @param executor   the runtime's shared thread pool
     * @param httpClient the runtime's shared HTTP client
     */
    public JavaProfileServiceImpl(@NotNull PandasticPluginType plugin,
                                  @NotNull PandasticProfileCacheImpl<PandasticPluginType> cache,
                                  @NotNull ExecutorService executor,
                                  @NotNull HttpClient httpClient) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        // playerdb.co asks for an identifying User-Agent.
        this.userAgent = "PandasticCore-JavaProfileService/1.0 (plugin: " + plugin.getName() + ")";
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<String>> getName(final @NotNull UUID uniqueIdentifier) {
        Objects.requireNonNull(uniqueIdentifier, "uniqueIdentifier");

        String cached = cache.getUniqueIdentifierToNameMap().get(uniqueIdentifier);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        return fetchPlayer(uniqueIdentifier.toString()).thenApply(player -> {
            try {
                if (player == null || !player.has("username")) return Optional.<String>empty();
                String name = player.get("username").getAsString();
                cache.rememberName(uniqueIdentifier, name);
                return Optional.of(name);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve name for UUID " + uniqueIdentifier, e);
                return Optional.<String>empty();
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<UUID>> getUniqueIdentifier(final @NotNull String name) {
        Objects.requireNonNull(name, "name");

        UUID cached = cache.getNameToUniqueIdentifierMap().get(name.toLowerCase(Locale.ROOT));
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        return fetchPlayer(name).thenApply(player -> {
            try {
                if (player == null || !player.has("id")) return Optional.<UUID>empty();
                // "id" is already a dashed UUID; "username" restores the player's capitalisation.
                UUID uniqueIdentifier = UUID.fromString(player.get("id").getAsString());
                String username = player.has("username") ? player.get("username").getAsString() : name;
                cache.rememberName(uniqueIdentifier, username);
                return Optional.of(uniqueIdentifier);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve UUID for name " + name, e);
                return Optional.<UUID>empty();
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<SkinDto>> getSkin(final @NotNull UUID uniqueIdentifier) {
        Objects.requireNonNull(uniqueIdentifier, "uniqueIdentifier");

        SkinDto cached = cache.getUniqueIdentifierToSkinMap().get(uniqueIdentifier);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        return fetchPlayer(uniqueIdentifier.toString()).thenApply(player -> {
            try {
                SkinDto skin = extractSkin(player);
                if (skin == null) return Optional.<SkinDto>empty();
                cache.rememberSkin(uniqueIdentifier, skin);
                return Optional.of(skin);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve skin for UUID " + uniqueIdentifier, e);
                return Optional.<SkinDto>empty();
            }
        });
    }

    /**
     * Pulls the signed {@code textures} property out of a playerdb.co player object.
     * <p>
     * Both the value and the signature are required: {@link SkinDto} promises a signature, and an
     * unsigned texture is useless to the places skins are applied, so a property without one is a
     * miss rather than a half-filled result.
     */
    private static @Nullable SkinDto extractSkin(@Nullable JsonObject player) {
        if (player == null || !player.has("properties")) return null;

        JsonArray properties = player.getAsJsonArray("properties");
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if (!property.has("name") || !"textures".equals(property.get("name").getAsString())) continue;
            if (!property.has("value") || !property.has("signature")) continue;
            return SkinDto.of(property.get("value").getAsString(), property.get("signature").getAsString());
        }
        return null;
    }

    /**
     * Fetches the {@code data.player} object for a username or UUID, resolving to {@code null} if
     * the lookup failed or the player was not found — the shared swallow-to-null contract.
     */
    private @NotNull CompletableFuture<@Nullable JsonObject> fetchPlayer(@NotNull String usernameOrUuid) {
        String url = PLAYERDB_URL + ProfileJsonHttp.encodeSegment(usernameOrUuid);
        return ProfileJsonHttp.getJson(httpClient, executor, url, userAgent, plugin.getLogger())
                .thenApply(response -> {
                    try {
                        if (response == null) return null;
                        if (response.has("success") && !response.get("success").getAsBoolean()) return null;
                        if (!response.has("data")) return null;
                        JsonObject data = response.getAsJsonObject("data");
                        return data.has("player") ? data.getAsJsonObject("player") : null;
                    } catch (RuntimeException malformedShape) {
                        return null;
                    }
                });
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
        return "JavaProfileService_" + plugin.getName();
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(getServiceName());
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Java-edition profile lookups (names, UUIDs, signed skins) for "
                + plugin.getName() + ", backed by the shared profile cache.");
    }
}
