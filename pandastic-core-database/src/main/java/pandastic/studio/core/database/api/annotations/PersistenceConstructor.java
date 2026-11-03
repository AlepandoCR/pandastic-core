package pandastic.studio.core.database.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a constructor to be used for object materialization from the database
 * The value array matches the constructor parameters to column names in order
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
public @interface PersistenceConstructor {
    String[] value() default {};
}

