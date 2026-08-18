package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.util.Objects;

final class Amounts {

    private Amounts() {
    }

    static BigDecimal requireNonNegative(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return amount;
    }

    static BigDecimal normalized(Number value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }
}
