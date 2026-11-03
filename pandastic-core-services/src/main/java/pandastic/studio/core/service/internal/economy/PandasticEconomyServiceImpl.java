package pandastic.studio.core.service.internal.economy;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.service.economy.EconomyTransactionDto;
import pandastic.studio.core.service.economy.PandasticEconomyService;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Vault-backed {@link PandasticEconomyService}, owned by the leader runtime.
 *
 * <p>Every operation runs as one task on the <b>main-thread executor</b> (inline when the caller
 * is already the main thread): Vault's contract is synchronous and economy providers do not
 * promise thread safety, so the main thread is the only universally safe place to call them.
 * Running {@code charge} and {@code transfer} as single tasks is also what makes them atomic —
 * no other plugin's economy call can interleave between their check and their move.
 *
 * <p>The Vault provider is resolved <b>per operation</b>, never cached: economy plugins can be
 * reloaded and re-register a new provider at any time, and a cached reference would keep serving
 * the dead one.
 *
 * <p>All collaborators are injected so the class tests without a server; production wiring lives
 * in {@link #vaultBacked(Plugin, ExecutorService)}.
 */
public final class PandasticEconomyServiceImpl implements PandasticEconomyService {

    /**
     * The one name this service registers under; shared services need no per-plugin suffix.
     */
    private static final String SERVICE_NAME = "EconomyService";

    private static final String NO_PROVIDER =
            "No Vault economy provider is registered. Install Vault plus an economy plugin"
                    + " (EssentialsX, CMI, ...) or gate calls on isEconomyAvailable().";

    private final Supplier<@Nullable Economy> economyResolver;
    private final Executor mainThread;
    private final ExecutorService asyncPool;
    private final Function<UUID, OfflinePlayer> accountResolver;

    /**
     * Test-friendly constructor; production code uses {@link #vaultBacked(Plugin, ExecutorService)}.
     *
     * @param economyResolver yields the current Vault provider, or {@code null} when none is
     *                        registered; invoked once per operation, on the main-thread executor
     * @param mainThread      executor that runs tasks on the server main thread
     * @param asyncPool       the shared pool exposed through {@link #getExecutorService()}
     * @param accountResolver maps a player id to the {@link OfflinePlayer} handle Vault consumes
     */
    public PandasticEconomyServiceImpl(@NotNull Supplier<@Nullable Economy> economyResolver,
                                 @NotNull Executor mainThread,
                                 @NotNull ExecutorService asyncPool,
                                 @NotNull Function<UUID, OfflinePlayer> accountResolver) {
        this.economyResolver = Objects.requireNonNull(economyResolver, "economyResolver");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.asyncPool = Objects.requireNonNull(asyncPool, "asyncPool");
        this.accountResolver = Objects.requireNonNull(accountResolver, "accountResolver");
    }

    /**
     * Production wiring: Bukkit's services manager for the provider, the Bukkit scheduler for the
     * main-thread hop (running inline when already on the main thread), and
     * {@code Server#getOfflinePlayer(UUID)} for accounts.
     *
     * @param owner     the leader's plugin, used to schedule main-thread tasks
     * @param asyncPool the shared runtime pool
     * @return a Vault-backed service
     */
    public static @NotNull PandasticEconomyServiceImpl vaultBacked(
            @NotNull Plugin owner,
            @NotNull ExecutorService asyncPool
    ) {
        Objects.requireNonNull(owner, "owner");
        return new PandasticEconomyServiceImpl(
                () -> {
                    RegisteredServiceProvider<Economy> registration =
                            owner.getServer().getServicesManager().getRegistration(Economy.class);
                    return registration == null ? null : registration.getProvider();
                },
                // The ONE sanctioned Bukkit-scheduler use in the whole library. Economy is the
                // single capability that semantically REQUIRES the main thread (Vault providers
                // promise no thread safety), so unlike database/config/profile work it cannot own
                // a dedicated thread. The inline fast path is what keeps the library's
                // "usable during onEnable/onDisable" rule intact: both run on the main thread, so
                // enable/disable callers never touch the scheduler at all. The scheduler is only
                // the cross-thread hop for mid-game async callers; if it refuses (owner disabled,
                // server stopping), onMainThread() turns that into an exceptional future — the
                // call fails loudly rather than hanging.
                task -> {
                    if (owner.getServer().isPrimaryThread()) {
                        task.run();
                    } else {
                        owner.getServer().getScheduler().runTask(owner, task);
                    }
                },
                asyncPool,
                playerId -> owner.getServer().getOfflinePlayer(playerId)
        );
    }

    @Override
    public @NotNull CompletableFuture<Boolean> isEconomyAvailable() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            mainThread.execute(() -> future.complete(economyResolver.get() != null));
        } catch (RuntimeException schedulingFailure) {
            future.completeExceptionally(schedulingFailure);
        }
        return future;
    }

    @Override
    public @NotNull CompletableFuture<String> getProviderName() {
        return onMainThread(Economy::getName);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasAccount(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return onMainThread(economy -> economy.hasAccount(accountResolver.apply(playerId)));
    }

    @Override
    public @NotNull CompletableFuture<Double> getBalance(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return onMainThread(economy -> economy.getBalance(accountResolver.apply(playerId)));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> has(@NotNull UUID playerId, double amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (invalidAmount(amount, true)) return invalidAmountFuture(amount);
        return onMainThread(economy -> economy.has(accountResolver.apply(playerId), amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyTransactionDto> withdraw(@NotNull UUID playerId, double amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (invalidAmount(amount, false)) return invalidAmountFuture(amount);
        return onMainThread(economy ->
                toDto(economy.withdrawPlayer(accountResolver.apply(playerId), amount)));
    }

    @Override
    public @NotNull CompletableFuture<EconomyTransactionDto> deposit(@NotNull UUID playerId, double amount) {
        Objects.requireNonNull(playerId, "playerId");
        // A negative deposit would be a withdrawal that bypasses every charge check; refuse it
        // here rather than trusting each provider to.
        if (invalidAmount(amount, false)) return invalidAmountFuture(amount);
        return onMainThread(economy ->
                toDto(economy.depositPlayer(accountResolver.apply(playerId), amount)));
    }

    @Override
    public @NotNull CompletableFuture<EconomyTransactionDto> charge(@NotNull UUID playerId, double amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (invalidAmount(amount, false)) return invalidAmountFuture(amount);
        return onMainThread(economy -> {
            OfflinePlayer account = accountResolver.apply(playerId);
            // Check and withdrawal inside ONE main-thread task: nothing can move money in between.
            if (!economy.has(account, amount)) {
                return EconomyTransactionDto.refused(amount, economy.getBalance(account),
                        "Insufficient funds: the balance does not cover " + amount + ".");
            }
            return toDto(economy.withdrawPlayer(account, amount));
        });
    }

    @Override
    public @NotNull CompletableFuture<EconomyTransactionDto> transfer(
            @NotNull UUID fromPlayerId,
            @NotNull UUID toPlayerId,
            double amount
    ) {
        Objects.requireNonNull(fromPlayerId, "fromPlayerId");
        Objects.requireNonNull(toPlayerId, "toPlayerId");
        if (invalidAmount(amount, false)) return invalidAmountFuture(amount);
        return onMainThread(economy -> {
            OfflinePlayer from = accountResolver.apply(fromPlayerId);
            OfflinePlayer to = accountResolver.apply(toPlayerId);

            if (!economy.has(from, amount)) {
                return EconomyTransactionDto.refused(amount, economy.getBalance(from),
                        "Insufficient funds: the payer's balance does not cover " + amount + ".");
            }

            EconomyResponse withdrawal = economy.withdrawPlayer(from, amount);
            if (!withdrawal.transactionSuccess()) {
                return toDto(withdrawal);
            }

            EconomyResponse depositLeg = economy.depositPlayer(to, amount);
            if (depositLeg.transactionSuccess()) {
                return EconomyTransactionDto.ok(amount, withdrawal.balance);
            }

            // The deposit leg failed after the withdrawal succeeded: refund the payer. All of
            // this is still the same main-thread task, so no outside call observes the gap.
            EconomyResponse refund = economy.depositPlayer(from, amount);
            String error = "Transfer failed on the deposit leg ("
                    + (depositLeg.errorMessage == null ? "no reason given" : depositLeg.errorMessage) + ")"
                    + (refund.transactionSuccess()
                            ? "; the payer was refunded."
                            : "; REFUND ALSO FAILED (" + refund.errorMessage + ") — the payer is short by " + amount + ".");
            double payerBalance = refund.transactionSuccess() ? refund.balance : withdrawal.balance;
            return EconomyTransactionDto.refused(amount, payerBalance, error);
        });
    }

    @Override
    public @NotNull CompletableFuture<String> format(double amount) {
        return onMainThread(economy -> economy.format(amount));
    }

    @Override
    public @NotNull ExecutorService getExecutorService() {
        return asyncPool;
    }

    @Override
    public @NotNull String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public @NotNull Component getName() {
        return Component.text("Economy Service");
    }

    @Override
    public @NotNull Component getDescription() {
        return Component.text("Server-wide money operations through Vault, shared by every plugin.");
    }

    /**
     * Runs one economy operation on the main-thread executor, resolving the provider inside the
     * task. Environment faults (no provider, scheduling failure, a provider throwing) complete
     * the future exceptionally; economic refusals are returned as data by the callers.
     */
    private <T> @NotNull CompletableFuture<T> onMainThread(@NotNull Function<Economy, T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                Economy economy = economyResolver.get();
                if (economy == null) {
                    future.completeExceptionally(new IllegalStateException(NO_PROVIDER));
                    return;
                }
                future.complete(operation.apply(economy));
            } catch (Throwable providerFailure) {
                // The provider is third-party code; anything it throws must land in the future,
                // never on the scheduler thread.
                future.completeExceptionally(providerFailure);
            }
        };
        try {
            mainThread.execute(task);
        } catch (RuntimeException schedulingFailure) {
            // E.g. the owning plugin was disabled and the scheduler refuses new tasks.
            future.completeExceptionally(schedulingFailure);
        }
        return future;
    }

    private static @NotNull EconomyTransactionDto toDto(@NotNull EconomyResponse response) {
        if (response.transactionSuccess()) {
            return EconomyTransactionDto.ok(response.amount, response.balance);
        }
        return EconomyTransactionDto.refused(response.amount, response.balance,
                response.errorMessage == null ? "The economy provider refused the transaction." : response.errorMessage);
    }

    /**
     * @param zeroAllowed whether an amount of exactly zero is acceptable (reads yes, moves no)
     */
    private static boolean invalidAmount(double amount, boolean zeroAllowed) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) return true;
        return zeroAllowed ? amount < 0 : amount <= 0;
    }

    private static <T> @NotNull CompletableFuture<T> invalidAmountFuture(double amount) {
        return CompletableFuture.failedFuture(new IllegalArgumentException(
                "Amount must be a finite, positive number; got " + amount + "."));
    }
}
