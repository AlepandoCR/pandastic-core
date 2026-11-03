package pandastic.studio.core.bootstrap.internal;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The JVM-global rendezvous point every shaded copy of this library meets at.
 * <p>
 * Each plugin classloader holds its own {@link Class} objects for these very types, so no static
 * field can be shared between them. {@link System#getProperties()} can: it is a
 * {@link java.util.Hashtable} held by the bootstrap classloader, it accepts arbitrary {@link Object}
 * values through {@link java.util.Hashtable#put}, and it is reachable from every classloader
 * without any of them having to see the others. The value stored under
 * {@link PandasticLibrary#ANCHOR_PROPERTY_KEY} is a {@link ConcurrentHashMap} that carries the elected
 * leader, the protocol version and the follower registry.
 * <h2>Security: the anchor is untrusted input</h2>
 * A {@code System} property is world-readable and world-writable within the JVM. Any plugin, any
 * library, any script engine running in this process can:
 * <ul>
 *     <li>read the leader object and call anything public on it;</li>
 *     <li>overwrite the leader slot so that every follower's {@link java.lang.invoke.MethodHandle}
 *     calls land on an object of its choosing;</li>
 *     <li>replace the whole map, or the lock, and break the mutual exclusion that election relies
 *     on.</li>
 * </ul>
 * None of that can be prevented by a rendezvous of this shape — that is the price of
 * cross-classloader sharing without a shared parent. What <em>is</em> enforced is that nothing read
 * back out is believed on sight: {@link PandasticBootstrap#readTrustedLeader(Map)} verifies through
 * {@link PandasticLeaderValidator} that a leader really is an instance of this library's own
 * {@code PandasticCore} before a single method is invoked on it, and re-elects when it is not. The
 * residual risk is a hostile object that genuinely implements the contract, which is
 * indistinguishable from a legitimate leader and is therefore a question of which plugins are
 * installed, not of what this class can check.
 * <p>
 * The stored map is also deliberately a plain {@code ConcurrentHashMap} of parent-visible types
 * only: a custom library class here would be unreadable by any other copy.
 */
public final class PandasticJvmAnchor {

    /**
     * {@link System} property key the shared map lives under.
     * <p>
     * Derived from {@link PandasticLibrary#ANCHOR_PROPERTY_KEY} rather than written out as a literal:
     * every copy of a given library version must compute the exact same key or the JVM silently
     * splits into independent leaders, and a literal is the easiest thing in this codebase to
     * rename halfway.
     */
    public static final String ANCHOR_PROPERTY_KEY = PandasticLibrary.ANCHOR_PROPERTY_KEY;

    /**
     * The first anchor map this copy ever observed, for swap detection. Replacing the whole map is
     * the one anchor attack that leaves no trace inside the map itself, so the only way to notice
     * it is to remember what was there. Detection only: the current map is still returned, because
     * following the property keeps every copy on the same (possibly new) rendezvous, whereas
     * clinging to the pinned one would split this copy off on its own.
     */
    private static volatile Object firstSeenMap;

    /**
     * Whether the swap warning already fired, so a persistent attacker cannot flood the log.
     */
    private static volatile boolean swapReported;

    private PandasticJvmAnchor() {
    }

    /**
     * Returns the shared anchor map, creating it if this is the first copy to ask.
     * <p>
     * Synchronises on the {@link Properties} instance itself, which is the only monitor available
     * before the map (and therefore the anchor lock) exists.
     */
    @SuppressWarnings("unchecked")
    public static @NotNull Map<String, Object> anchorMap() {
        Properties properties = System.getProperties();
        synchronized (properties) {
            Object existing = properties.get(ANCHOR_PROPERTY_KEY);

            if (existing instanceof ConcurrentMap<?, ?> concurrentMap) {
                ConcurrentMap<String, Object> typed = (ConcurrentMap<String, Object>) concurrentMap;
                typed.putIfAbsent(GeneratedBridgeContract.AnchorKeys.LOCK, new Object());
                reportIfSwapped(typed);
                return typed;
            }

            if (existing instanceof Map<?, ?> map) {
                // Defensive migration: a non-concurrent map cannot be shared safely.
                PandasticCoreLog.warn("Anchor at " + ANCHOR_PROPERTY_KEY + " was a " + existing.getClass().getName()
                        + " rather than a ConcurrentMap; migrating it to a concurrent copy.");
                ConcurrentMap<String, Object> migrated = new ConcurrentHashMap<>((Map<String, Object>) map);
                migrated.putIfAbsent(GeneratedBridgeContract.AnchorKeys.LOCK, new Object());
                properties.put(ANCHOR_PROPERTY_KEY, migrated);
                return migrated;
            }

            if (existing != null) {
                // Somebody put a non-map under our key. Replacing it is the only option, but it
                // must not happen quietly: it means either a key collision or active interference.
                PandasticCoreLog.warn("Anchor at " + ANCHOR_PROPERTY_KEY + " held a non-map "
                        + existing.getClass().getName() + "; replacing it. Plugins already bridged through"
                        + " the previous value will re-elect a leader.");
            }

            ConcurrentMap<String, Object> created = new ConcurrentHashMap<>();
            created.put(GeneratedBridgeContract.AnchorKeys.LOCK, new Object());
            properties.put(ANCHOR_PROPERTY_KEY, created);
            reportIfSwapped(created);
            return created;
        }
    }

    /**
     * Pins the first map this copy sees and warns — once — if a later read finds a different one.
     * A swapped anchor map means somebody called {@code System.setProperties} or overwrote the
     * property wholesale: there is no legitimate reason for either, so the line is an intrusion
     * signal (see the fail-loud telemetry notes in {@code docs/SECURITY.md} §5.5).
     */
    private static void reportIfSwapped(@NotNull Object current) {
        Object pinned = firstSeenMap;
        if (pinned == null) {
            firstSeenMap = current;
            return;
        }
        if (pinned != current && !swapReported) {
            swapReported = true;
            PandasticCoreLog.warn("The anchor map under " + ANCHOR_PROPERTY_KEY + " is not the object this"
                    + " copy first observed: the system property was replaced wholesale. Following the"
                    + " current map to stay on the shared rendezvous, but nothing legitimate does this"
                    + " — audit the installed plugins. Leaders elected before the swap are orphaned and"
                    + " will be re-elected.");
            firstSeenMap = current;
        }
    }

    /**
     * The monitor every copy of the library synchronises on while reading or publishing leader
     * state. Shared through the anchor, so it is the same object for all of them.
     */
    public static @NotNull Object lock() {
        return anchorMap().computeIfAbsent(GeneratedBridgeContract.AnchorKeys.LOCK, key -> new Object());
    }

    /**
     * Empties the anchor while preserving the lock object.
     * <p>
     * Called when the last participant leaves. The lock has to survive: a plugin that is already
     * blocked on it, or that is about to synchronise on it, must keep seeing the same monitor, or
     * two plugins could elect two leaders at once.
     */
    static void reset() {
        Map<String, Object> anchor = anchorMap();
        Object preservedLock = anchor.get(GeneratedBridgeContract.AnchorKeys.LOCK);
        anchor.clear();
        if (preservedLock != null) {
            anchor.put(GeneratedBridgeContract.AnchorKeys.LOCK, preservedLock);
        }
    }
}
