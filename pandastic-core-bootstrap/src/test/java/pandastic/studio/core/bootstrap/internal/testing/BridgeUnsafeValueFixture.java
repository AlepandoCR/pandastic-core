package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

/**
 * A library class that is neither an interface nor a {@link Dto}.
 * <p>
 * Nothing can be done with this across the boundary: a follower cannot be handed the leader's
 * instance (wrong {@code Class}), cannot be given a proxy (it is not an interface), and cannot be
 * given a copy (it declares no transferable shape). Such a type on a bridged signature is a latent
 * {@link ClassCastException}, so it has to be rejected at the contract level.
 */
public final class BridgeUnsafeValueFixture {

    private final String payload;

    public BridgeUnsafeValueFixture(String payload) {
        this.payload = payload;
    }

    public String payload() {
        return payload;
    }
}
