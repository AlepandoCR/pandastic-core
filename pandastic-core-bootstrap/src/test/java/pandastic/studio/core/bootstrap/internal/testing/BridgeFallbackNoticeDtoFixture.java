package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

/**
 * A non-record {@link Dto} used <b>only</b> by the once-per-type fallback-warning test.
 * <p>
 * The warning counter in {@code BridgeDtoTransfer} is JVM-global and monotonic, so the test can
 * only reason about its delta if no other test ever sends this type through the serialization
 * fallback. Do not reuse this fixture elsewhere; {@link BridgeLegacyDtoFixture} and
 * {@link BridgeSmuggledPayloadDtoFixture} exist for general round-trip and filter tests.
 */
public final class BridgeFallbackNoticeDtoFixture implements Dto {

    private static final long serialVersionUID = 1L;

    private final String note;

    public BridgeFallbackNoticeDtoFixture(String note) {
        this.note = note;
    }

    public String note() {
        return note;
    }
}
