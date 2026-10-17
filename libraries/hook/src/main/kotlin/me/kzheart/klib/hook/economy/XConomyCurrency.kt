package me.kzheart.klib.hook.economy

import java.math.BigDecimal
import java.text.DecimalFormat
import java.util.UUID
import org.bukkit.entity.Player

/** 不公开 XConomy 类的 API 反射边界。 */
class XConomyCurrency(private val api: Any) : Currency {
    override fun id(): String = "xconomy"
    override fun balance(player: Player): BigDecimal {
        val playerId = player.uniqueId
        val value = if (ReflectiveCalls.hasMethod(api, "getBalance", playerId)) {
            ReflectiveCalls.invoke(api, "getBalance", playerId)
        } else {
            ReflectiveCalls.invoke(playerData(playerId), "getBalance")
        }
        return Amounts.normalized(value as Number)
    }
    override fun take(player: Player, amount: BigDecimal): CurrencyResult = transact(player.uniqueId, "withdraw", amount)
    override fun give(player: Player, amount: BigDecimal): CurrencyResult = transact(player.uniqueId, "deposit", amount)
    override fun format(amount: BigDecimal): String = if (ReflectiveCalls.hasMethod(api, "format", amount)) {
        java.lang.String.valueOf(ReflectiveCalls.invoke(api, "format", amount))
    } else DecimalFormat("0.##").format(amount)

    private fun transact(playerId: UUID, method: String, amount: BigDecimal): CurrencyResult {
        Amounts.requireNonNegative(amount)
        return try {
            val result = if (ReflectiveCalls.hasMethod(api, method, playerId, amount)) {
                ReflectiveCalls.invoke(api, method, playerId, amount)
            } else ReflectiveCalls.invoke(playerData(playerId), method, amount)
            if (ReflectiveCalls.succeeded(result)) CurrencyResult.success(amount)
            else CurrencyResult.failure(amount, "$method was rejected")
        } catch (failure: RuntimeException) {
            CurrencyResult.failure(amount, "$method failed", failure)
        }
    }

    private fun playerData(playerId: UUID): Any = ReflectiveCalls.invoke(api, "getPlayerData", playerId)!!
}
