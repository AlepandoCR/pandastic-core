package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.SkinDto;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The declared shapes of the real profile API, so adapter tests can drive
 * {@code BridgeValueAdapter} with exactly the generic {@link java.lang.reflect.Type}s the profile
 * services and their shared cache expose across the bridge — including the
 * {@code CompletableFuture<Optional<SkinDto>>} nesting where an unadapted element used to hide
 * until the caller's {@code get()}.
 */
public interface BridgeProfileCacheSurfaceFixture {

    /**
     * The payload shape of {@code PandasticProfileService#getSkin} after the future completes.
     */
    Optional<SkinDto> optionalSkin();

    /**
     * The full return shape of {@code PandasticProfileService#getSkin}.
     */
    CompletableFuture<Optional<SkinDto>> futureOptionalSkin();

    /**
     * The skin direction of {@code PandasticProfileCache}: values are library Dtos, so the map must
     * cross as a live translating view.
     */
    Map<UUID, SkinDto> uniqueIdentifierToSkinMap();

    /**
     * The UUID-to-name direction of {@code PandasticProfileCache}: parent-visible entries throughout,
     * so the live map itself must cross.
     */
    Map<UUID, String> uniqueIdentifierToNameMap();

    /**
     * The name-to-UUID direction of {@code PandasticProfileCache}: parent-visible entries throughout,
     * so the live map itself must cross.
     */
    Map<String, UUID> nameToUniqueIdentifierMap();
}
