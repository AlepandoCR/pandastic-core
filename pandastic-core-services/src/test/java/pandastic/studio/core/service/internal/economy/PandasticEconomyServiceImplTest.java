package pandastic.studio.core.service.internal.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.service.economy.EconomyTransactionDto;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the Vault-backed economy service against a scripted in-memory provider.
 *
 * <p>Both executors are direct (the test thread plays the main thread), so every future is
 * complete when the call returns and the atomicity-relevant ordering is observable through the
 * fake's balances.
 */
class PandasticEconomyServiceImplTest {

    private static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PAYEE = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final FakeEconomy economy = new FakeEconomy();

    private PandasticEconomyServiceImpl service(FakeEconomy provider) {
        return new PandasticEconomyServiceImpl(
                () -> provider,
                Runnable::run,
                ForkJoinPool.commonPool(),
                PandasticEconomyServiceImplTest::offlinePlayer
        );
    }

    @Test
    @DisplayName("balance, has and format read straight from the provider")
    void readsGoToTheProvider() {
        economy.balances.put(PAYER, 250.0);
        PandasticEconomyServiceImpl service = service(economy);

        assertEquals(250.0, service.getBalance(PAYER).join());
        assertTrue(service.has(PAYER, 250.0).join());
        assertFalse(service.has(PAYER, 250.01).join());
        assertEquals("$42.0", service.format(42.0).join());
        assertEquals("FakeEconomy", service.getProviderName().join());
        assertTrue(service.isEconomyAvailable().join());
    }

    @Test
    @DisplayName("charge withdraws when covered and reports the new balance")
    void chargeSuccess() {
        economy.balances.put(PAYER, 100.0);

        EconomyTransactionDto result = service(economy).charge(PAYER, 60.0).join();

        assertTrue(result.success());
        assertEquals(60.0, result.amount());
        assertEquals(40.0, result.newBalance());
        assertEquals(40.0, economy.balances.get(PAYER));
    }

    @Test
    @DisplayName("charge refuses on insufficient funds without touching the balance")
    void chargeInsufficient() {
        economy.balances.put(PAYER, 10.0);

        EconomyTransactionDto result = service(economy).charge(PAYER, 60.0).join();

        assertFalse(result.success());
        assertEquals(10.0, result.newBalance(), "the refusal carries the untouched balance");
        assertNotNull(result.error());
        assertEquals(10.0, economy.balances.get(PAYER), "no withdrawal may have happened");
        assertEquals(0, economy.withdrawCalls, "the withdraw leg must not even be attempted");
    }

    @Test
    @DisplayName("transfer moves money between both accounts atomically")
    void transferSuccess() {
        economy.balances.put(PAYER, 100.0);
        economy.balances.put(PAYEE, 5.0);

        EconomyTransactionDto result = service(economy).transfer(PAYER, PAYEE, 40.0).join();

        assertTrue(result.success());
        assertEquals(60.0, result.newBalance(), "newBalance is the payer's");
        assertEquals(60.0, economy.balances.get(PAYER));
        assertEquals(45.0, economy.balances.get(PAYEE));
    }

    @Test
    @DisplayName("a failed deposit leg refunds the payer")
    void transferRollsBack() {
        economy.balances.put(PAYER, 100.0);
        economy.rejectDepositsTo = PAYEE;

        EconomyTransactionDto result = service(economy).transfer(PAYER, PAYEE, 40.0).join();

        assertFalse(result.success());
        assertEquals(100.0, economy.balances.get(PAYER), "the payer must be refunded");
        assertEquals(100.0, result.newBalance());
        assertNotNull(result.error());
        assertTrue(result.error().contains("refunded"));
    }

    @Test
    @DisplayName("provider refusals surface as data, not exceptions")
    void providerRefusalIsData() {
        economy.balances.put(PAYER, 100.0);
        economy.rejectWithdrawals = true;

        EconomyTransactionDto result = service(economy).withdraw(PAYER, 10.0).join();

        assertFalse(result.success());
        assertEquals("scripted refusal", result.error());
    }

    @Test
    @DisplayName("invalid amounts fail the future before touching the provider")
    void invalidAmountsAreRejected() {
        PandasticEconomyServiceImpl service = service(economy);

        for (double bad : new double[]{0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> service.deposit(PAYER, bad).join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        assertEquals(0, economy.depositCalls);
    }

    @Test
    @DisplayName("a missing provider fails operations exceptionally but isEconomyAvailable stays normal")
    void missingProvider() {
        PandasticEconomyServiceImpl service = new PandasticEconomyServiceImpl(
                () -> null, Runnable::run, ForkJoinPool.commonPool(),
                PandasticEconomyServiceImplTest::offlinePlayer);

        assertFalse(service.isEconomyAvailable().join());

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service.getBalance(PAYER).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private static OfflinePlayer offlinePlayer(UUID id) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "player-" + id;
            case "toString" -> "FakeOfflinePlayer(" + id + ")";
            case "hashCode" -> id.hashCode();
            case "equals" -> args[0] instanceof OfflinePlayer other && other.getUniqueId().equals(id);
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, handler);
    }

    /**
     * In-memory Vault provider: balances in a map, scriptable refusals, call counting. The many
     * unused Vault methods throw so an unexpected code path fails the test instead of lying.
     */
    private static final class FakeEconomy implements Economy {

        final Map<UUID, Double> balances = new HashMap<>();
        boolean rejectWithdrawals;
        UUID rejectDepositsTo;
        int withdrawCalls;
        int depositCalls;

        private double balance(OfflinePlayer player) {
            return balances.getOrDefault(player.getUniqueId(), 0.0);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public @NotNull String getName() {
            return "FakeEconomy";
        }

        @Override
        public @NotNull String format(double amount) {
            return "$" + amount;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return balances.containsKey(player.getUniqueId());
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return balance(player);
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return balance(player) >= amount;
        }

        @Override
        public @NotNull EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            withdrawCalls++;
            if (rejectWithdrawals) {
                return new EconomyResponse(amount, balance(player),
                        EconomyResponse.ResponseType.FAILURE, "scripted refusal");
            }
            double balance = balance(player);
            if (balance < amount) {
                return new EconomyResponse(amount, balance,
                        EconomyResponse.ResponseType.FAILURE, "insufficient funds");
            }
            balances.put(player.getUniqueId(), balance - amount);
            return new EconomyResponse(amount, balance - amount, EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public @NotNull EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            depositCalls++;
            if (player.getUniqueId().equals(rejectDepositsTo)) {
                return new EconomyResponse(amount, balance(player),
                        EconomyResponse.ResponseType.FAILURE, "account locked");
            }
            double balance = balance(player) + amount;
            balances.put(player.getUniqueId(), balance);
            return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
        }

        // ---- everything below is intentionally unreachable in these tests ----

        @Override
        public boolean hasBankSupport() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int fractionalDigits() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull String currencyNamePlural() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull String currencyNameSingular() {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public boolean hasAccount(String playerName) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public boolean hasAccount(String playerName, String worldName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public double getBalance(String playerName) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public double getBalance(String playerName, String world) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public boolean has(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public boolean has(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public @NotNull EconomyResponse withdrawPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public @NotNull EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public @NotNull EconomyResponse depositPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public @NotNull EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public @NotNull EconomyResponse createBank(String name, String player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse createBank(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse deleteBank(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse bankBalance(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse bankHas(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse bankWithdraw(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse bankDeposit(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public @NotNull EconomyResponse isBankOwner(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse isBankOwner(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public @NotNull EconomyResponse isBankMember(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull EconomyResponse isBankMember(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull List<String> getBanks() {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public boolean createPlayerAccount(String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            throw new UnsupportedOperationException();
        }
    }
}
