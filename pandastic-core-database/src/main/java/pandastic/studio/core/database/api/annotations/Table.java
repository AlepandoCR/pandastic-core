package pandastic.studio.core.database.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the physical table name for a DAO-backed table.
 * <p>
 * This annotation marks a DAO interface as a concrete table. The annotation processor will generate:
 * <ul>
 *   <li>An implementation class for the DAO interface</li>
 *   <li>A repository class for CRUD operations</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {

    /** @return the table name in MySQL. */
    String value();
}
