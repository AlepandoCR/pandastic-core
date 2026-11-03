package pandastic.studio.core.bootstrap.api.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a String constant as a shared JVM anchor key.
 * <p>
 * This annotation is intended for build-time contract generation.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface BridgeAnchorKey {

    BridgeStability stability() default BridgeStability.STABLE_ID;

    /**
     * Optional logical id for the key (distinct from the actual String value).
     */
    String id() default "";
}

