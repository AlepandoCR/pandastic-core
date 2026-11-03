package pandastic.studio.core.bootstrap.api.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as part of the reflection bridge surface.
 * <p>
 * This annotation is intended for build-time contract generation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface BridgeApi {

    /**
     * Stable logical namespace for the API. Prefer an enum to avoid typos.
     */
    BridgeNamespace value();
}

