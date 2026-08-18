package me.kzheart.example.stall;

import me.kzheart.klib.scope.Disposable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** M3 迁移测试夹具中由作用域管理的商品状态与原子购买边界。 */
public final class StallRuntime implements Disposable {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, StallListing> listings = new LinkedHashMap<Long, StallListing>();
    private final StallLedger ledger;
    private final StallPersistence persistence;

    public StallRuntime(StallLedger ledger, StallPersistence persistence) {
        this.ledger = ledger;
        this.persistence = persistence;
    }

    public synchronized StallListing list(
            UUID sellerId,
            String sellerName,
            String material,
            int amount,
            ListingTerms terms
    ) {
        StallListing listing = new StallListing(
                ids.incrementAndGet(), sellerId, sellerName, material, amount, terms);
        listings.put(Long.valueOf(listing.id()), listing);
        if (persistence != null) {
            persistence.save(listing);
        }
        return listing;
    }

    public synchronized List<StallListing> bySeller(UUID sellerId) {
        List<StallListing> result = new ArrayList<StallListing>();
        for (StallListing listing : listings.values()) {
            if (listing.sellerId().equals(sellerId)) {
                result.add(listing);
            }
        }
        return result;
    }

    public synchronized List<StallListing> bySellerName(String sellerName) {
        List<StallListing> result = new ArrayList<StallListing>();
        for (StallListing listing : listings.values()) {
            if (listing.sellerName().equalsIgnoreCase(sellerName)) {
                result.add(listing);
            }
        }
        return result;
    }

    public synchronized StallListing find(long listingId) {
        return listings.get(Long.valueOf(listingId));
    }

    public synchronized PurchaseResult purchase(long listingId, UUID buyerId, int amount) {
        StallListing listing = listings.get(Long.valueOf(listingId));
        if (listing == null) {
            return PurchaseResult.rejected(PurchaseResult.Status.NOT_FOUND, 0);
        }
        if (listing.sellerId().equals(buyerId)) {
            return PurchaseResult.rejected(
                    PurchaseResult.Status.SELF_PURCHASE, listing.amount());
        }
        if (amount < 1) {
            return PurchaseResult.rejected(
                    PurchaseResult.Status.INVALID_AMOUNT, listing.amount());
        }
        if (amount > listing.amount()) {
            return PurchaseResult.rejected(
                    PurchaseResult.Status.INSUFFICIENT_STOCK, listing.amount());
        }

        ListingTerms.Settlement settlement = listing.terms().settle(amount);
        if (!ledger.transfer(
                buyerId,
                listing.sellerId(),
                settlement.buyerCharge(),
                settlement.sellerIncome())) {
            return PurchaseResult.rejected(
                    PurchaseResult.Status.INSUFFICIENT_FUNDS, listing.amount());
        }

        int remaining = listing.amount() - amount;
        if (remaining == 0) {
            listings.remove(Long.valueOf(listing.id()));
            if (persistence != null) {
                persistence.remove(listing.id());
            }
        } else {
            StallListing updated = listing.withAmount(remaining);
            listings.put(Long.valueOf(listing.id()), updated);
            if (persistence != null) {
                persistence.save(updated);
            }
        }
        return PurchaseResult.completed(
                settlement.buyerCharge(), settlement.sellerIncome(), remaining);
    }

    public StallLedger ledger() {
        return ledger;
    }

    public synchronized int size() {
        return listings.size();
    }

    @Override
    public synchronized void dispose() {
        listings.clear();
    }
}
