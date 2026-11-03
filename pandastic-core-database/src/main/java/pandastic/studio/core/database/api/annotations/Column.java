package pandastic.studio.core.database.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a MySQL column for a DAO method.
 * <p>
 * The annotated method must be a no-arg getter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Column {

    /** @return column name (defaults to derived name from the getter when empty). */
    String name() default "";

    /**
     * @return the logical SQL type.
     * Use {@link SqlType#CUSTOM} together with {@link #sqlType()} when you need a parameterized type
     * such as {@code VARCHAR(36)} or {@code DECIMAL(10,2)}.
     */
    SqlType type() default SqlType.CUSTOM;

    /**
     * @return explicit SQL type override (e.g. VARCHAR(36), DECIMAL(10,2)).
     * Only used when {@link #type()} is {@link SqlType#CUSTOM}.
     */
    String sqlType() default "";

    /**
     * Optional length/size parameter for certain types (e.g. {@code VARCHAR(36)}, {@code CHAR(16)}).
     * <p>
     * If {@link #type()} is {@link SqlType#VARCHAR} or {@link SqlType#CHAR} and {@code length > 0},
     * the generated SQL type will be {@code VARCHAR(length)} / {@code CHAR(length)}.
     * <p>
     * For more complex parameterized types (e.g. {@code DECIMAL(10,2)}), use {@link SqlType#CUSTOM}
     * with {@link #sqlType()}.
     */
    int length() default -1;

    /** @return nullable flag for DDL. */
    boolean nullable() default true;

    /** @return unique constraint. */
    boolean unique() default false;

    /** @return default SQL expression (e.g. CURRENT_TIMESTAMP). */
    String defaultExpr() default "";
}
