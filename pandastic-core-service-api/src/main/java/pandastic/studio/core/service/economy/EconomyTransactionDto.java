package pandastic.studio.core.service.economy;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.util.api.data.Dto;

/**
 * Outcome of a money-moving operation, crossing the classloader boundary by value.
 *
 * <p>A transaction that the economy <em>refused</em> (insufficient funds, provider rejection) is a
 * normal, successful future completing with {@code success() == false} and an {@link #error()}
 * message — it is data, not an exception. Futures only complete exceptionally for environment
 * problems: no Vault provider installed, or the owning plugin unable to schedule (see
 * {@link PandasticEconomyService}).
 *
 * @param success    whether the money actually moved
 * @param amount     the amount the operation was asked to move
 * @param newBalance the payer's balance after the operation (or after the refusal, unchanged)
 * @param error      human-readable reason when {@code success} is {@code false}, {@code null} otherwise
 */
public record EconomyTransactionDto(
        boolean success,
        double amount,
        double newBalance,
        @Nullable String error
) implements Dto {

    /**
     * A completed transaction.
     *
     * @param amount     the amount moved
     * @param newBalance the payer's balance after the move
     * @return a successful result
     */
    @Contract("_, _ -> new")
    public static @NotNull EconomyTransactionDto ok(double amount, double newBalance) {
        return new EconomyTransactionDto(true, amount, newBalance, null);
    }

    /**
     * A refused transaction. No money moved.
     *
     * @param amount         the amount that was requested
     * @param currentBalance the payer's balance, unchanged
     * @param error          why the economy refused
     * @return a failed result
     */
    @Contract("_, _, _ -> new")
    public static @NotNull EconomyTransactionDto refused(double amount, double currentBalance, @NotNull String error) {
        return new EconomyTransactionDto(false, amount, currentBalance, error);
    }
}
