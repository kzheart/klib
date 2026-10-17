package me.kzheart.klib.hook.economy

import java.math.BigDecimal
import org.bukkit.entity.Player

/** 不链接 Vault API 的 Vault Economy 服务反射边界。 */
class VaultCurrency(private val economy: Any) : Currency {
    override fun id(): String = "vault"
    override fun balance(player: Player): BigDecimal =
        Amounts.normalized(ReflectiveCalls.invoke(economy, "getBalance", player) as Number)
    override fun take(player: Player, amount: BigDecimal): CurrencyResult = transact("withdrawPlayer", player, amount)
    override fun give(player: Player, amount: BigDecimal): CurrencyResult = transact("depositPlayer", player, amount)
    override fun format(amount: BigDecimal): String {
        require(isDoubleExact(amount)) { "amount ${amount.toPlainString()} cannot be represented exactly as a double" }
        return java.lang.String.valueOf(ReflectiveCalls.invoke(economy, "format", amount.toDouble()))
    }

    private fun transact(method: String, player: Player, amount: BigDecimal): CurrencyResult {
        Amounts.requireNonNegative(amount)
        if (!isDoubleExact(amount)) return CurrencyResult.failure(amount,
            "$method rejected: amount ${amount.toPlainString()} cannot be represented exactly as a double")
        return try {
            val result = ReflectiveCalls.invoke(economy, method, player, amount.toDouble())
            if (ReflectiveCalls.succeeded(result)) CurrencyResult.success(amount)
            else CurrencyResult.failure(amount, "$method was rejected")
        } catch (failure: RuntimeException) {
            CurrencyResult.failure(amount, "$method failed", failure)
        }
    }

    companion object {
        /** Vault 仅支持 double；拒绝经 double 往返转换后会失真的金额。 */
        private fun isDoubleExact(amount: BigDecimal): Boolean = BigDecimal.valueOf(amount.toDouble()).compareTo(amount) == 0
    }
}
