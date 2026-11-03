package pandastic.studio.core.bootstrap.api.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional human-friendly key for overloaded methods.
 * <p>
 * If present, the generated id can incorporate this key instead of raw parameter type names.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface BridgeOverloadKey {

    String value();
}

