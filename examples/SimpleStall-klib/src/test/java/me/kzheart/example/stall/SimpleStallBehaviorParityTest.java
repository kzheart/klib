package me.kzheart.example.stall;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimpleStallBehaviorParityTest {
    private static final UUID SELLER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BUYER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void locksMigrationCommandAndOriginalPriceTypeSurface() {
        assertEquals("simplestall", StallContract.ROOT_COMMAND);
        assertEquals("ss", StallContract.COMMAND_ALIAS);
        assertEquals(Arrays.asList(
                "help", "reload", "manage", "shop", "stall", "fixture"),
                StallContract.COMMANDS);
        assertEquals(Arrays.asList(PriceType.MONEY, PriceType.POINTS),
                Arrays.asList(PriceType.values()));
    }

    @Test
    void preservesSellerPriceTaxAndPointIntegerRules() {
        ListingTerms money = ListingTerms.of(
                new BigDecimal("100"), new BigDecimal("0.05"), PriceType.MONEY);
        ListingTerms.Settlement moneySettlement = money.settle(2);
        assertEquals(new BigDecimal("105.00"), money.listedUnitPrice());
        assertEquals(new BigDecimal("210.00"), moneySettlement.buyerCharge());
        assertEquals(new BigDecimal("200"), moneySettlement.sellerIncome());
        assertEquals(new BigDecimal("10.00"), moneySettlement.tax());

        ListingTerms points = ListingTerms.of(
                new BigDecimal("100"), new BigDecimal("0.10"), PriceType.POINTS);
        assertEquals(new BigDecimal("110"), points.listedUnitPrice());
        assertThrows(IllegalArgumentException.class, () -> ListingTerms.of(
                new BigDecimal("1.5"), new BigDecimal("0.10"), PriceType.POINTS));
    }

    @Test
    void rejectsSelfPurchaseAndLeavesStockAndBalanceUntouched() {
        StallLedger ledger = new StallLedger();
        ledger.set(SELLER, new BigDecimal("500"));
        StallRuntime runtime = new StallRuntime(ledger, null);
        StallListing listing = runtime.list(
                SELLER,
                "Seller",
                "DIAMOND",
                4,
                ListingTerms.of(
                        new BigDecimal("100"), new BigDecimal("0.05"), PriceType.MONEY));

        PurchaseResult result = runtime.purchase(listing.id(), SELLER, 1);

        assertEquals(PurchaseResult.Status.SELF_PURCHASE, result.status());
        assertEquals(4, runtime.find(listing.id()).amount());
        assertEquals(new BigDecimal("500"), ledger.balance(SELLER));
    }

    @Test
    void completesPurchaseAsOneStockAndLedgerTransition() {
        StallLedger ledger = new StallLedger();
        ledger.set(BUYER, new BigDecimal("1000"));
        StallRuntime runtime = new StallRuntime(ledger, null);
        StallListing listing = runtime.list(
                SELLER,
                "Seller",
                "DIAMOND",
                4,
                ListingTerms.of(
                        new BigDecimal("100"), new BigDecimal("0.05"), PriceType.MONEY));

        PurchaseResult first = runtime.purchase(listing.id(), BUYER, 2);

        assertEquals(PurchaseResult.Status.COMPLETED, first.status());
        assertEquals(2, runtime.find(listing.id()).amount());
        assertEquals(new BigDecimal("790.00"), ledger.balance(BUYER));
        assertEquals(new BigDecimal("200"), ledger.balance(SELLER));

        PurchaseResult second = runtime.purchase(listing.id(), BUYER, 2);
        assertEquals(PurchaseResult.Status.COMPLETED, second.status());
        assertNull(runtime.find(listing.id()));
        assertEquals(0, runtime.size());
    }

    @Test
    void failedPaymentDoesNotConsumeStock() {
        StallLedger ledger = new StallLedger();
        ledger.set(BUYER, new BigDecimal("10"));
        StallRuntime runtime = new StallRuntime(ledger, null);
        StallListing listing = runtime.list(
                SELLER,
                "Seller",
                "DIAMOND",
                4,
                ListingTerms.of(
                        new BigDecimal("100"), new BigDecimal("0.05"), PriceType.MONEY));

        PurchaseResult result = runtime.purchase(listing.id(), BUYER, 1);

        assertEquals(PurchaseResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(4, runtime.find(listing.id()).amount());
        assertEquals(new BigDecimal("10"), ledger.balance(BUYER));
        assertEquals(BigDecimal.ZERO, ledger.balance(SELLER));
    }
}
