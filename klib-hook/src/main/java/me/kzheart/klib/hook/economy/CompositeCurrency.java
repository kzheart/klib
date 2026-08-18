package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/** 按顺序从多种货币中付款，并在失败时补偿所有已完成的扣款。 */
public final class CompositeCurrency implements Currency {

    private final String id;
    private final List<Currency> currencies;

    public CompositeCurrency(String id, List<? extends Currency> currencies) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currencies, "currencies");
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("currencies must not be empty");
        }
        this.currencies = Collections.unmodifiableList(new ArrayList<Currency>(currencies));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BigDecimal balance(Player player) {
        Objects.requireNonNull(player, "player");
        BigDecimal total = BigDecimal.ZERO;
        for (Currency currency : currencies) {
            total = total.add(currency.balance(player));
        }
        return total;
    }

    @Override
    public CurrencyResult take(Player player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Amounts.requireNonNegative(amount);
        if (amount.signum() == 0) {
            return CurrencyResult.success(amount);
        }

        List<Debit> plan;
        try {
            plan = plan(player, amount);
        } catch (RuntimeException failure) {
            return CurrencyResult.failure(amount, "failed to inspect combined balance", failure);
        }
        if (plan == null) {
            return CurrencyResult.failure(amount, "insufficient combined balance");
        }

        List<Debit> completed = new ArrayList<Debit>();
        for (Debit debit : plan) {
            CurrencyResult result;
            try {
                result = debit.currency.take(player, debit.amount);
            } catch (RuntimeException failure) {
                result = CurrencyResult.failure(
                        debit.amount,
                        "debit threw for " + debit.currency.id(),
                        failure);
            }
            if (!result.success()) {
                return rollback(player, amount, completed, result);
            }
            completed.add(debit);
        }
        return CurrencyResult.success(amount);
    }

    @Override
    public CurrencyResult give(Player player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Amounts.requireNonNegative(amount);
        return currencies.get(0).give(player, amount);
    }

    @Override
    public String format(BigDecimal amount) {
        return currencies.get(0).format(amount);
    }

    public List<Currency> currencies() {
        return currencies;
    }

    private List<Debit> plan(Player player, BigDecimal amount) {
        List<Debit> plan = new ArrayList<Debit>();
        BigDecimal remaining = amount;
        for (Currency currency : currencies) {
            BigDecimal available = currency.balance(player).max(BigDecimal.ZERO);
            BigDecimal debit = available.min(remaining);
            BigDecimal unit = currency.minorUnit();
            if (unit != null && debit.signum() > 0) {
                // 整数（或有最小单位的）货币向下取整，余数推给后续货币。
                debit = debit.divideToIntegralValue(unit).multiply(unit);
            }
            if (debit.signum() > 0) {
                plan.add(new Debit(currency, debit));
                remaining = remaining.subtract(debit);
            }
            if (remaining.signum() == 0) {
                return plan;
            }
        }
        return null;
    }

    private CurrencyResult rollback(
            Player player,
            BigDecimal requested,
            List<Debit> completed,
            CurrencyResult failure
    ) {
        Throwable rollbackFailure = null;
        List<CurrencyResult.Uncompensated> uncompensated =
                new ArrayList<CurrencyResult.Uncompensated>();
        for (int index = completed.size() - 1; index >= 0; index--) {
            Debit debit = completed.get(index);
            Throwable current = null;
            try {
                CurrencyResult compensation = debit.currency.give(player, debit.amount);
                if (!compensation.success()) {
                    current = new IllegalStateException(
                            "Failed to rollback " + debit.currency.id() + ": "
                                    + compensation.message(),
                            compensation.cause());
                }
            } catch (RuntimeException compensationFailure) {
                current = new IllegalStateException(
                        "Rollback threw for " + debit.currency.id(),
                        compensationFailure);
            }
            if (current != null) {
                uncompensated.add(new CurrencyResult.Uncompensated(
                        debit.currency.id(), debit.amount));
                if (rollbackFailure == null) {
                    rollbackFailure = current;
                } else {
                    rollbackFailure.addSuppressed(current);
                }
            }
        }
        if (rollbackFailure != null) {
            return CurrencyResult.rollbackFailure(
                    requested,
                    "debit failed and compensation was incomplete",
                    rollbackFailure,
                    uncompensated);
        }
        return CurrencyResult.failure(requested, failure.message(), failure.cause());
    }

    private static final class Debit {
        private final Currency currency;
        private final BigDecimal amount;

        private Debit(Currency currency, BigDecimal amount) {
            this.currency = currency;
            this.amount = amount;
        }
    }
}
