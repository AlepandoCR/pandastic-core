package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

/**
 * A record {@link Dto} whose single component is declared as {@link Object} — the position the
 * declared-type-driven adapter used to be blind to.
 * <p>
 * {@code canHold(Object.class, x)} is trivially true for any {@code x}, so before the adapter
 * grew its {@code Object}-position branch a foreign library object stored in this component
 * crossed the classloader boundary <em>by reference</em>, bypassing the proxy-or-copy rule and
 * surfacing only later as a cross-classloader {@link ClassCastException} at the receiver's first
 * downcast. The tests built on this fixture pin the replacement rule: what arrives in the
 * component is decided by the payload's <b>runtime</b> shape — foreign Dtos are re-created
 * locally, foreign interface-bearing objects are proxied, foreign plain classes fail at the
 * bridge — while parent-visible and receiver-local payloads keep passing through untouched.
 */
public record BridgeOpaquePayloadDtoFixture(Object payload) implements Dto {
}
