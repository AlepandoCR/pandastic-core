package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

/**
 * A record {@link Dto} shaped exactly like the {@code SkinDto} the adapter used to special-case
 * with a hardcoded reflective branch.
 * <p>
 * Its whole purpose is to be unremarkable: the bridge has never heard of it, there is no name list
 * to add it to and no per-type code anywhere, yet it must cross the classloader boundary. If this
 * fixture transfers, so does any future DTO.
 */
public record BridgeSkinDtoFixture(String value, String signature) implements Dto {
}
