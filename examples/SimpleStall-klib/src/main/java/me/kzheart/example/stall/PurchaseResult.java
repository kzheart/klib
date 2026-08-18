package me.kzheart.example.stall;

import java.math.BigDecimal;

/** 单元测试夹具与真实服务器夹具共享的稳定购买结果。 */
public final class PurchaseResult {
    public enum Status {
        COMPLETED,
        NOT_FOUND,
        SELF_PURCHASE,
        INVALID_AMOUNT,
        INSUFFICIENT_STOCK,
        INSUFFICIENT_FUNDS
    }

    private final Status status;
    private final BigDecimal charged;
    private final BigDecimal sellerIncome;
    private final int remainingStock;

    private PurchaseResult(
            Status status,
            BigDecimal charged,
            BigDecimal sellerIncome,
            int remainingStock
    ) {
        this.status = status;
        this.charged = charged;
        this.sellerIncome = sellerIncome;
        this.remainingStock = remainingStock;
    }

    public static PurchaseResult rejected(Status status, int remainingStock) {
        return new PurchaseResult(status, BigDecimal.ZERO, BigDecimal.ZERO, remainingStock);
    }

    public static PurchaseResult completed(
            BigDecimal charged,
            BigDecimal sellerIncome,
            int remainingStock
    ) {
        return new PurchaseResult(Status.COMPLETED, charged, sellerIncome, remainingStock);
    }

    public Status status() {
        return status;
    }

    public BigDecimal charged() {
        return charged;
    }

    public BigDecimal sellerIncome() {
        return sellerIncome;
    }

    public int remainingStock() {
        return remainingStock;
    }
}
