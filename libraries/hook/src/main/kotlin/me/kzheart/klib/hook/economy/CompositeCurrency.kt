package me.kzheart.klib.hook.economy

import java.math.BigDecimal
import java.util.ArrayList
import java.util.Collections
import org.bukkit.entity.Player

/** 按顺序从多种货币中付款，并在失败时补偿所有已完成的扣款。 */
class CompositeCurrency(private val id: String, currencies: List<Currency>) : Currency {
    private val currencies: List<Currency>
    init {
        require(currencies.isNotEmpty()) { "currencies must not be empty" }
        this.currencies = Collections.unmodifiableList(ArrayList(currencies))
    }
    override fun id(): String = id
    override fun balance(player: Player): BigDecimal = currencies.fold(BigDecimal.ZERO) { total, currency ->
        total.add(currency.balance(player))
    }

    override fun take(player: Player, amount: BigDecimal): CurrencyResult {
        Amounts.requireNonNegative(amount)
        if (amount.signum() == 0) return CurrencyResult.success(amount)
        val plan = try { plan(player, amount) } catch (failure: RuntimeException) {
            return CurrencyResult.failure(amount, "failed to inspect combined balance", failure)
        } ?: return CurrencyResult.failure(amount, "insufficient combined balance")
        val completed = ArrayList<Debit>()
        for (debit in plan) {
            val result = try { debit.currency.take(player, debit.amount) } catch (failure: RuntimeException) {
                CurrencyResult.failure(debit.amount, "debit threw for " + debit.currency.id(), failure)
            }
            if (!result.success()) return rollback(player, amount, completed, result)
            completed.add(debit)
        }
        return CurrencyResult.success(amount)
    }
    override fun give(player: Player, amount: BigDecimal): CurrencyResult {
        Amounts.requireNonNegative(amount)
        return currencies[0].give(player, amount)
    }
    override fun format(amount: BigDecimal): String = currencies[0].format(amount)
    fun currencies(): List<Currency> = currencies

    private fun plan(player: Player, amount: BigDecimal): List<Debit>? {
        val plan = ArrayList<Debit>()
        var remaining = amount
        for (currency in currencies) {
            var debit = currency.balance(player).max(BigDecimal.ZERO).min(remaining)
            val unit = currency.minorUnit()
            if (unit != null && debit.signum() > 0) {
                // 整数（或有最小单位的）货币向下取整，余数推给后续货币。
                debit = debit.divideToIntegralValue(unit).multiply(unit)
            }
            if (debit.signum() > 0) {
                plan.add(Debit(currency, debit))
                remaining = remaining.subtract(debit)
            }
            if (remaining.signum() == 0) return plan
        }
        return null
    }

    private fun rollback(player: Player, requested: BigDecimal, completed: List<Debit>,
                         failure: CurrencyResult): CurrencyResult {
        var rollbackFailure: Throwable? = null
        val uncompensated = ArrayList<CurrencyResult.Uncompensated>()
        for (index in completed.indices.reversed()) {
            val debit = completed[index]
            val current = try {
                val compensation = debit.currency.give(player, debit.amount)
                if (compensation.success()) null else IllegalStateException(
                    "Failed to rollback ${debit.currency.id()}: ${compensation.message()}", compensation.cause())
            } catch (compensationFailure: RuntimeException) {
                IllegalStateException("Rollback threw for " + debit.currency.id(), compensationFailure)
            }
            if (current != null) {
                uncompensated.add(CurrencyResult.Uncompensated(debit.currency.id(), debit.amount))
                if (rollbackFailure == null) rollbackFailure = current else rollbackFailure.addSuppressed(current)
            }
        }
        return if (rollbackFailure != null) CurrencyResult.rollbackFailure(requested,
            "debit failed and compensation was incomplete", rollbackFailure, uncompensated)
        else CurrencyResult.failure(requested, failure.message(), failure.cause())
    }

    private class Debit(val currency: Currency, val amount: BigDecimal)
}
