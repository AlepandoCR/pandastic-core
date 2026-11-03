package pandastic.studio.core.bootstrap.internal;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JVM-wide registry of the follower bridges currently attached to the leader.
 * <p>
 * Two things depend on it: diagnostics ("are these two plugins really sharing one runtime?") and
 * the leader's shutdown decision, which may only tear the shared runtime down once no follower is
 * left holding a bridge onto it.
 * <h2>Everything here is weak, including the keys</h2>
 * The registry lives in a JVM-global map, so every reference it holds outlives every plugin unless
 * something explicitly removes it. A follower that is disabled without calling {@code shutdown()} —
 * a crash, a failed {@code onDisable}, a {@code /reload} — never removes itself, so the registry
 * has to be able to forget it on its own.
 * <p>
 * It is therefore keyed by <b>plugin name</b> (a {@link String}, which pins nothing) and holds a
 * {@link WeakReference} to the {@link Plugin}. An earlier shape, a map from {@code Plugin} to a
 * {@code WeakReference} of the follower's bridge, could never forget anything: the key was strong,
 * so the plugin was pinned; every plugin keeps its bridge in a field, so the weakly-held bridge was
 * reachable from the strongly-held key; and the pruning condition — "the bridge was collected" —
 * was therefore unreachable by construction. The consequences were a permanent classloader leak
 * per reload <em>and</em> a follower count that never fell to zero, so the leader could never
 * release the shared thread pool and HTTP client and each reload started another pair.
 * <p>
 * Tracking the plugin rather than the bridge is also the better signal: a plugin that is still
 * loaded but has dropped its bridge is counted, which is conservative in the safe direction (the
 * shared runtime stays up), while a plugin whose classloader is gone is definitively detached.
 * <h2>The slot is untrusted input</h2>
 * Like every anchor slot this one is world-writable, and it is read on the bootstrap path of every
 * plugin in the JVM. A hostile or colliding value must therefore degrade to "no followers" rather
 * than throw a {@link ClassCastException} into unrelated plugins' startup, which is what an
 * unchecked cast over a foreign map would do.
 */
public final class PandasticApiRegistry {

    /**
     * Anchor slot holding the follower map.
     */
    public static final String FOLLOWERS_KEY = GeneratedBridgeContract.AnchorKeys.FOLLOWERS;

    /**
     * Anchor slot holding each follower's join timestamp, keyed by plugin name. The earliest live
     * follower is the designated successor when the leader's plugin is unloaded. Values come from
     * {@link System#nanoTime()}, which is monotonic within one JVM — the only place this map is
     * ever compared.
     */
    public static final String FOLLOWER_SEQUENCE_KEY = GeneratedBridgeContract.AnchorKeys.FOLLOWER_SEQUENCE;

    private PandasticApiRegistry() {
    }

    /**
     * The follower map, creating or replacing it if what is there cannot be used.
     * <p>
     * Only ever called by a writer: a reader uses {@link #readFollowers} so that a poisoned slot
     * is not silently repaired by a read.
     */
    private static @NotNull ConcurrentMap<String, Object> writableFollowers(@NotNull Map<String, Object> anchor) {
        ConcurrentMap<String, Object> existing = readFollowers(anchor);
        if (existing != null) return existing;

        ConcurrentMap<String, Object> created = new ConcurrentHashMap<>();
        anchor.put(FOLLOWERS_KEY, created);
        return created;
    }

    /**
     * The follower map if the slot holds something usable, {@code null} otherwise.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable ConcurrentMap<String, Object> readFollowers(@NotNull Map<String, Object> anchor) {
        Object stored = anchor.get(FOLLOWERS_KEY);
        // A plain Map cannot be shared safely across plugins, and a non-map is interference or a
        // key collision. Either way nothing in it can be believed.
        if (stored instanceof ConcurrentMap<?, ?> concurrent) return (ConcurrentMap<String, Object>) concurrent;
        if (stored != null) {
            PandasticCoreLog.warn("The anchor's follower slot holds a " + stored.getClass().getName()
                    + " rather than a ConcurrentMap; ignoring it. The anchor lives in a world-writable"
                    + " system property, so this is either a key collision or deliberate interference.");
        }
        return null;
    }

    /**
     * Records that {@code plugin} holds a follower bridge onto the leader.
     *
     * @param anchor the shared anchor map
     * @param plugin the plugin that attached
     */
    public static void registerFollower(@NotNull Map<String, Object> anchor, @NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        writableFollowers(anchor).put(plugin.getName(), new WeakReference<>(plugin));
        // putIfAbsent: a plugin that re-initialises (or reloads under the same name while still
        // registered) keeps its original seniority rather than moving to the back of the line.
        sequences(anchor).putIfAbsent(plugin.getName(), System.nanoTime());
    }

    /**
     * Detaches {@code plugin}. Safe to call for a plugin that was never registered.
     */
    public static void unregisterFollower(@NotNull Map<String, Object> anchor, @NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        ConcurrentMap<String, Object> followers = readFollowers(anchor);
        if (followers != null) followers.remove(plugin.getName());
        sequences(anchor).remove(plugin.getName());
    }

    /**
     * The live follower that should take over when the leader's plugin is unloaded: the earliest
     * joiner, by the recorded join timestamp, with the plugin name as a deterministic tie-break. A
     * follower registered by an older library copy that never wrote a timestamp sorts last rather
     * than being skipped.
     *
     * @param anchor               the shared anchor map
     * @param excludedPluginName   plugin to ignore, typically the one being disabled
     * @return the designated successor's plugin name, or {@code null} when no live follower remains
     */
    public static @Nullable String designatedSuccessor(@NotNull Map<String, Object> anchor,
                                                       @NotNull String excludedPluginName) {
        ConcurrentMap<String, Object> sequence = sequences(anchor);

        String designated = null;
        long designatedJoin = Long.MAX_VALUE;
        for (Plugin follower : followers(anchor)) {
            String name = follower.getName();
            if (name.equals(excludedPluginName)) continue;

            long join = sequence.get(name) instanceof Long recorded ? recorded : Long.MAX_VALUE;
            boolean earlier = join < designatedJoin
                    || (join == designatedJoin && (designated == null || name.compareTo(designated) < 0));
            if (earlier) {
                designated = name;
                designatedJoin = join;
            }
        }
        return designated;
    }

    /**
     * The join-sequence map, created on demand. Shape-checked like every anchor slot: a poisoned
     * value degrades to an empty map (every follower then sorts equal and the name decides), never
     * to an exception on another plugin's disable path.
     */
    @SuppressWarnings("unchecked")
    private static @NotNull ConcurrentMap<String, Object> sequences(@NotNull Map<String, Object> anchor) {
        Object stored = anchor.get(FOLLOWER_SEQUENCE_KEY);
        if (stored instanceof ConcurrentMap<?, ?> concurrent) return (ConcurrentMap<String, Object>) concurrent;
        if (stored != null) {
            PandasticCoreLog.warn("The anchor's follower-sequence slot holds a " + stored.getClass().getName()
                    + " rather than a ConcurrentMap; replacing it. Successor order resets to name order.");
        }
        ConcurrentMap<String, Object> created = new ConcurrentHashMap<>();
        anchor.put(FOLLOWER_SEQUENCE_KEY, created);
        return created;
    }

    /**
     * Number of live followers, after pruning collected ones.
     */
    public static int followerCount(@NotNull Map<String, Object> anchor) {
        return followers(anchor).size();
    }

    /**
     * The plugins currently holding a live follower bridge, pruning collected and unreadable
     * entries on the way.
     */
    public static @NotNull Set<Plugin> followers(@NotNull Map<String, Object> anchor) {
        ConcurrentMap<String, Object> followers = readFollowers(anchor);
        if (followers == null) return Set.of();

        Set<Plugin> live = new HashSet<>();
        for (Iterator<Map.Entry<String, Object>> it = followers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            // Each value is checked rather than cast: the map is reachable from a world-writable
            // property, and one surprising entry must not break every caller's bootstrap.
            if (!(entry.getValue() instanceof Reference<?> reference)) {
                it.remove();
                continue;
            }
            if (!(reference.get() instanceof Plugin plugin)) {
                // Either collected (the plugin is gone) or never a Plugin to begin with.
                it.remove();
                continue;
            }
            live.add(plugin);
        }
        return Set.copyOf(live);
    }
}
