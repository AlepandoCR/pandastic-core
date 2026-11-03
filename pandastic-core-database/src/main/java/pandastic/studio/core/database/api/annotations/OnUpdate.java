package pandastic.studio.core.database.api.annotations;

/**
 * Foreign key ON UPDATE action.
 */
public enum OnUpdate {
    RESTRICT,
    CASCADE,
    SET_NULL,
    NO_ACTION
}

