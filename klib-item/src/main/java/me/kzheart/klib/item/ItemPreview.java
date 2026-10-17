package me.kzheart.klib.item;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** 无交付语义的展示结果；成功物品始终按防御性副本返回。 */
public final class ItemPreview {
    public enum Status { READY, UNSUPPORTED, NOT_READY, UNKNOWN_ID, FAILED }

    private final Status status;
    private final ItemStack item;
    private final String detail;
    private final Throwable failure;

    private ItemPreview(Status status, ItemStack item, String detail, Throwable failure) {
        this.status = status;
        this.item = item;
        this.detail = detail;
        this.failure = failure;
    }

    public static ItemPreview ready(ItemStack item) {
        if (InventoryItems.isAir(item)) throw new IllegalArgumentException("Preview item must not be empty");
        return new ItemPreview(Status.READY, item.clone(), "", null);
    }

    public static ItemPreview unavailable(Status status, String detail) {
        Objects.requireNonNull(status, "status");
        if (status == Status.READY || status == Status.FAILED) {
            throw new IllegalArgumentException("Use ready or failed for this status");
        }
        return new ItemPreview(status, null, Objects.requireNonNull(detail, "detail"), null);
    }

    public static ItemPreview failed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new ItemPreview(Status.FAILED, null, failure.toString(), failure);
    }

    public Status status() { return status; }
    public Optional<ItemStack> item() { return item == null ? Optional.empty() : Optional.of(item.clone()); }
    public String detail() { return detail; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
}
