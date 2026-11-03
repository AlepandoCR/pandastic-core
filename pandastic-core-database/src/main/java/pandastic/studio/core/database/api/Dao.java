package pandastic.studio.core.database.api;

/**
 * Marker interface for all Data Access Object interfaces.
 * <p>
 * Any interface that represents a table (or part of a table via inheritance) should extend this interface.
 * This allows type-safe handling of DAOs throughout the ORM infrastructure.
 * <p>
 * Example usage:
 *
 * <pre>
 * public interface UserDao extends Dao {
 *     &#64;Column(name = "id", type = SqlType.INT)
 *     &#64;PrimaryKey(autoIncrement = true)
 *     int getId();
 *
 *     &#64;Column(name = "name", type = SqlType.VARCHAR, length = 255)
 *     String getName();
 * }
 * </pre>
 */
public interface Dao {
    // Marker interface - no methods required
}

