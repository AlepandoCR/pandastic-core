package pandastic.studio.core.service.internal.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the library-wide rule "no async work depends on the Bukkit scheduler" for its one
 * sanctioned exception: the economy's cross-thread hop.
 *
 * <p>Economy operations must run on the main thread (Vault providers promise no thread safety),
 * so the production wiring uses the scheduler — but only as the hop for async callers. On the
 * main thread it runs <b>inline</b>, which is what makes economy usable during {@code onEnable}
 * and {@code onDisable}: both run on the main thread, and the fake {@link Server} here throws on
 * any scheduler access, so a reintroduced scheduler dependency on that path fails the test
 * immediately. Off the main thread with no usable scheduler, the contract is fail-loud: an
 * exceptional future, never a hang.
 */
class PandasticEconomyServiceStartupTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    @DisplayName("on the main thread, economy runs inline — the scheduler is never touched")
    void inlineOnMainThreadWithoutScheduler() throws Exception {
        PandasticEconomyServiceImpl economy = PandasticEconomyServiceImpl.vaultBacked(
                fakePlugin(fakeServer(true)), ForkJoinPool.commonPool());

        // The fake server's getScheduler() throws, so completing at all proves the inline path.
        assertEquals(123.45, economy.getBalance(PLAYER).get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("off the main thread with no scheduler, the future fails loudly instead of hanging")
    void offMainThreadFailsLoudlyWhenSchedulerUnavailable() {
        PandasticEconomyServiceImpl economy = PandasticEconomyServiceImpl.vaultBacked(
                fakePlugin(fakeServer(false)), ForkJoinPool.commonPool());

        CompletableFuture<Double> balance = economy.getBalance(PLAYER);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> balance.get(2, TimeUnit.SECONDS));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause(),
                "the scheduler refusal must surface in the future, not hang it");
    }

    // ------------------------------------------------------------------
    // Fakes: a Server whose scheduler always throws, backing a real Vault registration.
    // ------------------------------------------------------------------

    private static Server fakeServer(boolean primaryThread) {
        Economy economy = fakeEconomy();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> primaryThread;
            case "getServicesManager" -> servicesManagerWith(proxy, economy);
            case "getOfflinePlayer" -> offlinePlayer((UUID) args[0]);
            case "getScheduler" -> throw new UnsupportedOperationException(
                    "economy must not touch the scheduler on this path");
            case "toString" -> "FakeServer";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Server#" + method.getName());
        };
        return (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(), new Class<?>[]{Server.class}, handler);
    }

    private static ServicesManager servicesManagerWith(Object server, Economy economy) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getRegistration" -> new RegisteredServiceProvider<>(
                    Economy.class, economy, ServicePriority.Normal, null);
            case "toString" -> "FakeServicesManager";
            default -> throw new UnsupportedOperationException("ServicesManager#" + method.getName());
        };
        return (ServicesManager) Proxy.newProxyInstance(
                ServicesManager.class.getClassLoader(), new Class<?>[]{ServicesManager.class}, handler);
    }

    /** Vault's Economy as a proxy: only the members this test exercises answer. */
    private static Economy fakeEconomy() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBalance" -> 123.45;
            case "getName" -> "FakeEconomy";
            case "toString" -> "FakeEconomy";
            default -> throw new UnsupportedOperationException("Economy#" + method.getName());
        };
        return (Economy) Proxy.newProxyInstance(
                Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, handler);
    }

    private static OfflinePlayer offlinePlayer(UUID id) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "toString" -> "FakeOfflinePlayer(" + id + ")";
            default -> throw new UnsupportedOperationException("OfflinePlayer#" + method.getName());
        };
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, handler);
    }

private static Plugin fakePlugin(Server server) {
        Logger logger = Logger.getLogger("PandasticEconomyServiceStartupTest");
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getName" -> "EconomyStartupTestPlugin";
            case "getLogger" -> logger;
            case "isEnabled" -> true;
            case "toString" -> "FakeEconomyPlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Plugin#" + method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }
}
