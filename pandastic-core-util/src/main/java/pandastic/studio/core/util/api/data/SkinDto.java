package pandastic.studio.core.util.api.data;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.util.internal.data.SkinDtoImpl;

/**
 * A Mojang-signed skin: the Base64 {@code textures} property value and its signature.
 *
 * <p>Extends {@link Dto} so instances cross the classloader boundary <b>by value</b>: the
 * receiving side rebuilds its own copy through the record path instead of holding a proxy
 * over the sender's object. The canonical implementation is a record, so no serialization
 * is ever involved.
 */
public interface SkinDto extends Dto {

    /**
     * The Base64-encoded {@code textures} property value.
     *
     * @return the signed texture payload
     */
    @NotNull String value();

    /**
     * Mojang's signature over {@link #value()}.
     *
     * @return the signature, as served by the session API
     */
    @NotNull String signature();

    /**
     * Creates a skin value.
     *
     * @param value     the Base64-encoded {@code textures} property value
     * @param signature Mojang's signature over {@code value}
     * @return a new immutable skin
     */
    @Contract("_, _ -> new")
    static @NotNull SkinDto of(@NotNull String value, @NotNull String signature) {
        return new SkinDtoImpl(value, signature);
    }
}
