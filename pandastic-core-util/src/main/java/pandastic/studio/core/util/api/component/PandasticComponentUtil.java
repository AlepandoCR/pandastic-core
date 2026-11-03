package pandastic.studio.core.util.api.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.UUID;

/**
 * Builders for Adventure's <em>object components</em> — inline player heads and atlas sprites
 * rendered inside chat text.
 *
 * <h2>Player heads come from {@link SkinDto}, not from lookups</h2>
 * A head built from a bare UUID or name makes every viewing client resolve the profile itself.
 * A head built from a {@code textures} profile property renders immediately, offline-capable and
 * identical for every viewer — and that property is exactly the value/signature pair a
 * {@link SkinDto} carries. The intended flow is therefore:
 * {@code profileService.getSkin(uuid)} (cached, shared across plugins) → {@code SkinDto} →
 * {@link #playerHead(SkinDto)}. The profile services expose that composition directly as
 * {@code getPlayerHeadComponent(uuid)}.
 *
 * <h2>Sprites always name their atlas</h2>
 * Adventure offers a single-argument sprite factory that silently assumes the default atlas
 * ({@code minecraft:blocks}). This utility deliberately does not expose it: a sprite is only
 * addressable when both halves are stated, and an implicit atlas is how a GUI icon silently
 * renders as a hole the day the sprite moves. Pass the atlas, always.
 */
public final class PandasticComponentUtil {

    /**
     * Name of the Mojang profile property that carries a skin: the Base64 texture payload plus
     * Mojang's signature over it — the exact pair {@link SkinDto} transports.
     */
    private static final String TEXTURES_PROPERTY = "textures";

    private PandasticComponentUtil() {
    }

    /**
     * A player head rendered purely from a signed skin — no id, no name, no client-side lookup.
     *
     * @param skin the signed skin to render
     * @return an object component showing the head, hat layer included
     */
    @Contract("_ -> new")
    public static @NotNull Component playerHead(@NotNull SkinDto skin) {
        return playerHead(null, null, skin, PlayerHeadObjectContents.DEFAULT_HAT);
    }

    /**
     * A player head with full profile identity and the default hat layer.
     *
     * @param id   the player's unique id, or {@code null} to omit it
     * @param name the player's name, or {@code null} to omit it
     * @param skin the signed skin to render
     * @return an object component showing the head, hat layer included
     */
    @Contract("_, _, _ -> new")
    public static @NotNull Component playerHead(
            @Nullable UUID id,
            @Nullable String name,
            @NotNull SkinDto skin
    ) {
        return playerHead(id, name, skin, PlayerHeadObjectContents.DEFAULT_HAT);
    }

    /**
     * A player head rendered from a signed skin, optionally carrying the profile identity.
     * <p>
     * The skin property alone decides what the head looks like; {@code id} and {@code name} are
     * profile metadata worth attaching when known. The hat layer is the outer skin layer —
     * disabling it shows the bare head.
     *
     * @param id   the player's unique id, or {@code null} to omit it
     * @param name the player's name, or {@code null} to omit it
     * @param skin the signed skin to render
     * @param hat  whether to render the hat (outer) layer
     * @return an object component showing the head
     */
    @Contract("_, _, _, _ -> new")
    public static @NotNull Component playerHead(
            @Nullable UUID id,
            @Nullable String name,
            @NotNull SkinDto skin,
            boolean hat
    ) {
        PlayerHeadObjectContents.Builder head = ObjectContents.playerHead()
                .profileProperty(PlayerHeadObjectContents.property(
                        TEXTURES_PROPERTY, skin.value(), skin.signature()))
                .hat(hat);
        if (id != null) head.id(id);
        if (name != null) head.name(name);
        return Component.object(head.build());
    }

    /**
     * A player head resolved by the <b>viewing client</b> from a unique id.
     * <p>
     * Prefer the {@link SkinDto} overloads: this form makes every client perform its own profile
     * lookup, renders nothing while that lookup is pending, and shows the default skin when it
     * fails. It exists for the cases where no skin is obtainable server-side.
     *
     * @param id the player's unique id
     * @return an object component showing the head once the client resolves it
     */
    @Contract("_ -> new")
    public static @NotNull Component playerHeadByLookup(@NotNull UUID id) {
        return Component.object(ObjectContents.playerHead(id));
    }

    /**
     * An inline sprite, addressed by atlas <b>and</b> sprite key.
     * <p>
     * There is intentionally no single-argument overload assuming the default atlas; see the
     * class notes.
     *
     * @param atlas  the texture atlas the sprite lives in, e.g. {@code minecraft:gui}
     * @param sprite the sprite inside that atlas
     * @return an object component rendering the sprite inline
     */
    @Contract("_, _ -> new")
    public static @NotNull Component sprite(@NotNull Key atlas, @NotNull Key sprite) {
        return Component.object(ObjectContents.sprite(atlas, sprite));
    }

    /**
     * Generic escape hatch for contents this utility has no dedicated builder for.
     *
     * @param contents prebuilt object contents
     * @return the wrapping component
     */
    @Contract("_ -> new")
    public static @NotNull ObjectComponent object(@NotNull ObjectContents contents) {
        return Component.object(contents);
    }
}
