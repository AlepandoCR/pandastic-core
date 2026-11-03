package pandastic.studio.core.bootstrap.internal.testing;

/**
 * A library enum, used as a record component.
 * <p>
 * An enum is a library <em>class</em>, so it is deliberately rejected on a bridged method
 * signature: a follower could never hold the leader's constants. Inside a value object it is
 * ordinary and useful, which is why the DTO transfer maps it by constant name.
 */
public enum BridgeRarityFixture {
    COMMON,
    RARE,
    LEGENDARY
}
