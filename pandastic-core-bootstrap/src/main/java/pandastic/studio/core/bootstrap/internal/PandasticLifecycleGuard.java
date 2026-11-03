package pandastic.studio.core.bootstrap.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticCoreProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Makes leader failover eager instead of lazy.
 *
 * <p>Without this listener a {@code plugman unload} of the leader plugin is only discovered on the
 * <em>next</em> bridged call, through {@code readTrustedLeader}'s liveness check. With it, every
 * participating copy hears {@link PluginDisableEvent} the moment any plugin goes down — Bukkit
 * fires the event before the dying plugin's {@code onDisable} runs and before its listeners are
 * unregistered — and hands the event to
 * {@link PandasticBootstrap#onParticipantDisabled(Plugin, PandasticCoreProvider, Plugin)}: the dead leader's
 * slots are cleared immediately, the earliest-joined surviving follower promotes itself, and the
 * whole transition is logged.
 *
 * <p>The lazy check stays in place as the backstop: on servers where the listener cannot be
 * registered (plugin disabled at init time, no server in unit tests), everything still works —
 * just on the next call instead of on the event.
 */
final class PandasticLifecycleGuard implements Listener {

    /**
     * Plugins this copy already guards. A copy normally serves exactly one plugin, but init may
     * run more than once; the entry is dropped when the plugin disables so that a plugman
     * {@code load} after an {@code unload} installs a fresh listener (Bukkit unregistered the old
     * one as part of the disable).
     */
    private static final ConcurrentMap<String, Boolean> INSTALLED = new ConcurrentHashMap<>();

    private final Plugin self;
    private final PandasticCoreProvider provider;

    private PandasticLifecycleGuard(@NotNull Plugin self, @NotNull PandasticCoreProvider provider) {
        this.self = self;
        this.provider = provider;
    }

    /**
     * Registers the guard for {@code self}, once per live plugin instance. Failure is survivable
     * and loud: without the listener, failover degrades to the lazy next-call detection.
     *
     * @param self     the plugin this library copy is shaded into
     * @param provider factory used if this copy ever becomes the designated successor
     */
    static void install(@NotNull Plugin self, @NotNull PandasticCoreProvider provider) {
        if (INSTALLED.putIfAbsent(self.getName(), Boolean.TRUE) != null) return;

        try {
            self.getServer().getPluginManager().registerEvents(new PandasticLifecycleGuard(self, provider), self);
            PandasticCoreLog.debug("Lifecycle guard installed for '" + self.getName() + "'.");
        } catch (RuntimeException | LinkageError e) {
            INSTALLED.remove(self.getName());
            PandasticCoreLog.warn("Could not install the plugin-disable listener for '" + self.getName()
                    + "'; eager leader failover is unavailable in this copy. A stale leader is still"
                    + " detected lazily on the next bridged call.", e);
        }
    }

    /**
     * Routes every plugin disable to the bootstrap's failover logic.
     *
     * @param event the disable event, fired before the plugin's own teardown
     */
    @EventHandler
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        Plugin dying = event.getPlugin();
        if (dying == this.self) {
            // Our own plugin is going down: Bukkit is about to unregister this listener, so allow
            // a future re-init (plugman load) to install a fresh one. Cleanup of our own state is
            // shutdown()'s job; the survivors' guards handle everything global.
            INSTALLED.remove(this.self.getName());
            return;
        }

        try {
            PandasticBootstrap.onParticipantDisabled(this.self, this.provider, dying);
        } catch (RuntimeException e) {
            PandasticCoreLog.warn("Leader failover handling failed while reacting to the disable of '"
                    + dying.getName() + "'. The lazy next-call detection still applies.", e);
        }
    }
}
