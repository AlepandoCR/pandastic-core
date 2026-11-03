package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * A {@link Serializable} class that sits under this library's package prefix, is <b>not</b> a
 * {@link Dto}, and runs code from {@code readObject}.
 * <p>
 * This is the shape that makes a package-prefix allowlist unsafe. The prefix is not a credential:
 * any plugin can ship a class named {@code pandastic.studio.core.anything} in its own jar, and on a Paper
 * server one plugin's classloader can resolve another plugin's classes. A filter that admits a
 * class because of its <em>name</em> therefore admits attacker-authored bytecode, and
 * {@code readObject} is called on it before the receiving side sees any value at all.
 * <p>
 * {@link #detonated} records whether that happened, per classloader, so a test can tell the
 * difference between "rejected" and "rejected after it already ran".
 */
public final class BridgeGadgetFixture implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Set by {@link #readObject}. Static, so each classloader's copy has its own flag and the test
     * can assert on the copy that would actually have executed.
     */
    public static volatile boolean detonated;

    public BridgeGadgetFixture() {
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        detonated = true;
    }
}
