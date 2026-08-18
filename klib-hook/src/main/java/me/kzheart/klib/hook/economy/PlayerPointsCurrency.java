package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/** PlayerPoints 基于 UUID 和整数 API 的反射边界。 */
public final class PlayerPointsCurrency implements Currency {

    private final Object api;

    public PlayerPointsCurrency(Object api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public String id() {
        return "playerpoints";
    }

    @Override
    public BigDecimal balance(Player player) {
        Object value = ReflectiveCalls.invoke(api, "look", playerId(player));
        return Amounts.normalized((Number) value);
    }

    @Override
    public CurrencyResult take(Player player, BigDecimal amount) {
        return transact("take", playerId(player), amount);
    }

    @Override
    public CurrencyResult give(Player player, BigDecimal amount) {
        return transact("give", playerId(player), amount);
    }

    @Override
    public String format(BigDecimal amount) {
        return new DecimalFormat("0").format(integerAmount(amount));
    }

    @Override
    public BigDecimal minorUnit() {
        return BigDecimal.ONE;
    }

    private CurrencyResult transact(String method, UUID playerId, BigDecimal amount) {
        int points;
        try {
            points = integerAmount(amount);
            Object result = ReflectiveCalls.invoke(api, method, playerId, Integer.valueOf(points));
            return ReflectiveCalls.succeeded(result)
                    ? CurrencyResult.success(amount)
                    : CurrencyResult.failure(amount, method + " was rejected");
        } catch (RuntimeException failure) {
            return CurrencyResult.failure(amount, method + " failed", failure);
        }
    }

    private static int integerAmount(BigDecimal amount) {
        Amounts.requireNonNegative(amount);
        try {
            return amount.intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("PlayerPoints amount must be an integer", failure);
        }
    }

    private static UUID playerId(Player player) {
        return Objects.requireNonNull(player, "player").getUniqueId();
    }
}
