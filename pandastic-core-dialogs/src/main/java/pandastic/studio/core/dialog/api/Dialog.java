package pandastic.studio.core.dialog.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Dialog {
    /**
     * Whether the dialog can be closed with the ESCAPE key.
     */
    boolean canBeClosedWithEscape() default true;
}
