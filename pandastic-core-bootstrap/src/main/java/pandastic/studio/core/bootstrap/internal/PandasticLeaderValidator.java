package pandastic.studio.core.bootstrap.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeTypeContract;
import pandastic.studio.core.service.api.PandasticCore;

import java.lang.reflect.Modifier;

/**
 * Decides whether an object read out of the shared anchor may be invoked through the bridge.
 * <h2>The threat</h2>
 * The anchor lives in {@link System#getProperties()}, so anything running in the JVM can write to
 * it. Without this check, a follower would resolve a method on whatever object it found under the
 * leader slot and invoke it through a {@link java.lang.invoke.MethodHandle}. A hostile object only
 * needs public methods with the right names and arities — it does not need to implement anything —
 * to receive every bridged call from every plugin in the process, including the arguments.
 * <h2>Every published slot, not just the leader</h2>
 * The leader is not the only object the anchor publishes: the leader's service manager is cached in
 * its own slot so followers do not have to fetch it through the bridge on every call, and that slot
 * is in the same world-writable property. {@link #isTrustedBridgeTarget} is therefore the general
 * form of this check and {@link #isTrustedLeader} is one application of it. Anything read out of
 * the anchor and used as an invocation target must go through it, or the leader check is simply
 * bypassed by aiming one slot to the left.
 * <h2>The check</h2>
 * A candidate is trusted only when all of these hold:
 * <ul>
 *     <li>its class is {@code public} — the bridge invokes nothing non-public anyway
 *     (see {@link PandasticBootstrapReflection#unreflectPublic}), so a non-public leader is either
 *     misconfigured or probing;</li>
 *     <li>its class name lies under this library's package prefix;</li>
 *     <li>it declares, transitively, an interface whose fully-qualified name is exactly
 *     {@link PandasticCore}'s.</li>
 * </ul>
 * Name-based identity is required rather than {@code instanceof}: the leader's {@code PandasticCore} is a
 * different {@link Class} object from this copy's, so {@code instanceof} is {@code false} for every
 * legitimate leader. That is also exactly why the check cannot be stronger.
 * <h2>Residual risk, stated plainly</h2>
 * An attacker who can write to system properties can also place an object that genuinely implements
 * an interface named {@code PandasticCore}, and it will pass. There is no way to tell that object from a
 * legitimate leader in another classloader, because the only identity the two classloaders share
 * <em>is</em> the name. What this class buys is real but bounded: the trivially cheap attack —
 * dropping in a duck-typed object, or any ordinary object, and harvesting bridged calls — stops
 * working, and the failure is loud and re-elects a known-good leader instead of being silent.
 * Defending the rest requires controlling what is installed in the server.
 */
public final class PandasticLeaderValidator {

    /**
     * The interface name a leader must implement, taken from this copy's own {@link PandasticCore} so it
     * cannot drift from the type the bridge actually invokes.
     */
    private static final String LEADER_INTERFACE_FQN = PandasticCore.class.getName();

    private PandasticLeaderValidator() {
    }

    /**
     * Whether {@code candidate} may be invoked as the leader.
     *
     * @param candidate an object read from the anchor, possibly {@code null}
     */
    public static boolean isTrustedLeader(@Nullable Object candidate) {
        return isTrustedBridgeTarget(candidate, LEADER_INTERFACE_FQN);
    }

    /**
     * Whether {@code candidate} may be used as the foreign target of bridged calls against the
     * interface named {@code requiredInterfaceFqn}.
     * <p>
     * This is the general form of {@link #isTrustedLeader} and applies to every object read out of
     * the anchor, because every such object becomes a reflective invocation target.
     *
     * @param candidate            an object read from the anchor, possibly {@code null}
     * @param requiredInterfaceFqn fully-qualified name of the interface the target must declare,
     *                             taken from the caller's own copy of that type so it cannot drift
     *                             from the type whose methods will actually be invoked
     */
    public static boolean isTrustedBridgeTarget(@Nullable Object candidate, @NotNull String requiredInterfaceFqn) {
        if (candidate == null) return false;

        Class<?> candidateType = candidate.getClass();
        if (!Modifier.isPublic(candidateType.getModifiers())) return false;
        if (!BridgeTypeContract.isLibraryType(candidateType)) return false;
        return BridgeTypeContract.implementsByName(candidateType, requiredInterfaceFqn);
    }

    /**
     * Human-readable reason a candidate was rejected as the leader, for the log line that
     * accompanies a re-election.
     */
    public static @NotNull String describeRejection(@Nullable Object candidate) {
        if (candidate == null) return "the leader slot was empty";
        return describeRejection(candidate, LEADER_INTERFACE_FQN);
    }

    /**
     * Human-readable reason a candidate was rejected as a bridge target.
     */
    public static @NotNull String describeRejection(@Nullable Object candidate,
                                                    @NotNull String requiredInterfaceFqn) {
        if (candidate == null) return "the slot was empty";

        Class<?> candidateType = candidate.getClass();
        if (!Modifier.isPublic(candidateType.getModifiers())) {
            return candidateType.getName() + " is not a public type";
        }
        if (!BridgeTypeContract.isLibraryType(candidateType)) {
            return candidateType.getName() + " is not a " + BridgeTypeContract.LIBRARY_PACKAGE_PREFIX + "* type";
        }
        if (!BridgeTypeContract.implementsByName(candidateType, requiredInterfaceFqn)) {
            return candidateType.getName() + " does not implement " + requiredInterfaceFqn;
        }
        return candidateType.getName() + " is trusted";
    }

    /**
     * Whether the leader object agrees with the class name recorded alongside it when it was
     * published.
     * <p>
     * A mismatch means the leader slot and its metadata were written by different parties, which is
     * a tampering signal rather than an ordinary race: publication happens under the anchor lock
     * and writes both in one go.
     *
     * @param candidate         the object in the leader slot
     * @param recordedClassName the value of the recorded leader class slot, if any
     */
    public static boolean matchesRecordedClass(@NotNull Object candidate, @Nullable Object recordedClassName) {
        if (recordedClassName == null) return true;
        return candidate.getClass().getName().equals(String.valueOf(recordedClassName));
    }
}
