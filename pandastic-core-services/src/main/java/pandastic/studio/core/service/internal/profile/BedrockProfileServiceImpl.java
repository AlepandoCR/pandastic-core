package pandastic.studio.core.service.internal.profile;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
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
 * Bedrock-edition profile lookups: gamertags and XUIDs through Floodgate when that plugin is
 * installed, through the <a href="https://api.geysermc.org">Geyser Global API</a> when it is not,
 * and skins always through the Global API (Floodgate has no skin helper; the Global API serves the
 * Mojang-signed value and signature Geyser uploaded when the player joined).
 * <p>
 * This service's domain is the Floodgate UUID format — most significant bits zero, the XUID in the
 * least significant bits, the {@code createJavaPlayerId} layout. A UUID outside that format can
 * never be a Bedrock account, so UUID-keyed lookups answer it with an immediate
 * {@link Optional#empty()} and no network traffic. Resolved names carry the Bedrock prefix exactly
 * as the player appears in game.
 * <p>
 * All I/O goes through the leader runtime's shared {@link HttpClient} and executor, every
 * resolution is written into the runtime's single shared {@link PandasticProfileCacheImpl} (both
 * directions for the name/UUID pair), and misses resolve to {@link Optional#empty()}, never
 * exceptionally.
 * <h2>Floodgate isolation</h2>
 * Floodgate is optional at runtime. The availability probe is {@link #isFloodgateAvailable},
 * called by whoever constructs this service, and every touch of a Floodgate type is confined to
 * the nested {@link FloodgateBridge} so those classes only load once the probe has passed.
 *
 * @param <PandasticPluginType> the concrete plugin type this instance is bound to
 */
public final class BedrockProfileServiceImpl<PandasticPluginType extends Plugin> implements PandasticProfileService<PandasticPluginType> {

    private static final String GLOBAL_API = "https://api.geysermc.org";

    /**
     * Prefix assumed for Bedrock display names when Floodgate is absent and its configured prefix
     * cannot be asked for. Floodgate's own default.
     */
    private static final String FALLBACK_PREFIX = ".";

    private final PandasticPluginType plugin;
    private final PandasticProfileCacheImpl<PandasticPluginType> cache;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final boolean floodgateAvailable;
    private final String userAgent;

    /**
     * @param plugin             the plugin this instance acts on behalf of
     * @param cache              the runtime's single shared profile cache
     * @param executor           the runtime's shared thread pool
     * @param httpClient         the runtime's shared HTTP client
     * @param floodgateAvailable the caller's {@link #isFloodgateAvailable} probe result
     */
    public BedrockProfileServiceImpl(@NotNull PandasticPluginType plugin,
                                     @NotNull PandasticProfileCacheImpl<PandasticPluginType> cache,
                                     @NotNull ExecutorService executor,
                                     @NotNull HttpClient httpClient,
                                     boolean floodgateAvailable) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.floodgateAvailable = floodgateAvailable;
        this.userAgent = "PandasticCore-BedrockProfileService/1.0 (plugin: " + plugin.getName() + ")";
    }

    /**
     * Whether this UUID is a Floodgate-format id: high bits zero, XUID in the low bits. A pure
     * format check with no Floodgate classes involved, so historical Bedrock UUIDs are recognized
     * even on a server without the plugin.
     *
     * @param uniqueIdentifier the UUID to classify
     * @return {@code true} if the UUID can only belong to a Bedrock account
     */
    public static boolean isBedrockId(@NotNull UUID uniqueIdentifier) {
        return uniqueIdentifier.getMostSignificantBits() == 0
                && uniqueIdentifier.getLeastSignificantBits() != 0;
    }

    /**
     * Probes whether Floodgate can actually be used from this classloader: the plugin must be
     * present on the server <em>and</em> its API classes must be resolvable. Callers pass the
     * result to the constructor, keeping the decision — and any logging about it — with the code
     * that wires services together.
     *
     * @param plugin any plugin, used to reach the server's plugin manager
     * @return {@code true} if {@link FloodgateBridge} may be used
     */
    public static boolean isFloodgateAvailable(@NotNull Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("floodgate") == null) return false;
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi", false,
                    BedrockProfileServiceImpl.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError apiNotLoadable) {
            return false;
        }
    }

    /**
     * Whether a name carries the Bedrock prefix (and at least one character behind it), making it
     * Bedrock by construction — Mojang has nothing to say about it. The universal service uses
     * this to decide that the Java fallback is pointless.
     *
     * @param name the name as the caller typed it
     * @return {@code true} if the name can only be a Bedrock display name
     */
    public boolean isPrefixedBedrockName(@NotNull String name) {
        String prefix = bedrockPrefix();
        return name.startsWith(prefix) && name.length() > prefix.length();
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<String>> getName(final @NotNull UUID uniqueIdentifier) {
        Objects.requireNonNull(uniqueIdentifier, "uniqueIdentifier");
        if (!isBedrockId(uniqueIdentifier)) return CompletableFuture.completedFuture(Optional.empty());

        String cached = cache.getUniqueIdentifierToNameMap().get(uniqueIdentifier);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        long xuid = uniqueIdentifier.getLeastSignificantBits();
        return resolveGamertag(xuid).thenApply(gamertag -> {
            try {
                if (gamertag == null) return Optional.<String>empty();
                // Prefix + gamertag is the name the player carries in game.
                String name = bedrockPrefix() + gamertag;
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

        // Normalise to the display name: a prefixed name is stripped to its gamertag for the
        // lookup, a bare gamertag gains the prefix for the cache, so both spellings land on the
        // same entry.
        String prefix = bedrockPrefix();
        String displayName;
        String gamertag;
        if (name.startsWith(prefix) && name.length() > prefix.length()) {
            displayName = name;
            gamertag = name.substring(prefix.length());
        } else {
            displayName = prefix + name;
            gamertag = name;
        }

        UUID cached = cache.getNameToUniqueIdentifierMap().get(displayName.toLowerCase(Locale.ROOT));
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        return resolveXuid(gamertag).thenApply(xuid -> {
            try {
                if (xuid == null) return Optional.<UUID>empty();
                UUID bedrockId = new UUID(0L, xuid);
                cache.rememberName(bedrockId, displayName);
                return Optional.of(bedrockId);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve UUID for name " + name, e);
                return Optional.<UUID>empty();
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<@NotNull Optional<SkinDto>> getSkin(final @NotNull UUID uniqueIdentifier) {
        Objects.requireNonNull(uniqueIdentifier, "uniqueIdentifier");
        if (!isBedrockId(uniqueIdentifier)) return CompletableFuture.completedFuture(Optional.empty());

        SkinDto cached = cache.getUniqueIdentifierToSkinMap().get(uniqueIdentifier);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        long xuid = uniqueIdentifier.getLeastSignificantBits();
        return getJson(GLOBAL_API + "/v2/skin/" + xuid).thenApply(json -> {
            try {
                // No skin stored is an empty object, not an error: the player never joined a
                // Geyser server with their current skin.
                if (json == null || !json.has("value") || !json.has("signature")) {
                    return Optional.<SkinDto>empty();
                }
                SkinDto skin = SkinDto.of(json.get("value").getAsString(), json.get("signature").getAsString());
                cache.rememberSkin(uniqueIdentifier, skin);
                return Optional.of(skin);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to retrieve skin for UUID " + uniqueIdentifier, e);
                return Optional.<SkinDto>empty();
            }
        });
    }

    /**
     * The gamertag of a XUID: Floodgate's own async helper when the plugin is installed, the
     * Global API directly when it is not.
     */
    private @NotNull CompletableFuture<@Nullable String> resolveGamertag(long xuid) {
        if (floodgateAvailable) {
            return FloodgateBridge.gamertagFor(xuid);
        }
        return getJson(GLOBAL_API + "/v2/xbox/gamertag/" + xuid)
                .thenApply(json -> json != null && json.has("gamertag") ? json.get("gamertag").getAsString() : null);
    }

    /**
     * The XUID of a gamertag: Floodgate when installed, the Global API otherwise.
     */
    private @NotNull CompletableFuture<@Nullable Long> resolveXuid(@NotNull String gamertag) {
        if (floodgateAvailable) {
            return FloodgateBridge.xuidFor(gamertag);
        }
        return getJson(GLOBAL_API + "/v2/xbox/xuid/" + ProfileJsonHttp.encodeSegment(gamertag))
                .thenApply(json -> json != null && json.has("xuid") ? Long.valueOf(json.get("xuid").getAsLong()) : null);
    }

    private @NotNull String bedrockPrefix() {
        return floodgateAvailable ? FloodgateBridge.prefix() : FALLBACK_PREFIX;
    }

    private @NotNull CompletableFuture<@Nullable JsonObject> getJson(@NotNull String url) {
        return ProfileJsonHttp.getJson(httpClient, executor, url, userAgent, plugin.getLogger());
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
        return "BedrockProfileService_" + plugin.getName();
    }

    @Override
    public @NotNull Component getName() {
        return Component.text(getServiceName());
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Bedrock-edition profile lookups (gamertags, Floodgate UUIDs, signed"
                + " skins) for " + plugin.getName() + ", via Floodgate or the Geyser Global API,"
                + " backed by the shared profile cache.");
    }

    /**
     * The only code that touches Floodgate types. Nested so those classes only load when the
     * {@link #isFloodgateAvailable} probe passed — referencing {@link FloodgateApi} from the outer
     * class body would risk a {@link NoClassDefFoundError} on servers without the plugin.
     */
    private static final class FloodgateBridge {

        private FloodgateBridge() {
        }

        static @NotNull String prefix() {
            return FloodgateApi.getInstance().getPlayerPrefix();
        }

        static @NotNull CompletableFuture<@Nullable String> gamertagFor(long xuid) {
            return FloodgateApi.getInstance().getGamertagFor(xuid)
                    .exceptionally(_ -> null);
        }

        static @NotNull CompletableFuture<@Nullable Long> xuidFor(@NotNull String gamertag) {
            return FloodgateApi.getInstance().getXuidFor(gamertag)
                    .exceptionally(_ -> null);
        }
    }
}
