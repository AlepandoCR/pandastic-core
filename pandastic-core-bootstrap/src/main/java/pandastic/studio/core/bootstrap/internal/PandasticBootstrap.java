package pandastic.studio.core.bootstrap.internal;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;
import pandastic.studio.core.bootstrap.internal.service.PandasticReflectiveApi;
import pandastic.studio.core.service.api.PandasticCore;
import pandastic.studio.core.service.api.PandasticCoreProvider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Leader election for the shaded runtime.
 * <p>
 * The first plugin in the JVM to initialise the library wins the election and keeps the real
 * implementation, which owns the shared runtime. Every later plugin receives a
 * {@link PandasticReflectiveApi} bridged onto that instance. Election state lives in the JVM-global
 * {@link PandasticJvmAnchor} and is read back defensively: see {@link #readTrustedLeader(Map)}.
 * <p>
 * The {@code @BridgeContractVersion} declaration that stamps
 * {@code GeneratedBridgeContract.PROTOCOL_VERSION} lives on {@code PandasticAnchorKeys} in
 * {@code pandastic-core-service-api}: the annotation is SOURCE-retention, so it must sit in the module whose
 * compilation runs the processor. Election below compares that generated constant — the protocol
 * version is precisely what two copies agree on before sharing anything.
 */
public final class PandasticBootstrap {

    /**
     * Anchor slot holding the elected leader.
     */
    public static final String KEY_LEADER = GeneratedBridgeContract.AnchorKeys.LEADER;

    /**
     * Anchor slot holding the leader's bridge protocol version.
     */
    public static final String KEY_PROTOCOL = GeneratedBridgeContract.AnchorKeys.PROTOCOL;

    /**
     * Anchor slot holding the leader owner's plugin name.
     */
    public static final String KEY_LEADER_PLUGIN = GeneratedBridgeContract.AnchorKeys.LEADER_PLUGIN;

    /**
     * Anchor slot holding a {@link WeakReference} to the leader owner plugin.
     */
    public static final String KEY_LEADER_PLUGIN_REF = GeneratedBridgeContract.AnchorKeys.LEADER_PLUGIN_REF;

    /**
     * Anchor slot holding the published leader's class name, used to detect tampering.
     */
    public static final String KEY_LEADER_CLASS = GeneratedBridgeContract.AnchorKeys.LEADER_CLASS;

    /**
     * Anchor slot caching the leader's service manager.
     */
    public static final String KEY_LEADER_SERVICE_MANAGER = GeneratedBridgeContract.AnchorKeys.LEADER_SERVICE_MANAGER;

    /**
     * Property name of the optional protocol-version override inside
     * {@link PandasticLibrary#METADATA_RESOURCE}.
     */
    private static final String PROTOCOL_PROPERTY = "protocolVersion";

    /**
     * Protocol version this copy was compiled against, generated from {@link BridgeContractVersion}
     * above. This is the authoritative value and the fallback for every failure mode below.
     */
    private static final int COMPILED_PROTOCOL_VERSION = GeneratedBridgeContract.PROTOCOL_VERSION;

    /**
     * Protocol version this copy will advertise. Two copies only share a runtime when these match;
     * otherwise each runs in isolated mode.
     */
    private static final int PROTOCOL_VERSION = loadProtocolVersion();

    /**
     * The leader instance this copy of the library created, if it ever created one.
     * <p>
     * Written and read only under {@link PandasticJvmAnchor#lock()}; see {@link #localLeader}. It holds
     * nothing from another classloader, so it pins nothing a plugin's own statics do not already
     * pin, and {@link #releaseLeader} clears it.
     */
    private static PandasticCore localLeader;

    private PandasticBootstrap() {
    }

    /**
     * Reads the bridge protocol version, preferring an explicit packaging override.
     * <p>
     * {@link PandasticLibrary#METADATA_RESOURCE} is optional: a normal build does not ship it, and its
     * absence simply means "use the version I was compiled against". A <em>malformed</em> one is a
     * different matter. The previous implementation caught everything and silently returned
     * {@code 1}, so a typo in that file would make one plugin advertise protocol 1 while the rest
     * advertised the real version — producing two independent leaders, two thread pools and two
     * caches, with no symptom other than the sharing quietly not happening. Each failure mode is
     * therefore distinguished and reported, and the fallback is the compiled version rather than a
     * hardcoded {@code 1}.
     */
    private static int loadProtocolVersion() {
        String resource = PandasticLibrary.METADATA_RESOURCE;
        try (InputStream metadata = PandasticBootstrap.class.getClassLoader().getResourceAsStream(resource)) {
            if (metadata == null) return COMPILED_PROTOCOL_VERSION;

            Properties properties = new Properties();
            properties.load(metadata);

            String declared = properties.getProperty(PROTOCOL_PROPERTY);
            if (declared == null || declared.isBlank()) {
                PandasticCoreLog.warn("Resource " + resource + " exists but declares no '" + PROTOCOL_PROPERTY
                        + "'. Using the compiled protocol version " + COMPILED_PROTOCOL_VERSION
                        + "; fix the packaging, because a protocol disagreement between plugins silently"
                        + " splits the shared runtime in two.");
                return COMPILED_PROTOCOL_VERSION;
            }

            int parsed = Integer.parseInt(declared.trim());
            if (parsed != COMPILED_PROTOCOL_VERSION) {
                PandasticCoreLog.warn("Resource " + resource + " declares " + PROTOCOL_PROPERTY + "=" + parsed
                        + " but this copy was compiled against " + COMPILED_PROTOCOL_VERSION
                        + ". Honouring the resource, but only plugins advertising " + parsed
                        + " will share a runtime with this one.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            PandasticCoreLog.warn("Resource " + resource + " declares a malformed " + PROTOCOL_PROPERTY
                    + " (" + e.getMessage() + "). Using the compiled protocol version "
                    + COMPILED_PROTOCOL_VERSION + "; fix the packaging, because a protocol disagreement"
                    + " between plugins silently splits the shared runtime in two.", e);
            return COMPILED_PROTOCOL_VERSION;
        } catch (IOException e) {
            PandasticCoreLog.warn("Resource " + resource + " could not be read. Using the compiled protocol"
                    + " version " + COMPILED_PROTOCOL_VERSION + ".", e);
            return COMPILED_PROTOCOL_VERSION;
        }
    }

    /**
     * Joins the shared runtime, discovering the leader implementation through
     * {@link PandasticCoreProviders}.
     *
     * @param plugin  the calling plugin
     * @param logging whether to report the outcome to the plugin's logger
     * @return the leader implementation, or a follower bridge onto it
     */
    public static @NotNull PandasticCore init(@NotNull JavaPlugin plugin, boolean logging) {
        return init(plugin, PandasticCoreProviders.provider(), logging);
    }

    /**
     * Joins the shared runtime with an explicit provider.
     *
     * @param plugin   the calling plugin
     * @param provider factory for the leader implementation, used only if this call wins election
     * @param logging  whether to report the outcome to the plugin's logger
     * @return the leader implementation, or a follower bridge onto it
     */
    public static @NotNull PandasticCore init(@NotNull JavaPlugin plugin,
                                        @NotNull PandasticCoreProvider provider,
                                        boolean logging) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(provider, "provider");

        PandasticBootstrapLogger log = new PandasticBootstrapLogger(logging, plugin);
        Map<String, Object> anchor = PandasticJvmAnchor.anchorMap();

        // Installed before election and outside the anchor lock: every participant — leader or
        // follower — must notice other participants being unloaded, or a plugman unload of the
        // leader would only be discovered lazily on the next bridged call.
        PandasticLifecycleGuard.install(plugin, provider);

        synchronized (PandasticJvmAnchor.lock()) {
            Object leader = readTrustedLeader(anchor);

            if (leader != null) {
                Object protocol = anchor.get(KEY_PROTOCOL);
                int leaderProtocol = (protocol instanceof Integer declared) ? declared : -1;
                if (leaderProtocol != PROTOCOL_VERSION) {
                    log.warn("Protocol mismatch (leader=" + leaderProtocol + ", local=" + PROTOCOL_VERSION
                            + "). Falling back to isolated mode: this plugin will not share the leader's"
                            + " thread pool or caches.");
                    return localLeader(plugin, provider);
                }

                Object leaderPluginName = anchor.get(KEY_LEADER_PLUGIN);
                PandasticCore api = PandasticReflectiveApi.create(plugin, provider, logging);
                PandasticApiRegistry.registerFollower(anchor, plugin);

                if (logging) {
                    log.info("Connected as follower. leaderPlugin="
                            + (leaderPluginName == null ? "<unknown>" : leaderPluginName)
                            + " leader=" + fingerprint(leader)
                            + ", protocol=" + PROTOCOL_VERSION
                            + ", followers=" + PandasticApiRegistry.followerCount(anchor));
                    log.info("Shared resources are owned by the leader runtime. Two plugins reporting the"
                            + " same leader fingerprint really are sharing one thread pool and one set of"
                            + " caches.");
                }
                return api;
            }

            PandasticCore createdLeader = localLeader(plugin, provider);
            publishLeaderState(anchor, plugin, createdLeader);

            if (logging) {
                log.info("Elected as leader. leaderPlugin=" + plugin.getName()
                        + " leader=" + fingerprint(createdLeader)
                        + ", protocol=" + PROTOCOL_VERSION);
                log.info("Shared runtime initialised here: common thread pool, HTTP client, caches and"
                        + " the service registry.");
            }
            return createdLeader;
        }
    }

    /**
     * Returns the current leader, electing a new one in the caller's classloader if there is none,
     * if its protocol does not match, or if the anchor contents are not trustworthy.
     *
     * @param caller   the plugin asking
     * @param provider factory used if a new leader has to be elected
     * @param logging  whether to report a re-election to the caller's logger
     * @return an object that passed {@link PandasticLeaderValidator#isTrustedLeader}
     */
    public static @NotNull Object ensureLeader(@NotNull JavaPlugin caller,
                                               @NotNull PandasticCoreProvider provider,
                                               boolean logging) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(provider, "provider");

        PandasticBootstrapLogger log = new PandasticBootstrapLogger(logging, caller);
        Map<String, Object> anchor = PandasticJvmAnchor.anchorMap();

        synchronized (PandasticJvmAnchor.lock()) {
            Object leader = readTrustedLeader(anchor);
            if (leader != null && anchor.get(KEY_PROTOCOL) instanceof Integer protocol && protocol == PROTOCOL_VERSION) {
                return leader;
            }

            log.warn("No compatible leader found. Electing a new leader in this plugin's classloader.");

            PandasticCore createdLeader = localLeader(caller, provider);
            publishLeaderState(anchor, caller, createdLeader);
            return createdLeader;
        }
    }

    /**
     * This copy's own leader instance, created at most once.
     * <h2>Why this is memoised</h2>
     * {@link #ensureLeader} runs on every bridged call, and it elects whenever the anchor does not
     * hold a usable leader. Creating a fresh one each time it does so is an amplification: the
     * anchor is a world-writable system property, so anything in the JVM can keep the leader slot
     * unusable, and every bridged call would then construct another shared runtime — another cached
     * thread pool, another {@code HttpClient} with its own selector thread — none of which is ever
     * shut down, because only the currently published leader is ever released. A few hundred calls
     * is thread exhaustion.
     * <p>
     * Re-publishing the instance this copy already created is also simply correct: a classloader
     * has one shared runtime, and electing "again" in the same classloader should mean reclaiming
     * the election, not starting a second runtime alongside the first.
     * <p>
     * Callers must hold {@link PandasticJvmAnchor#lock()}, which is what makes the check-then-create safe
     * without a second monitor.
     */
    /**
     * Test hook: clears the memoised local leader so failover scenarios start from a clean copy.
     * Production code must never call this — the memo exists to prevent runtime amplification.
     */
    static void resetLocalLeaderForTesting() {
        localLeader = null;
    }

    private static @NotNull PandasticCore localLeader(@NotNull Plugin plugin, @NotNull PandasticCoreProvider provider) {
        PandasticCore existing = localLeader;
        if (existing != null) return existing;

        PandasticCore created = provider.createLeader(plugin);
        localLeader = created;
        return created;
    }

    /**
     * Reads the leader slot and returns its contents <b>only if they can be trusted</b>.
     * <p>
     * Anything in the anchor arrived through a world-writable {@link System} property, so it is
     * untrusted input. A candidate must satisfy {@link PandasticLeaderValidator#isTrustedLeader}, agree
     * with the class name recorded next to it, and still be owned by a live plugin; otherwise the
     * whole leader state is cleared so the caller re-elects, and the rejection is logged loudly
     * rather than being papered over.
     * <p>
     * Callers must hold {@link PandasticJvmAnchor#lock()}.
     *
     * @param anchor the anchor map to inspect
     * @return a trustworthy leader, or {@code null} if the slot was empty or has been rejected
     */
    public static @Nullable Object readTrustedLeader(@NotNull Map<String, Object> anchor) {
        Object leader = anchor.get(KEY_LEADER);
        if (leader == null) return null;

        if (!PandasticLeaderValidator.isTrustedLeader(leader)) {
            PandasticCoreLog.warn("Refusing to bridge onto the object in the anchor's leader slot: "
                    + PandasticLeaderValidator.describeRejection(leader)
                    + ". Clearing the slot and re-electing. The anchor lives in a world-writable system"
                    + " property, so this is either a key collision or deliberate interference.");
            clearLeaderState(anchor);
            return null;
        }

        if (!PandasticLeaderValidator.matchesRecordedClass(leader, anchor.get(KEY_LEADER_CLASS))) {
            PandasticCoreLog.warn("Anchor leader slot holds a " + leader.getClass().getName()
                    + " but the recorded leader class is " + anchor.get(KEY_LEADER_CLASS)
                    + ". The slot and its metadata were written by different parties; clearing and"
                    + " re-electing.");
            clearLeaderState(anchor);
            return null;
        }

        if (!leaderOwnerIsLive(anchor)) {
            PandasticCoreLog.warn("The leader in the anchor (" + leader.getClass().getName()
                    + ") is owned by a plugin that is no longer enabled, so it is running code from a"
                    + " classloader the server has moved on from. Clearing the slot and re-electing;"
                    + " this is what a plugin that skipped its shutdown() leaves behind.");
            clearLeaderState(anchor);
            return null;
        }

        return leader;
    }

    /**
     * Whether the plugin that published the current leader is still enabled.
     * <h2>Why liveness is asked, not inferred</h2>
     * {@link #KEY_LEADER_PLUGIN_REF} is a {@link WeakReference} so that the anchor cannot pin a
     * disabled plugin's classloader. On its own, though, it can never report anything: the leader
     * object sits in the same anchor under a <em>strong</em> reference and holds its owning plugin,
     * so the weak reference is kept alive by the very slot it was meant to say something about.
     * Asking the plugin whether it is enabled is independent of garbage collection and is true
     * immediately, which is what makes a stale leader detectable at all.
     * <p>
     * An absent slot reads as live: it means the leader was published by a copy of this library
     * that did not record it, and refusing on that basis would reject a perfectly good leader.
     */
    private static boolean leaderOwnerIsLive(@NotNull Map<String, Object> anchor) {
        Object recorded = anchor.get(KEY_LEADER_PLUGIN_REF);
        if (!(recorded instanceof Reference<?> reference)) return true;

        Object owner = reference.get();
        if (owner == null) return false;
        if (!(owner instanceof Plugin plugin)) return true;
        try {
            return plugin.isEnabled();
        } catch (RuntimeException | LinkageError e) {
            // The slot is world-writable, so this may not be a real plugin at all. Re-electing is
            // the safe outcome: it produces a known-good leader in the caller's own classloader.
            PandasticCoreLog.warn("Could not ask the recorded leader plugin whether it is still enabled;"
                    + " treating the leader as stale and re-electing.", e);
            return false;
        }
    }

    /**
     * Publishes a freshly elected leader. Callers must hold {@link PandasticJvmAnchor#lock()}.
     */
    private static void publishLeaderState(@NotNull Map<String, Object> anchor,
                                           @NotNull Plugin plugin,
                                           @NotNull PandasticCore createdLeader) {
        anchor.put(KEY_LEADER, createdLeader);
        anchor.put(KEY_PROTOCOL, PROTOCOL_VERSION);
        anchor.put(KEY_LEADER_PLUGIN, plugin.getName());
        // Weak on purpose: a strong reference in a JVM-global map would pin a disabled plugin's
        // classloader for the lifetime of the process.
        anchor.put(KEY_LEADER_PLUGIN_REF, new WeakReference<>(plugin));
        anchor.put(KEY_LEADER_CLASS, createdLeader.getClass().getName());

        // Cache the leader's service manager so followers do not have to fetch it reflectively on
        // every call. Failing here is survivable (followers fall back to the reflective fetch) but
        // never normal, so it is reported rather than ignored.
        try {
            anchor.put(KEY_LEADER_SERVICE_MANAGER, createdLeader.getServiceManager());
        } catch (RuntimeException e) {
            PandasticCoreLog.warn("Leader " + createdLeader.getClass().getName() + " could not expose its service"
                    + " manager at election time; followers will resolve it reflectively instead.", e);
        }
    }

    /**
     * Releases a leader election and, once nobody is left, tears the shared runtime down.
     * <p>
     * Called by the leader implementation's own {@code shutdown()}. The runtime is only destroyed
     * when no followers remain: a leader plugin may well be disabled while other plugins are still
     * holding bridges onto its services, and pulling the thread pool out from under them would
     * break plugins that did nothing wrong. Those followers keep their bridge, and the next
     * {@code ensureLeader} re-elects in whichever classloader asks first.
     * <p>
     * The anchor is emptied rather than left with stale slots, but the lock object is preserved:
     * {@link PandasticJvmAnchor#reset()} explains why.
     * <h2>Teardown runs outside the anchor lock</h2>
     * {@link PandasticJvmAnchor#lock()} is a single JVM-global monitor that every bridged call acquires,
     * and closing the shared runtime waits a bounded time for in-flight tasks. Holding the lock
     * across that wait stalls every other plugin's bridged calls for its full duration — on the
     * server's main thread during a {@code /reload}, which is what a watchdog kills a server for —
     * and the tasks being waited on cannot make progress either if any of them needs the bridge, so
     * the wait is guaranteed to run to its timeout rather than finish early. The decision is taken
     * under the lock, the waiting is not, and the final reset re-checks that nothing was published
     * in between.
     *
     * @param leader         the leader releasing itself; ignored if it is not the elected one
     * @param runtimeTeardown closes the shared runtime, run only when the last follower is gone
     */
    public static void releaseLeader(@NotNull PandasticCore leader, @NotNull Runnable runtimeTeardown) {
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(runtimeTeardown, "runtimeTeardown");

        Map<String, Object> anchor = PandasticJvmAnchor.anchorMap();
        boolean elected;
        synchronized (PandasticJvmAnchor.lock()) {
            elected = anchor.get(KEY_LEADER) == leader;
            if (localLeader == leader) localLeader = null;

            if (elected) {
                clearLeaderState(anchor);

                int remainingFollowers = PandasticApiRegistry.followerCount(anchor);
                if (remainingFollowers > 0) {
                    PandasticCoreLog.warn("The leader plugin is shutting down while " + remainingFollowers
                            + " follower(s) still hold bridges onto it. Keeping the shared runtime alive;"
                            + " the next plugin to call into the library will elect a new leader.");
                    return;
                }
            }
        }

        if (!elected) {
            // Not the elected leader — typically because a failover already promoted a successor
            // while this plugin was being disabled. The anchor belongs to somebody else now, but
            // this instance's runtime belongs to nobody else: without tearing it down here its
            // thread pool and HttpClient leak, since no other copy ever can. Teardown runs outside
            // the anchor lock for the same reason the elected path's does.
            PandasticCoreLog.info("Releasing a leader runtime that is no longer elected (a successor has"
                    + " already been promoted). Tearing down this plugin's own runtime only; the"
                    + " anchor is left to the current leader.");
            runtimeTeardown.run();
            return;
        }

        runtimeTeardown.run();

        synchronized (PandasticJvmAnchor.lock()) {
            // Only empty the anchor if nobody claimed it while the runtime was closing. A plugin
            // that elected a new leader in the meantime owns a different runtime, and wiping its
            // slots would strand it.
            if (anchor.get(KEY_LEADER) == null && PandasticApiRegistry.followerCount(anchor) == 0) {
                PandasticJvmAnchor.reset();
            }
        }
    }

    /**
     * Reacts to any plugin in the server being disabled. Called by {@link PandasticLifecycleGuard} on
     * the main thread, in every participating copy, the moment the event fires — which is before
     * the dying plugin's {@code onDisable} runs and before its listeners are unregistered.
     * <p>
     * The dying copy itself does nothing here: its own cleanup belongs to its {@code shutdown()}
     * (which may legitimately still be used inside its {@code onDisable}). Survivors do the work,
     * which is exactly what makes a {@code plugman unload} of the leader survivable.
     *
     * @param self     the plugin this library copy is shaded into
     * @param provider factory used if this copy is the designated successor
     * @param dying    the plugin being disabled
     */
    static void onParticipantDisabled(@NotNull Plugin self,
                                      @NotNull PandasticCoreProvider provider,
                                      @NotNull Plugin dying) {
        if (dying == self) return;

        Map<String, Object> anchor = PandasticJvmAnchor.anchorMap();
        synchronized (PandasticJvmAnchor.lock()) {
            handleDisabledParticipant(anchor, self, provider, dying);
        }
    }

    /**
     * Lock-free core of {@link #onParticipantDisabled}, parameterised on the anchor so tests can
     * drive it against a private map. Production callers must hold {@link PandasticJvmAnchor#lock()}.
     */
    static void handleDisabledParticipant(@NotNull Map<String, Object> anchor,
                                          @NotNull Plugin self,
                                          @NotNull PandasticCoreProvider provider,
                                          @NotNull Plugin dying) {
        failoverIfLeaderLost(anchor, self, provider, dying);
        pruneDeadFollower(anchor, dying);
    }

    /**
     * Fails the leadership over when the disabled plugin owned it.
     * <p>
     * Order-independent on purpose: every surviving copy's listener runs this, in unspecified
     * order, so the logic may not assume it is first. Whichever copy runs first clears the dead
     * leader's slots; the <em>designated successor</em> — the earliest-joined live follower — is
     * the only copy that publishes a new leader, no matter when its listener fires, because the
     * promotion condition is evaluated against the anchor, not against who cleared it.
     */
    private static void failoverIfLeaderLost(@NotNull Map<String, Object> anchor,
                                             @NotNull Plugin self,
                                             @NotNull PandasticCoreProvider provider,
                                             @NotNull Plugin dying) {
        boolean leaderDied = anchor.get(KEY_LEADER_PLUGIN) instanceof String recorded
                && recorded.equals(dying.getName());
        if (leaderDied) {
            PandasticCoreLog.warn("Leader plugin '" + dying.getName() + "' is being disabled (unload or"
                    + " reload). Starting leader failover.");
            clearLeaderState(anchor);
            PandasticApiRegistry.unregisterFollower(anchor, dying);
            // The dead leader plugin's own trusted bridge packages die with it; other plugins'
            // registrations survive the failover, because the slot is anchor-scoped, not
            // leader-scoped.
            PandasticBridgePackages.removeAll(anchor, dying.getName());
        }

        // A healthy leader means there is nothing to fail over (an unrelated plugin disabled, or
        // another copy's listener already completed the promotion).
        if (readTrustedLeader(anchor) != null) return;

        String successor = PandasticApiRegistry.designatedSuccessor(anchor, dying.getName());
        if (successor == null) {
            if (leaderDied) {
                PandasticCoreLog.info("No followers remain after the leader's departure; the next plugin"
                        + " to call into the library will elect a new leader.");
            }
            return;
        }

        if (!successor.equals(self.getName())) {
            PandasticCoreLog.info("Leader failover: designated successor is '" + successor + "'. This"
                    + " plugin ('" + self.getName() + "') stays a follower and re-attaches to the"
                    + " new leader on its next call.");
            return;
        }

        PandasticCore promoted = localLeader(self, provider);
        publishLeaderState(anchor, self, promoted);
        PandasticApiRegistry.unregisterFollower(anchor, self);
        PandasticCoreLog.info("Leader failover complete: '" + self.getName() + "' (earliest remaining"
                + " follower) is the new leader " + fingerprint(promoted) + ", protocol "
                + PROTOCOL_VERSION + ". " + PandasticApiRegistry.followerCount(anchor) + " follower(s)"
                + " re-attach on their next call. Services registered with the previous leader are"
                + " gone and must be re-registered by their owners.");
    }

    /**
     * Cleans up after a follower that was disabled without calling {@code shutdown()}. Only the
     * copy currently holding the published leadership acts: it owns the registry the dead follower
     * is polluting, and exactly one copy acting keeps the log noise at one line per death.
     */
    private static void pruneDeadFollower(@NotNull Map<String, Object> anchor, @NotNull Plugin dying) {
        PandasticCore mine = localLeader;
        if (mine == null || anchor.get(KEY_LEADER) != mine) return;

        boolean wasRegistered = PandasticApiRegistry.followers(anchor).stream()
                .anyMatch(follower -> follower.getName().equals(dying.getName()));
        if (!wasRegistered) return;

        PandasticApiRegistry.unregisterFollower(anchor, dying);
        // Directly, not only via the registry sweep below: the sweep may fail, and an unloaded
        // plugin's prefixes must not keep its interfaces proxyable either way.
        PandasticBridgePackages.removeAll(anchor, dying.getName());

        int removedServices = 0;
        try {
            removedServices = mine.getServiceManager().unregisterAll(dying);
        } catch (RuntimeException e) {
            PandasticCoreLog.warn("Could not unregister the services of disabled follower '"
                    + dying.getName() + "'; its registrations may linger until the leader restarts.", e);
        }
        PandasticCoreLog.info("Follower plugin '" + dying.getName() + "' was disabled without detaching;"
                + " removed its registration and " + removedServices + " of its service(s).");
    }

    /**
     * Removes every leader-related slot, leaving the follower registry and the lock intact.
     */
    private static void clearLeaderState(@NotNull Map<String, Object> anchor) {
        anchor.remove(KEY_LEADER);
        anchor.remove(KEY_PROTOCOL);
        anchor.remove(KEY_LEADER_PLUGIN);
        anchor.remove(KEY_LEADER_PLUGIN_REF);
        anchor.remove(KEY_LEADER_CLASS);
        anchor.remove(KEY_LEADER_SERVICE_MANAGER);
    }

    private static @NotNull String fingerprint(@NotNull Object instance) {
        return "@" + Integer.toHexString(System.identityHashCode(instance));
    }
}
