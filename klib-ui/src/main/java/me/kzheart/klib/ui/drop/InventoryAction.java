package me.kzheart.klib.ui.drop;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 投放区状态机的不可变输入。 */
public final class InventoryAction {
    public enum Type {
        PLACE,
        SHIFT_INSERT,
        DRAG,
        TAKE
    }

    private final Type type;
    private final int slot;
    private final ItemStack item;
    private final int amount;
    private final Map<Integer, Integer> dragAmounts;

    private InventoryAction(
            Type type,
            int slot,
            ItemStack item,
            int amount,
            Map<Integer, Integer> dragAmounts
    ) {
        this.type = Objects.requireNonNull(type, "type");
        this.slot = slot;
        this.item = item == null ? null : item.clone();
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        this.amount = amount;
        this.dragAmounts = Collections.unmodifiableMap(
                new LinkedHashMap<Integer, Integer>(dragAmounts));
    }

    public static InventoryAction place(int slot, ItemStack item, int amount) {
        return new InventoryAction(Type.PLACE, slot, item, amount,
                Collections.<Integer, Integer>emptyMap());
    }

    public static InventoryAction shiftInsert(ItemStack item) {
        Objects.requireNonNull(item, "item");
        return new InventoryAction(Type.SHIFT_INSERT, -1, item, item.getAmount(),
                Collections.<Integer, Integer>emptyMap());
    }

    public static InventoryAction drag(ItemStack item, Map<Integer, Integer> amounts) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(amounts, "amounts");
        return new InventoryAction(Type.DRAG, -1, item, item.getAmount(), amounts);
    }

    public static InventoryAction take(int slot, int amount) {
        return new InventoryAction(Type.TAKE, slot, null, amount,
                Collections.<Integer, Integer>emptyMap());
    }

    public Type type() {
        return type;
    }

    public int slot() {
        return slot;
    }

    public ItemStack item() {
        return item == null ? null : item.clone();
    }

    public int amount() {
        return amount;
    }

    public Map<Integer, Integer> dragAmounts() {
        return dragAmounts;
    }
}
