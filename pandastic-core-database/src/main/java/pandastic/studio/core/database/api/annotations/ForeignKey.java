package pandastic.studio.core.database.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a foreign key constraint for a DAO column.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ForeignKey {

    /** @return referenced DAO type (used to discover the referenced table name). */
    Class<?> referencesDao();

    /** @return referenced column name on the target table. */
    String referencesColumn() default "id";

    /** @return constraint name (optional). */
    String name() default "";

    /** @return ON DELETE rule. */
    OnDelete onDelete() default OnDelete.RESTRICT;

    /** @return ON UPDATE rule. */
    OnUpdate onUpdate() default OnUpdate.RESTRICT;
}

