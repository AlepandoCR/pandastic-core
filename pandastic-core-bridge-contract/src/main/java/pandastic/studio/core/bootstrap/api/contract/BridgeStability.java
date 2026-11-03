package pandastic.studio.core.bootstrap.api.contract;

/**
 * How ids are produced for the bridge contract.
 */
public enum BridgeStability {
    /**
     * The id is explicitly provided and remains stable across renames.
     */
    STABLE_ID,

    /**
     * The id is derived from the method signature.
     */
    DERIVED_ID
}

