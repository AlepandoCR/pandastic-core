package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.util.api.data.Dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A declared surface mixing legal and illegal bridged signatures, so the contract predicate can be
 * checked against real {@link java.lang.reflect.Type}s — including generic arguments, which are
 * where an unbridgeable type is easiest to hide.
 */
public interface BridgeSurfaceFixture {

    /**
     * Legal: a JDK container of a library interface.
     */
    List<PandasticService> safeServices();

    /**
     * Legal: a {@link Dto}, which crosses by value.
     */
    BridgeSkinDtoFixture safeDto();

    /**
     * Legal: parent-visible types throughout.
     */
    Map<UUID, String> safeJdkTypes();

    /**
     * Illegal: a library class that is neither an interface nor a Dto.
     */
    BridgeUnsafeValueFixture unsafeLibraryClass();

    /**
     * Illegal, and the easy one to miss: the raw type is a JDK {@link List}, and only the type
     * argument is unbridgeable.
     */
    List<BridgeUnsafeValueFixture> unsafeGenericArgument();

    /**
     * Illegal: a library enum is a class, so a follower cannot hold the leader's constants.
     */
    BridgeRarityFixture unsafeLibraryEnum();
}
