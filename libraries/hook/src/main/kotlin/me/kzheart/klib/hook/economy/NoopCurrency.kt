package me.kzheart.klib.hook.economy

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import org.bukkit.entity.Player

/** 配置的经济插件缺失时使用的明确回退实现。 */
class NoopCurrency(private val id: String) : Currency {
    override fun id(): String = id
    override fun balance(player: Player): BigDecimal = BigDecimal.ZERO
    override fun take(player: Player, amount: BigDecimal): CurrencyResult {
        Amounts.requireNonNegative(amount)
        return CurrencyResult.failure(amount, "$id is unavailable")
    }
    override fun give(player: Player, amount: BigDecimal): CurrencyResult {
        Amounts.requireNonNegative(amount)
        return CurrencyResult.failure(amount, "$id is unavailable")
    }
    override fun format(amount: BigDecimal): String =
        DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount)
}
