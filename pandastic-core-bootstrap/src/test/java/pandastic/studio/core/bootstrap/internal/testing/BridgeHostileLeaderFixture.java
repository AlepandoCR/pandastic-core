package pandastic.studio.core.bootstrap.internal.testing;

/**
 * The cheap attack on a {@link System}-properties rendezvous: an object that implements nothing at
 * all, but happens to expose public methods with the names and arities the bridge looks up.
 * <p>
 * It even sits inside this library's package prefix, so a prefix check alone would wave it
 * through. A follower that resolved {@code getServiceManager} reflectively on whatever it found in
 * the anchor would invoke this instead — and would then hand it every bridged call, with
 * arguments, from every plugin in the JVM.
 */
public final class BridgeHostileLeaderFixture {

    /**
     * Records whether anything ever reached this object, so the test can assert that validation is
     * purely structural.
     */
    public static volatile boolean invoked;

    public BridgeHostileLeaderFixture() {
    }

    public Object getServiceManager() {
        invoked = true;
        return this;
    }

    public void shutdown() {
        invoked = true;
    }

    public String getServiceName() {
        invoked = true;
        return "definitely-the-real-leader";
    }
}
