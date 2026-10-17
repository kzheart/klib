package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import org.bukkit.entity.Player;

/** 独立于任何具体经济插件 API 的货币。 */
public interface Currency {

    String id();

    BigDecimal balance(Player player);

    default boolean has(Player player, BigDecimal amount) {
        Amounts.requireNonNegative(amount);
        return balance(player).compareTo(amount) >= 0;
    }

    CurrencyResult take(Player player, BigDecimal amount);

    CurrencyResult give(Player player, BigDecimal amount);

    String format(BigDecimal amount);

    /**
     * 该货币可扣除或存入的最小金额；任意精度时返回 {@code null}。
     * 整数货币应返回 {@link BigDecimal#ONE}，使组合规划向下取整其份额，
     * 避免发起注定失败的扣款。
     */
    default BigDecimal minorUnit() {
        return null;
    }
}
