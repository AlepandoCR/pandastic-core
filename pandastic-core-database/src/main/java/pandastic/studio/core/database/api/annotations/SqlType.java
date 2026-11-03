package pandastic.studio.core.database.api.annotations;

/**
 * Enumerates common MySQL column types.
 * <p>
 * This is intended to be used as a safer alternative to free-form SQL strings in annotations.
 * When {@link Column#type()} is set to {@link SqlType#CUSTOM}, {@link Column#sqlType()} must be provided.
 */
public enum SqlType {
    // Numeric
    TINYINT,
    SMALLINT,
    MEDIUMINT,
    INT,
    BIGINT,
    DECIMAL,
    NUMERIC,
    FLOAT,
    DOUBLE,
    REAL,
    BIT,
    BOOLEAN,

    // Date & time
    DATE,
    TIME,
    DATETIME,
    TIMESTAMP,
    YEAR,

    // Character & binary strings
    CHAR,
    VARCHAR,
    TINYTEXT,
    TEXT,
    MEDIUMTEXT,
    LONGTEXT,

    BINARY,
    VARBINARY,
    TINYBLOB,
    BLOB,
    MEDIUMBLOB,
    LONGBLOB,

    // JSON
    JSON,

    // Spatial
    GEOMETRY,
    POINT,
    LINESTRING,
    POLYGON,
    MULTIPOINT,
    MULTILINESTRING,
    MULTIPOLYGON,
    GEOMETRYCOLLECTION,

    // Misc
    ENUM,
    SET,

    /** Use {@link Column#sqlType()} for an explicit type (e.g. VARCHAR(36), DECIMAL(10,2)). */
    CUSTOM
}

