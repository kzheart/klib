package me.kzheart.example.stall;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 不使用二进制浮点数，复现原有的卖家价格加税费计算。 */
public final class ListingTerms {
    private final BigDecimal sellerUnitPrice;
    private final BigDecimal taxRate;
    private final PriceType priceType;

    private ListingTerms(BigDecimal sellerUnitPrice, BigDecimal taxRate, PriceType priceType) {
        if (sellerUnitPrice.signum() <= 0) {
            throw new IllegalArgumentException("sellerUnitPrice must be positive");
        }
        if (taxRate.signum() < 0) {
            throw new IllegalArgumentException("taxRate must not be negative");
        }
        if (priceType == PriceType.POINTS
                && sellerUnitPrice.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("points price must be an integer");
        }
        this.sellerUnitPrice = sellerUnitPrice;
        this.taxRate = taxRate;
        this.priceType = Objects.requireNonNull(priceType, "priceType");
    }

    public static ListingTerms of(
            BigDecimal sellerUnitPrice,
            BigDecimal taxRate,
            PriceType priceType
    ) {
        return new ListingTerms(
                Objects.requireNonNull(sellerUnitPrice, "sellerUnitPrice"),
                Objects.requireNonNull(taxRate, "taxRate"),
                priceType);
    }

    public BigDecimal sellerUnitPrice() {
        return sellerUnitPrice;
    }

    public BigDecimal listedUnitPrice() {
        BigDecimal taxed = sellerUnitPrice.multiply(BigDecimal.ONE.add(taxRate));
        return priceType == PriceType.POINTS
                ? taxed.setScale(0, RoundingMode.DOWN)
                : taxed;
    }

    public Settlement settle(int amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        BigDecimal sellerIncome = sellerUnitPrice.multiply(BigDecimal.valueOf(amount));
        BigDecimal buyerCharge = listedUnitPrice().multiply(BigDecimal.valueOf(amount));
        return new Settlement(buyerCharge, sellerIncome, buyerCharge.subtract(sellerIncome));
    }

    public PriceType priceType() {
        return priceType;
    }

    /** 单次购买的资金流转。 */
    public static final class Settlement {
        private final BigDecimal buyerCharge;
        private final BigDecimal sellerIncome;
        private final BigDecimal tax;

        private Settlement(BigDecimal buyerCharge, BigDecimal sellerIncome, BigDecimal tax) {
            this.buyerCharge = buyerCharge;
            this.sellerIncome = sellerIncome;
            this.tax = tax;
        }

        public BigDecimal buyerCharge() {
            return buyerCharge;
        }

        public BigDecimal sellerIncome() {
            return sellerIncome;
        }

        public BigDecimal tax() {
            return tax;
        }
    }
}
