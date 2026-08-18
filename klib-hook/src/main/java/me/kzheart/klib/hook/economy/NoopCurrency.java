package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.entity.Player;

/** 配置的经济插件缺失时使用的明确回退实现。 */
public final class NoopCurrency implements Currency {

    private final String id;

    public NoopCurrency(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BigDecimal balance(Player player) {
        Objects.requireNonNull(player, "player");
        return BigDecimal.ZERO;
    }

    @Override
    public CurrencyResult take(Player player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Amounts.requireNonNegative(amount);
        return CurrencyResult.failure(amount, id + " is unavailable");
    }

    @Override
    public CurrencyResult give(Player player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        Amounts.requireNonNegative(amount);
        return CurrencyResult.failure(amount, id + " is unavailable");
    }

    @Override
    public String format(BigDecimal amount) {
        return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT))
                .format(Objects.requireNonNull(amount, "amount"));
    }
}
