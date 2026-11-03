package pandastic.studio.core.database.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a DAO getter method as the primary key column.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PrimaryKey {

    /** @return true to auto-increment when the type is numeric. */
    boolean autoIncrement() default false;
}

