package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * A gadget that satisfies every <em>structural</em> rule the deserialization allowlist can state:
 * it sits under the library package prefix, it implements {@link Dto}, and it runs code from
 * {@code readObject}.
 * <p>
 * It exists to make the point that "is it shaped like one of ours?" cannot be the whole answer.
 * Adding {@code implements Dto} is one line of attacker effort, and the prefix comes free to
 * anyone who can name a package. The only remaining evidence that this class is not code the
 * receiving plugin chose to ship is <b>which classloader defined it</b> — see
 * {@link ForeignPluginClassLoader}.
 */
public final class BridgeHostileDtoGadgetFixture implements Dto {

    private static final long serialVersionUID = 1L;

    /**
     * Set by {@link #readObject}, per classloader, so a test can assert on the copy that would
     * actually have executed.
     */
    public static volatile boolean detonated;

    public BridgeHostileDtoGadgetFixture() {
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        detonated = true;
    }
}
