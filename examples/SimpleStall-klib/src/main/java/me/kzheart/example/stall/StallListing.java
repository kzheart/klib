package me.kzheart.example.stall;

import java.util.Objects;
import java.util.UUID;

/** 与 Bukkit 和存储提供者解耦的不可变商品快照。 */
public final class StallListing {
    private final long id;
    private final UUID sellerId;
    private final String sellerName;
    private final String material;
    private final int amount;
    private final ListingTerms terms;

    public StallListing(
            long id,
            UUID sellerId,
            String sellerName,
            String material,
            int amount,
            ListingTerms terms
    ) {
        if (id < 1) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        this.id = id;
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        this.sellerName = Objects.requireNonNull(sellerName, "sellerName");
        this.material = Objects.requireNonNull(material, "material");
        this.amount = amount;
        this.terms = Objects.requireNonNull(terms, "terms");
    }

    public long id() {
        return id;
    }

    public UUID sellerId() {
        return sellerId;
    }

    public String sellerName() {
        return sellerName;
    }

    public String material() {
        return material;
    }

    public int amount() {
        return amount;
    }

    public ListingTerms terms() {
        return terms;
    }

    public StallListing withAmount(int newAmount) {
        return new StallListing(id, sellerId, sellerName, material, newAmount, terms);
    }
}
