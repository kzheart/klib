package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.util.Objects;
import org.bukkit.entity.Player;

/** 不链接 Vault API 的 Vault Economy 服务反射边界。 */
public final class VaultCurrency implements Currency {

    private final Object economy;

    public VaultCurrency(Object economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public BigDecimal balance(Player player) {
        Objects.requireNonNull(player, "player");
        Object value = ReflectiveCalls.invoke(economy, "getBalance", player);
        return Amounts.normalized((Number) value);
    }

    @Override
    public CurrencyResult take(Player player, BigDecimal amount) {
        return transact("withdrawPlayer", player, amount);
    }

    @Override
    public CurrencyResult give(Player player, BigDecimal amount) {
        return transact("depositPlayer", player, amount);
    }

    @Override
    public String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        requireDoubleExact(amount);
        return String.valueOf(ReflectiveCalls.invoke(economy, "format", Double.valueOf(amount.doubleValue())));
    }

    private CurrencyResult transact(String method, Player player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Amounts.requireNonNegative(amount);
        if (!isDoubleExact(amount)) {
            return CurrencyResult.failure(amount, method + " rejected: amount "
                    + amount.toPlainString() + " cannot be represented exactly as a double");
        }
        try {
            Object result = ReflectiveCalls.invoke(
                    economy,
                    method,
                    player,
                    Double.valueOf(amount.doubleValue()));
            return ReflectiveCalls.succeeded(result)
                    ? CurrencyResult.success(amount)
                    : CurrencyResult.failure(amount, method + " was rejected");
        } catch (RuntimeException failure) {
            return CurrencyResult.failure(amount, method + " failed", failure);
        }
    }

    /** Vault 仅支持 double；拒绝经 double 往返转换后会失真的金额。 */
    private static boolean isDoubleExact(BigDecimal amount) {
        return BigDecimal.valueOf(amount.doubleValue()).compareTo(amount) == 0;
    }

    private static void requireDoubleExact(BigDecimal amount) {
        if (!isDoubleExact(amount)) {
            throw new IllegalArgumentException("amount " + amount.toPlainString()
                    + " cannot be represented exactly as a double");
        }
    }
}
