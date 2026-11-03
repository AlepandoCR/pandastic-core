package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

/**
 * The generic flavour of {@link BridgeOpaquePayloadDtoFixture}: an unbounded type variable
 * component, which <em>erases</em> to {@link Object}.
 * <p>
 * Erasure means the two fixtures are the same shape at runtime — the canonical constructor takes
 * an {@code Object}, the accessor returns one — so the adapter's {@code Object}-position rule has
 * to fire for both. A test using this fixture proves the branch keys on the <em>erased</em>
 * declared type rather than on a literal {@code java.lang.Object} in the source.
 *
 * @param <T> deliberately unbounded, so it erases to {@code Object}
 */
public record BridgeGenericPayloadDtoFixture<T>(T payload) implements Dto {
}
