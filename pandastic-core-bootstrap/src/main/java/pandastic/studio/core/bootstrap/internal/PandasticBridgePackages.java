package pandastic.studio.core.bootstrap.internal;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JVM-wide registry of consumer package prefixes whose <b>interfaces</b> may cross the bridge as
 * proxies, exactly like the library's own interfaces.
 * <p>
 * The bridge's proxyability rule ({@code BridgeTypeContract.isProxyableInterfaceType}) is
 * evaluated by whichever copy of the library happens to be <em>receiving</em> a value — a
 * follower's copy when a return value crosses, the leader's copy when an argument does. A
 * registration made on the leader therefore cannot live in any one copy's statics: it has to be
 * readable by every copy, and the only mechanism this library has for that is an anchor slot of
 * parent-visible types. The slot holds a {@code ConcurrentMap<String, Set<String>>} from owning
 * plugin name to prefixes, so that a plugin's registrations can be dropped wholesale when it dies.
 * <h2>Why this is a security boundary, and what bounds it</h2>
 * A registered prefix widens the set of interfaces the bridge will resolve <em>by name</em> in the
 * receiving classloader and bind {@code MethodHandle}s against. It deliberately widens nothing
 * else: no concrete class becomes crossable, no {@code Dto} rule changes, and
 * {@code BridgeTypeContract.isBridgeSafe} is untouched. {@link #validatePrefix(String)} refuses
 * prefixes that overlap the library's own namespace or the platform namespaces, because a
 * wildcard-ish prefix ({@code "org"}, {@code "java"}) would turn the name-based type contract into
 * no contract at all. Every registration is logged at INFO — operator telemetry for a widened
 * boundary — and registrations die with their owning plugin.
 * <h2>The slot is untrusted input</h2>
 * Like every anchor slot this one is world-writable. Reads are shape-checked at every level (map,
 * set, element), and anything unusable degrades to "no trusted prefixes" — the closed, safe
 * default — rather than throwing into whatever plugin happened to be adapting a value. Poison is
 * reported once rather than per read, because {@link #isTrustedPrefixOf(String)} sits on the
 * bridge's value-adaptation path.
 */
public final class PandasticBridgePackages {

    /**
     * Anchor slot holding the trusted-prefix map. Additive slot: see {@code PandasticAnchorKeys}.
     */
    public static final String BRIDGE_PACKAGES_KEY = GeneratedBridgeContract.AnchorKeys.BRIDGE_PACKAGES;

    /**
     * Namespaces a prefix may never equal, contain, or be contained by. The library root is
     * derived from {@link PandasticLibrary#ROOT_PACKAGE} so a repackaging moves the rule with the code;
     * the rest are the platform namespaces every plugin classloader shares — trusting any of them
     * would make parent-owned names "proxyable consumer interfaces", which is a contradiction.
     */
    private static final List<String> RESERVED_NAMESPACES = List.of(
            PandasticLibrary.ROOT_PACKAGE,
            "java", "javax", "jdk", "sun", "com.sun",
            "org.bukkit", "io.papermc", "net.kyori");

    /**
     * Whether a poisoned slot was already reported, so a persistent attacker cannot use the
     * per-crossing read path to flood the log.
     */
    private static volatile boolean poisonReported;

    private PandasticBridgePackages() {
    }

    /**
     * Registers {@code packagePrefix} as trusted on behalf of {@code pluginName}, after
     * validation, and reports it at INFO.
     *
     * @param anchor        the shared anchor map
     * @param pluginName    name of the plugin that owns (and bounds the lifetime of) the prefix
     * @param packagePrefix the package prefix to trust
     * @throws IllegalArgumentException if the prefix fails {@link #validatePrefix(String)}
     */
    public static void register(@NotNull Map<String, Object> anchor,
                                @NotNull String pluginName,
                                @NotNull String packagePrefix) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(pluginName, "pluginName");
        validatePrefix(packagePrefix);

        ConcurrentMap<String, Object> byPlugin = writablePackages(anchor);
        // compute() keeps the keep-or-replace decision atomic per plugin, so two concurrent
        // registrations for the same plugin cannot strand each other's set.
        Object slot = byPlugin.compute(pluginName,
                (key, current) -> current instanceof Set<?> ? current : ConcurrentHashMap.newKeySet());
        // The cast is unchecked but safe for what this class does with it: compute() just
        // guaranteed a Set, this class only ever ADDS Strings, and readers shape-check every
        // element anyway because the world-writable slot may hold foreign elements regardless.
        @SuppressWarnings("unchecked")
        Set<String> prefixes = (Set<String>) slot;
        prefixes.add(packagePrefix);

        // Operator telemetry: this line marks a deliberate widening of the bridge's type trust.
        PandasticCoreLog.info("Plugin '" + pluginName + "' registered trusted bridge package '"
                + packagePrefix + "'. Interfaces under it may now cross the classloader bridge as"
                + " proxies; the trust is dropped when the plugin's services are unregistered.");
    }

    /**
     * Validates a candidate prefix, rejecting anything that is not a plain, multi-segment Java
     * package name safely outside every {@linkplain #RESERVED_NAMESPACES reserved namespace}.
     * <p>
     * Overlap is checked in both directions on segment boundaries: {@code "pandastic.studio"} (an ancestor
     * of the library root) would trust the library's own names, and {@code "org.bukkit.plugin"}
     * (a descendant of a platform root) would trust parent-owned names — both defeat the point of
     * a name-based contract, so both are refused.
     *
     * @param packagePrefix the candidate prefix
     * @throws IllegalArgumentException describing exactly which rule was violated
     */
    public static void validatePrefix(@NotNull String packagePrefix) {
        Objects.requireNonNull(packagePrefix, "packagePrefix");
        if (packagePrefix.isBlank()) {
            throw new IllegalArgumentException("A trusted bridge package prefix must not be blank.");
        }

        String[] segments = packagePrefix.split("\\.", -1);
        if (segments.length < 2) {
            throw new IllegalArgumentException("A trusted bridge package prefix must have at least"
                    + " two segments; '" + packagePrefix + "' is too broad to be a package of one"
                    + " plugin's API.");
        }
        for (String segment : segments) {
            if (!isJavaIdentifier(segment)) {
                throw new IllegalArgumentException("A trusted bridge package prefix must be a"
                        + " dot-separated Java package name; segment '" + segment + "' of '"
                        + packagePrefix + "' is not a valid identifier.");
            }
        }

        for (String reserved : RESERVED_NAMESPACES) {
            if (overlaps(packagePrefix, reserved)) {
                throw new IllegalArgumentException("Refusing to trust bridge package prefix '"
                        + packagePrefix + "': it overlaps the reserved namespace '" + reserved
                        + "'. Trusting the library's own or a platform namespace would defeat the"
                        + " bridge's type contract entirely.");
            }
        }
    }

    /**
     * Whether a class with this fully-qualified name lies under any registered trusted prefix.
     * <p>
     * Matching requires a segment boundary ({@code prefix + "."}), so trusting
     * {@code com.example.market} never trusts {@code com.example.marketplace}. Reads go through
     * the live system-property anchor rather than a parameter because the callers — the bridge's
     * static adaptation code — run in every copy and have nothing else in hand; a failure to read
     * the anchor degrades to {@code false}, the closed default.
     *
     * @param className fully-qualified class name of a candidate interface
     * @return {@code true} if a registered prefix covers it
     */
    public static boolean isTrustedPrefixOf(@NotNull String className) {
        Map<String, Object> anchor;
        try {
            anchor = PandasticJvmAnchor.anchorMap();
        } catch (RuntimeException e) {
            // An unreadable anchor means no registrations can be seen; closed is the safe answer.
            return false;
        }

        Object stored = anchor.get(BRIDGE_PACKAGES_KEY);
        if (stored == null) return false;
        if (!(stored instanceof ConcurrentMap<?, ?> byPlugin)) {
            reportPoisonOnce(stored);
            return false;
        }

        for (Object value : byPlugin.values()) {
            if (!(value instanceof Set<?> prefixes)) {
                reportPoisonOnce(value);
                continue;
            }
            for (Object prefix : prefixes) {
                if (prefix instanceof String trusted
                        && className.length() > trusted.length()
                        && className.charAt(trusted.length()) == '.'
                        && className.startsWith(trusted)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drops every prefix registered on behalf of {@code pluginName}. Safe to call for a plugin
     * that never registered anything; reports at INFO when something was actually dropped,
     * because the drop narrows the boundary the registration line widened.
     *
     * @param anchor     the shared anchor map
     * @param pluginName the plugin whose registrations are removed
     */
    public static void removeAll(@NotNull Map<String, Object> anchor, @NotNull String pluginName) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(pluginName, "pluginName");

        Object stored = anchor.get(BRIDGE_PACKAGES_KEY);
        if (!(stored instanceof ConcurrentMap<?, ?> byPlugin)) return;

        Object removed = byPlugin.remove(pluginName);
        if (removed instanceof Set<?> prefixes && !prefixes.isEmpty()) {
            PandasticCoreLog.info("Dropped " + prefixes.size() + " trusted bridge package prefix(es) of"
                    + " plugin '" + pluginName + "': " + prefixes + ". Its interfaces are no longer"
                    + " proxyable across the bridge.");
        }
    }

    /**
     * The writable prefix map, creating or replacing it when the slot holds nothing usable.
     * Writer-only, like {@code PandasticApiRegistry.writableFollowers}: readers never repair the slot,
     * so a poisoned value stays observable until a legitimate registration replaces it.
     */
    private static @NotNull ConcurrentMap<String, Object> writablePackages(@NotNull Map<String, Object> anchor) {
        Object stored = anchor.get(BRIDGE_PACKAGES_KEY);
        if (stored instanceof ConcurrentMap<?, ?> concurrent) {
            @SuppressWarnings("unchecked")
            ConcurrentMap<String, Object> usable = (ConcurrentMap<String, Object>) concurrent;
            return usable;
        }
        if (stored != null) {
            PandasticCoreLog.warn("The anchor's bridge-packages slot holds a " + stored.getClass().getName()
                    + " rather than a ConcurrentMap; replacing it. The anchor lives in a world-writable"
                    + " system property, so this is either a key collision or deliberate interference.");
        }
        ConcurrentMap<String, Object> created = new ConcurrentHashMap<>();
        anchor.put(BRIDGE_PACKAGES_KEY, created);
        return created;
    }

    private static boolean isJavaIdentifier(@NotNull String segment) {
        if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) return false;
        for (int i = 1; i < segment.length(); i++) {
            if (!Character.isJavaIdentifierPart(segment.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Whether two package names are equal or one contains the other, on segment boundaries.
     */
    private static boolean overlaps(@NotNull String a, @NotNull String b) {
        return a.equals(b) || a.startsWith(b + ".") || b.startsWith(a + ".");
    }

    private static void reportPoisonOnce(@NotNull Object unexpected) {
        if (poisonReported) return;
        poisonReported = true;
        PandasticCoreLog.warn("The anchor's bridge-packages slot contains a " + unexpected.getClass().getName()
                + " where a ConcurrentMap/Set shape was expected; treating it as 'no trusted prefixes'."
                + " The anchor lives in a world-writable system property, so this is either a key"
                + " collision or deliberate interference. Further occurrences are not repeated.");
    }
}
