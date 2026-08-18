package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/** 不公开 XConomy 类的 API 反射边界。 */
public final class XConomyCurrency implements Currency {

    private final Object api;

    public XConomyCurrency(Object api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public String id() {
        return "xconomy";
    }

    @Override
    public BigDecimal balance(Player player) {
        UUID playerId = playerId(player);
        Object value;
        if (ReflectiveCalls.hasMethod(api, "getBalance", playerId)) {
            value = ReflectiveCalls.invoke(api, "getBalance", playerId);
        } else {
            value = ReflectiveCalls.invoke(playerData(playerId), "getBalance");
        }
        return Amounts.normalized((Number) value);
    }

    @Override
    public CurrencyResult take(Player player, BigDecimal amount) {
        return transact(playerId(player), "withdraw", amount);
    }

    @Override
    public CurrencyResult give(Player player, BigDecimal amount) {
        return transact(playerId(player), "deposit", amount);
    }

    @Override
    public String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (ReflectiveCalls.hasMethod(api, "format", amount)) {
            return String.valueOf(ReflectiveCalls.invoke(api, "format", amount));
        }
        return new DecimalFormat("0.##").format(amount);
    }

    private CurrencyResult transact(UUID playerId, String method, BigDecimal amount) {
        Amounts.requireNonNegative(amount);
        try {
            Object result;
            if (ReflectiveCalls.hasMethod(api, method, playerId, amount)) {
                result = ReflectiveCalls.invoke(api, method, playerId, amount);
            } else {
                result = ReflectiveCalls.invoke(playerData(playerId), method, amount);
            }
            return ReflectiveCalls.succeeded(result)
                    ? CurrencyResult.success(amount)
                    : CurrencyResult.failure(amount, method + " was rejected");
        } catch (RuntimeException failure) {
            return CurrencyResult.failure(amount, method + " failed", failure);
        }
    }

    private Object playerData(UUID playerId) {
        return ReflectiveCalls.invoke(api, "getPlayerData", playerId);
    }

    private static UUID playerId(Player player) {
        return Objects.requireNonNull(player, "player").getUniqueId();
    }
}
