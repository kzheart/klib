package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 一次余额修改的结果，包括补偿失败信息。 */
public final class CurrencyResult {

    private final boolean success;
    private final BigDecimal amount;
    private final String message;
    private final Throwable cause;
    private final boolean compensated;
    private final List<Uncompensated> uncompensated;

    private CurrencyResult(
            boolean success,
            BigDecimal amount,
            String message,
            Throwable cause,
            boolean compensated,
            List<Uncompensated> uncompensated
    ) {
        this.success = success;
        this.amount = Objects.requireNonNull(amount, "amount");
        this.message = Objects.requireNonNull(message, "message");
        this.cause = cause;
        this.compensated = compensated;
        this.uncompensated = Collections.unmodifiableList(
                new ArrayList<Uncompensated>(uncompensated));
    }

    public static CurrencyResult success(BigDecimal amount) {
        return new CurrencyResult(true, amount, "ok", null, true,
                Collections.<Uncompensated>emptyList());
    }

    public static CurrencyResult failure(BigDecimal amount, String message) {
        return new CurrencyResult(false, amount, message, null, true,
                Collections.<Uncompensated>emptyList());
    }

    public static CurrencyResult failure(BigDecimal amount, String message, Throwable cause) {
        return new CurrencyResult(false, amount, message, cause, true,
                Collections.<Uncompensated>emptyList());
    }

    static CurrencyResult rollbackFailure(
            BigDecimal amount,
            String message,
            Throwable cause,
            List<Uncompensated> uncompensated
    ) {
        return new CurrencyResult(false, amount, message, cause, false, uncompensated);
    }

    public boolean success() {
        return success;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String message() {
        return message;
    }

    public Throwable cause() {
        return cause;
    }

    public boolean compensated() {
        return compensated;
    }

    /** 组合支付失败后无法补偿的扣款。 */
    public List<Uncompensated> uncompensated() {
        return uncompensated;
    }

    /** 因补偿失败而残留在玩家账户上的一笔扣款。 */
    public static final class Uncompensated {

        private final String currencyId;
        private final BigDecimal amount;

        Uncompensated(String currencyId, BigDecimal amount) {
            this.currencyId = Objects.requireNonNull(currencyId, "currencyId");
            this.amount = Objects.requireNonNull(amount, "amount");
        }

        public String currencyId() {
            return currencyId;
        }

        public BigDecimal amount() {
            return amount;
        }
    }
}
