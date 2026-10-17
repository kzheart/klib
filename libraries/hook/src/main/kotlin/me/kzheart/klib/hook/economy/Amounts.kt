package me.kzheart.klib.hook.economy

import java.math.BigDecimal

internal class Amounts private constructor() {
    companion object {
        @JvmStatic fun requireNonNegative(amount: BigDecimal): BigDecimal {
            require(amount.signum() >= 0) { "amount must not be negative" }
            return amount
        }
        @JvmStatic fun normalized(value: Number): BigDecimal =
            if (value is BigDecimal) value else BigDecimal(value.toString())
    }
}
