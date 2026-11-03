package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

import java.util.UUID;

/**
 * A record {@link Dto} whose components exercise every case the record path has to handle at once:
 * a parent-visible value ({@link UUID}), a nested {@link Dto} that must recurse, and a library
 * enum that must be mapped by constant name rather than by reference.
 *
 * @param id    parent-visible, so it crosses unchanged and stays {@code equals} on both sides
 * @param skin  a nested Dto, re-created recursively as the receiving side's own type
 * @param rarity a library enum, re-resolved to the receiving side's constant
 */
public record BridgeProfileDtoFixture(UUID id, BridgeSkinDtoFixture skin, BridgeRarityFixture rarity) implements Dto {
}
