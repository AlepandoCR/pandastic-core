package pandastic.studio.core.bootstrap.api;

import pandastic.studio.core.bootstrap.api.contract.BridgeAnchorKey;
import pandastic.studio.core.bootstrap.api.contract.BridgeContractVersion;

/**
 * Declaration site for the slot names used inside the shared anchor map, and carrier of the
 * bridge protocol version.
 * <p>
 * These constants exist only to be read by {@code BridgeContractProcessor} at build time: the
 * processor copies each field into {@code GeneratedBridgeContract.AnchorKeys} under the same
 * field name, and the rest of the library reads the <em>generated</em> constants. Routing the
 * names through code generation is what guarantees that every shaded copy of a given library
 * version agrees on them, and that a typo becomes a compile error rather than a silent
 * "leader not found".
 * <p>
 * The values are deliberately short and unqualified. They are keys inside a map that is itself
 * namespaced by the anchor property key ({@code PandasticLibrary.ANCHOR_PROPERTY_KEY} in
 * {@code pandastic-core-bootstrap}), so there is nothing for them to collide with; qualifying them again
 * would just be a second place to keep the package name in sync.
 * <p>
 * {@link BridgeContractVersion} lives here rather than on the bootstrap machinery because the
 * annotation is SOURCE-retention: the processor can only see it in the module whose sources it is
 * compiling, and this module is where the whole annotated contract surface resides.
 * <h2>Protocol history</h2>
 * <ul>
 *     <li><b>3</b> — adds {@code PandasticServiceManager.getService(String, Class)} and
 *     {@code PandasticCore.registerBridgePackage(Plugin, String)}.</li>
 *     <li><b>2</b> — adds the unregistration methods and the per-plugin profile services.</li>
 * </ul>
 * A protocol mismatch is <em>not</em> an error state: a copy that finds a leader advertising a
 * different protocol falls back to isolated mode — it elects its own leader in its own
 * classloader and simply does not share the runtime. That predictable degradation is exactly what
 * an older follower meeting a newer leader (or vice versa) is supposed to get, instead of
 * invoking methods the other side does not have.
 */
@BridgeContractVersion(3)
final class PandasticAnchorKeys {

    /**
     * Monitor every copy of the library synchronises on while reading or publishing leader state.
     * Survives {@code PandasticJvmAnchor.reset()} so a torn-down anchor can still be re-elected safely.
     */
    @BridgeAnchorKey(id = "anchor.lock")
    static final String LOCK = "lock";

    /**
     * The elected leader instance, owned by the first plugin that initialised the library.
     */
    @BridgeAnchorKey(id = "anchor.leader")
    static final String LEADER = "leader";

    /**
     * Bridge protocol version of the copy that elected the current leader, as an {@link Integer}.
     */
    @BridgeAnchorKey(id = "anchor.protocol")
    static final String PROTOCOL = "protocol";

    /**
     * Plugin name of the leader owner, as a {@link String}, for diagnostics.
     */
    @BridgeAnchorKey(id = "anchor.leader.plugin")
    static final String LEADER_PLUGIN = "leader.plugin";

    /**
     * Weak reference to the leader owner plugin. Weak on purpose: a strong reference here would
     * pin a disabled plugin's classloader in a JVM-global map forever.
     */
    @BridgeAnchorKey(id = "anchor.leader.plugin.ref")
    static final String LEADER_PLUGIN_REF = "leader.plugin.ref";

    /**
     * Fully-qualified class name of the published leader, used to detect anchor tampering.
     */
    @BridgeAnchorKey(id = "anchor.leader.class")
    static final String LEADER_CLASS = "leader.class";

    /**
     * Cached leader-side service manager, so followers do not have to reflectively fetch it on
     * every call.
     */
    @BridgeAnchorKey(id = "anchor.leader.serviceManager")
    static final String LEADER_SERVICE_MANAGER = "leader.serviceManager";

    /**
     * Weakly-referenced follower registry, used for diagnostics and for deciding whether a leader
     * shutdown may tear the shared runtime down.
     */
    @BridgeAnchorKey(id = "anchor.followers")
    static final String FOLLOWERS = "followers";

    /**
     * Join order of the followers, as a {@code ConcurrentMap<String, Long>} from plugin name to a
     * monotonic join timestamp. The earliest live entry is the designated successor when the
     * leader's plugin is unloaded. Additive slot: layout-2 copies that predate it simply never
     * read it, and failover degrades to first-caller election.
     */
    @BridgeAnchorKey(id = "anchor.followers.seq")
    static final String FOLLOWER_SEQUENCE = "followers.seq";

    /**
     * Trusted bridge package prefixes, as a {@code ConcurrentMap<String, Set<String>>} from
     * owning plugin name to the package prefixes that plugin registered via
     * {@code PandasticCore.registerBridgePackage}. Interfaces under a registered prefix become
     * cross-proxyable exactly like the library's own interfaces; nothing else about the bridge
     * rules changes. Keys and elements are plain parent-visible {@link String}s, so every shaded
     * copy can read the slot.
     * <p>
     * Like every anchor slot this is untrusted input: readers shape-check it and a poisoned value
     * degrades to "no trusted prefixes" rather than throwing into another plugin's call path.
     * <p>
     * <b>Additive slot</b>: copies that predate it simply never read it, so it does not require
     * an {@code PandasticLibrary.ANCHOR_LAYOUT_VERSION} bump — no existing slot changed shape.
     */
    @BridgeAnchorKey(id = "anchor.bridge.packages")
    static final String BRIDGE_PACKAGES = "bridge.packages";

    private PandasticAnchorKeys() {
    }
}
