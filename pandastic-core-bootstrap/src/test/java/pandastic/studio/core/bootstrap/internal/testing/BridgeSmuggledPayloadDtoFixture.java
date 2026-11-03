package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

/**
 * A non-record {@link Dto} with an {@code Object} field, which is how a hostile or careless sender
 * gets an arbitrary class into the serialized stream.
 * <p>
 * The DTO's own type is above suspicion — it is a library class — but the declared field type says
 * nothing about what the stream will actually carry. The filter has to decide on the class it
 * finds, not on the field it was declared under, which is exactly what the rejection test checks.
 */
public final class BridgeSmuggledPayloadDtoFixture implements Dto {

    private static final long serialVersionUID = 1L;

    private final Object payload;

    public BridgeSmuggledPayloadDtoFixture(Object payload) {
        this.payload = payload;
    }

    public Object payload() {
        return payload;
    }
}
