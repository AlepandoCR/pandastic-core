package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.service.api.PandasticService;

import java.util.List;

/**
 * The duck-typed attack again, aimed at the anchor's <em>service manager</em> slot rather than its
 * leader slot.
 * <p>
 * {@code PandasticLeaderValidator} stops an object like this from being treated as the leader. The
 * service manager is published in a separate, equally world-writable slot, and a follower reads it
 * as an optimisation so it does not have to fetch the manager through the leader on every call.
 * An attacker that writes only that slot leaves a legitimate leader in place and still receives
 * every service lookup and every {@code registerService} from every plugin in the JVM — including
 * the service objects themselves.
 * <p>
 * It implements nothing: public methods with the right names and parameter type <em>names</em> are
 * all a reflective bridge needs, and an attacker that shades this library has those type names
 * available for free.
 */
public final class BridgeHostileServiceManagerFixture {

    /**
     * Records whether any bridged call ever reached this object.
     */
    public static volatile boolean invoked;

    public BridgeHostileServiceManagerFixture() {
    }

    public List<Object> getAllServices() {
        invoked = true;
        return List.of();
    }

    public Object getService(String name) {
        invoked = true;
        return null;
    }

    public void registerService(PandasticService service) {
        invoked = true;
    }
}
