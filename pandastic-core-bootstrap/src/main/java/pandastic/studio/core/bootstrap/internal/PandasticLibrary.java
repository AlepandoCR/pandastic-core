package pandastic.studio.core.bootstrap.internal;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.util.api.data.Dto;

/**
 * The library's own identity: the single source of truth for every string that must be
 * <b>byte-identical across all shaded copies of the same library version</b>.
 * <p>
 * Nothing here is a free-floating literal that could drift. {@link #ROOT_PACKAGE} is computed from
 * the packages of classes that actually ship in the jar, so a repackaging (relocation, rename,
 * module split) moves the derived values with the code instead of silently desynchronising from it.
 * <h2>Why derivation, not literals</h2>
 * The rendezvous between a leader and its followers happens through a JVM-global key
 * ({@link #ANCHOR_PROPERTY_KEY}) and a package prefix used to decide which types are
 * "ours" ({@link #PACKAGE_PREFIX}). If those two ever disagree between copies:
 * <ul>
 *     <li>a differing anchor key splits the JVM into two independent leaders, so plugins stop
 *     sharing the runtime they were supposed to share;</li>
 *     <li>a differing package prefix makes one copy treat the other's types as foreign JDK types
 *     and hand them over unadapted, which surfaces as a {@link ClassCastException} much later.</li>
 * </ul>
 * Deriving both from the same computed root makes the two failure modes impossible by construction.
 * <p>
 * A copy that <em>is</em> relocated to a different package deliberately computes a different anchor
 * key and therefore elects its own leader. That is the correct behaviour: a relocated copy has
 * different {@link Class} identities and cannot honour the same reflective contract.
 */
public final class PandasticLibrary {

    /**
     * Classes used to compute {@link #ROOT_PACKAGE}. They are deliberately picked from different
     * library modules so the longest common package prefix is the library root and not one
     * module's package, and they are all guaranteed to be present at runtime wherever the
     * bootstrap itself is present.
     * <p>
     * Source-retention annotation types (the {@code contract} package) are intentionally
     * <em>not</em> used here: a build may legitimately strip them from the shaded jar, which would
     * turn this class' initialisation into a {@link NoClassDefFoundError}.
     */
    private static final Class<?>[] PACKAGE_WITNESSES = {Dto.class, PandasticLibrary.class};

    /**
     * Root package shared by every library module, e.g. {@code pandastic.studio.core}.
     */
    public static final String ROOT_PACKAGE = longestCommonPackagePrefix(PACKAGE_WITNESSES);

    /**
     * {@link #ROOT_PACKAGE} with a trailing dot, for {@code String.startsWith} tests.
     */
    public static final String PACKAGE_PREFIX = ROOT_PACKAGE + ".";

    /**
     * Human-facing product name. The one place this library spells its own name.
     */
    public static final String DISPLAY_NAME = "PandasticCore";

    /**
     * Prefix for every log line this library emits, including a trailing space.
     */
    public static final String LOG_PREFIX = "[" + DISPLAY_NAME + "] ";

    /**
     * Service name reported by the leader implementation.
     */
    public static final String CORE_SERVICE_NAME = DISPLAY_NAME;

    /**
     * Service-name prefix reported by a follower's reflective bridge, which appends the follower
     * plugin name in parentheses so two plugins never report the same service name.
     */
    public static final String BRIDGE_SERVICE_NAME_PREFIX = DISPLAY_NAME + "-Bridge";

    /**
     * Gradle artifact base name, which is also the base name of the packaged metadata resource.
     */
    public static final String ARTIFACT_NAME = "pandastic-core";

    /**
     * Optional classpath resource carrying build metadata, notably a {@code protocolVersion}
     * override. Absent in a normal build.
     */
    public static final String METADATA_RESOURCE = ARTIFACT_NAME + ".properties";

    /**
     * Layout version of the anchor map itself. Bump this when the set of slots in the anchor, or
     * the <em>shape of any slot's value</em>, changes incompatibly, so an old copy and a new copy
     * rendezvous in different maps instead of misreading each other's slots.
     * <p>
     * Version 2: the follower registry changed from {@code Map<Plugin, WeakReference<Object>>} to
     * {@code Map<String, WeakReference<Plugin>>}. The old shape could not forget a dead follower —
     * its keys were strong, so a disabled plugin's classloader was pinned in a JVM-global map for
     * the life of the process and the follower count never reached zero, which is the condition the
     * leader waits for before releasing the shared runtime. A version 1 copy reading a version 2
     * map would misread every entry, so the two must not meet.
     */
    public static final int ANCHOR_LAYOUT_VERSION = 2;

    /**
     * Key under which the shared anchor map is published in {@link System#getProperties()}.
     */
    public static final String ANCHOR_PROPERTY_KEY = ROOT_PACKAGE + ".anchor.v" + ANCHOR_LAYOUT_VERSION;

    private PandasticLibrary() {
    }

    /**
     * Builds the service name a follower bridge reports for itself.
     *
     * @param followerPluginName name of the plugin that owns the follower bridge
     */
    public static @NotNull String bridgeServiceName(@NotNull String followerPluginName) {
        return BRIDGE_SERVICE_NAME_PREFIX + "(" + followerPluginName + ")";
    }

    /**
     * Longest prefix shared by the given classes' packages, truncated at a package-segment
     * boundary so the result is always a real package name.
     *
     * @throws IllegalStateException if the witnesses share no package segment, which would mean
     *                               the library has been split across unrelated roots and no
     *                               single prefix can identify it any more
     */
    private static @NotNull String longestCommonPackagePrefix(Class<?> @NotNull [] witnesses) {
        String[][] segments = new String[witnesses.length][];
        for (int i = 0; i < witnesses.length; i++) {
            segments[i] = witnesses[i].getPackageName().split("\\.");
        }

        StringBuilder prefix = new StringBuilder();
        int segmentIndex = 0;
        while (true) {
            String candidate = null;
            for (String[] witnessSegments : segments) {
                if (segmentIndex >= witnessSegments.length) {
                    candidate = null;
                    break;
                }
                if (candidate == null) {
                    candidate = witnessSegments[segmentIndex];
                } else if (!candidate.equals(witnessSegments[segmentIndex])) {
                    candidate = null;
                    break;
                }
            }
            if (candidate == null) break;
            if (!prefix.isEmpty()) prefix.append('.');
            prefix.append(candidate);
            segmentIndex++;
        }

        if (prefix.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot determine the library root package: " + describe(witnesses)
                            + " share no common package segment. Fix PandasticLibrary.PACKAGE_WITNESSES.");
        }
        return prefix.toString();
    }

    private static @NotNull String describe(Class<?> @NotNull [] witnesses) {
        StringBuilder out = new StringBuilder();
        for (Class<?> witness : witnesses) {
            if (!out.isEmpty()) out.append(", ");
            out.append(witness.getName());
        }
        return out.toString();
    }
}
