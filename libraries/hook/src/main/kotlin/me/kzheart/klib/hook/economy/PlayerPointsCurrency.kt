package me.kzheart.klib.hook.economy

import java.math.BigDecimal
import java.text.DecimalFormat
import java.util.UUID
import org.bukkit.entity.Player

/** PlayerPoints 基于 UUID 和整数 API 的反射边界。 */
class PlayerPointsCurrency(private val api: Any) : Currency {
    override fun id(): String = "playerpoints"
    override fun balance(player: Player): BigDecimal =
        Amounts.normalized(ReflectiveCalls.invoke(api, "look", player.uniqueId) as Number)
    override fun take(player: Player, amount: BigDecimal): CurrencyResult = transact("take", player.uniqueId, amount)
    override fun give(player: Player, amount: BigDecimal): CurrencyResult = transact("give", player.uniqueId, amount)
    override fun format(amount: BigDecimal): String = DecimalFormat("0").format(integerAmount(amount))
    override fun minorUnit(): BigDecimal = BigDecimal.ONE

    private fun transact(method: String, playerId: UUID, amount: BigDecimal): CurrencyResult = try {
        val result = ReflectiveCalls.invoke(api, method, playerId, integerAmount(amount))
        if (ReflectiveCalls.succeeded(result)) CurrencyResult.success(amount)
        else CurrencyResult.failure(amount, "$method was rejected")
    } catch (failure: RuntimeException) {
        CurrencyResult.failure(amount, "$method failed", failure)
    }

    companion object {
        private fun integerAmount(amount: BigDecimal): Int {
            Amounts.requireNonNegative(amount)
            return try { amount.intValueExact() } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("PlayerPoints amount must be an integer", failure)
            }
        }
    }
}
