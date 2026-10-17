package me.kzheart.klib.hook.economy

import java.math.BigDecimal
import java.util.ArrayList
import java.util.Collections

/** 一次余额修改的结果，包括补偿失败信息。 */
class CurrencyResult private constructor(private val success: Boolean, private val amount: BigDecimal,
        private val message: String, private val cause: Throwable?, private val compensated: Boolean,
        uncompensated: List<Uncompensated>) {
    private val uncompensated: List<Uncompensated> = Collections.unmodifiableList(ArrayList(uncompensated))
    fun success(): Boolean = success
    fun amount(): BigDecimal = amount
    fun message(): String = message
    fun cause(): Throwable? = cause
    fun compensated(): Boolean = compensated
    /** 组合支付失败后无法补偿的扣款。 */
    fun uncompensated(): List<Uncompensated> = uncompensated

    class Uncompensated internal constructor(private val currencyId: String, private val amount: BigDecimal) {
        fun currencyId(): String = currencyId
        fun amount(): BigDecimal = amount
    }

    companion object {
        @JvmStatic fun success(amount: BigDecimal): CurrencyResult =
            CurrencyResult(true, amount, "ok", null, true, emptyList())
        @JvmStatic fun failure(amount: BigDecimal, message: String): CurrencyResult =
            CurrencyResult(false, amount, message, null, true, emptyList())
        @JvmStatic fun failure(amount: BigDecimal, message: String, cause: Throwable?): CurrencyResult =
            CurrencyResult(false, amount, message, cause, true, emptyList())
        @JvmStatic fun rollbackFailure(amount: BigDecimal, message: String, cause: Throwable,
                uncompensated: List<Uncompensated>): CurrencyResult =
            CurrencyResult(false, amount, message, cause, false, uncompensated)
    }
}
