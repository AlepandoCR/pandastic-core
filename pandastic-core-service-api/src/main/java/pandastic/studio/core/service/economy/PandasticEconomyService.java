package pandastic.studio.core.service.economy;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.AsyncPandasticService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Money operations for the whole server, backed by Vault's registered economy provider.
 *
 * <h2>Why this service is shared, async-shaped, and uncached</h2>
 * Vault's {@code Economy} contract is entirely synchronous and carries no cache of its own — it is
 * a facade over whichever economy plugin registered itself (EssentialsX, CMI, ...), whose balances
 * are mutated continuously by commands and other plugins with no invalidation signal. From that,
 * three decisions:
 * <ul>
 *     <li><b>Shared, not per-plugin.</b> Money is server-global state, and funneling every plugin
 *     through one leader-owned instance is what makes {@link #charge(UUID, double)} atomic: the
 *     balance check and the withdrawal run as a single main-thread task, so two plugins charging
 *     the same player cannot both pass the check first and overdraw second.</li>
 *     <li><b>Async API, main-thread execution.</b> Economy providers do not promise thread safety,
 *     so every operation here hops to the server main thread (running inline when already there).
 *     The returned futures therefore <em>complete on the main thread</em> — unlike the rest of the
 *     library's futures. Continuations may touch Bukkit API freely, and heavy work should be moved
 *     off-thread explicitly.</li>
 *     <li><b>No balance cache.</b> A cached balance would gate purchases on stale money the moment
 *     any command or plugin moves funds. Reads always hit the provider.</li>
 * </ul>
 *
 * <h2>Failure model</h2>
 * Economic refusals (insufficient funds, provider said no) are <b>data</b>: the future completes
 * normally with {@link EconomyTransactionDto#success()} {@code false}. Futures complete
 * <b>exceptionally</b> only for environment faults: no Vault economy provider is registered
 * ({@link IllegalStateException}), invalid arguments ({@link IllegalArgumentException}), or the
 * leader's plugin can no longer schedule. Check {@link #isEconomyAvailable()} once at startup
 * instead of catching per call.
 *
 * <p>No Vault type appears in this API: consumers need no Vault dependency at compile time. At
 * runtime the server needs Vault plus an economy provider installed.
 */
public interface PandasticEconomyService extends AsyncPandasticService {

    /**
     * Whether a Vault economy provider is currently registered. The only method that never
     * completes exceptionally over a missing provider.
     *
     * @return {@code true} when economy operations can be served
     */
    @NotNull CompletableFuture<Boolean> isEconomyAvailable();

    /**
     * Name of the registered economy provider, e.g. {@code "EssentialsX Economy"}.
     *
     * @return the provider's self-reported name
     */
    @NotNull CompletableFuture<String> getProviderName();

    /**
     * Whether the player has an account with the economy.
     *
     * @param playerId the player
     * @return {@code true} when the economy knows the player
     */
    @NotNull CompletableFuture<Boolean> hasAccount(@NotNull UUID playerId);

    /**
     * The player's current balance, read fresh from the provider.
     *
     * @param playerId the player
     * @return the balance
     */
    @NotNull CompletableFuture<Double> getBalance(@NotNull UUID playerId);

    /**
     * Whether the player holds at least {@code amount}.
     * <p>
     * For a check-then-take, do not call this followed by {@link #withdraw}: another plugin can
     * move money in between. Use {@link #charge}, which performs both atomically.
     *
     * @param playerId the player
     * @param amount   the amount to test for, {@code >= 0}
     * @return {@code true} when the balance covers the amount
     */
    @NotNull CompletableFuture<Boolean> has(@NotNull UUID playerId, double amount);

    /**
     * Takes {@code amount} from the player without a prior balance check — the provider itself
     * still refuses overdrafts according to its own rules.
     *
     * @param playerId the player
     * @param amount   the amount to take, {@code > 0}
     * @return the outcome
     */
    @NotNull CompletableFuture<EconomyTransactionDto> withdraw(@NotNull UUID playerId, double amount);

    /**
     * Gives {@code amount} to the player.
     *
     * @param playerId the player
     * @param amount   the amount to give, {@code > 0}
     * @return the outcome
     */
    @NotNull CompletableFuture<EconomyTransactionDto> deposit(@NotNull UUID playerId, double amount);

    /**
     * Charges the player: verifies the balance covers {@code amount} and withdraws it as one
     * atomic main-thread step. The refusal carries the current balance, so a "you need X more"
     * message costs no second call.
     *
     * @param playerId the player to charge
     * @param amount   the price, {@code > 0}
     * @return the outcome; {@code success() == false} with an error on insufficient funds
     */
    @NotNull CompletableFuture<EconomyTransactionDto> charge(@NotNull UUID playerId, double amount);

    /**
     * Moves {@code amount} from one player to another atomically: the withdrawal and the deposit
     * run in one main-thread task, and a deposit failure refunds the sender.
     *
     * @param fromPlayerId the payer
     * @param toPlayerId   the payee
     * @param amount       the amount to move, {@code > 0}
     * @return the outcome; {@code newBalance()} is the <em>payer's</em> balance afterwards
     */
    @NotNull CompletableFuture<EconomyTransactionDto> transfer(@NotNull UUID fromPlayerId,
                                                               @NotNull UUID toPlayerId,
                                                               double amount);

    /**
     * Formats an amount the way the economy displays money, e.g. {@code "$1,000.50"}.
     *
     * @param amount the amount to format
     * @return the provider-formatted string
     */
    @NotNull CompletableFuture<String> format(double amount);
}
