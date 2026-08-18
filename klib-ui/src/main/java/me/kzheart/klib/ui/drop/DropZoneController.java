package me.kzheart.klib.ui.drop;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 串行化的投放区状态机。它持有所有已接收物品堆的克隆，绝不修改动作的源物品堆。
 */
public final class DropZoneController {
    private final Set<Integer> slots;
    private final Predicate<ItemStack> accepts;
    private final Map<Integer, ItemStack> contents = new LinkedHashMap<Integer, ItemStack>();
    private boolean closed;

    public DropZoneController(Set<Integer> slots, Predicate<ItemStack> accepts) {
        Objects.requireNonNull(slots, "slots");
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("drop zone must contain at least one slot");
        }
        LinkedHashSet<Integer> copy = new LinkedHashSet<Integer>();
        for (Integer slot : slots) {
            if (slot == null || slot.intValue() < 0) {
                throw new IllegalArgumentException("drop-zone slots must not be negative");
            }
            copy.add(slot);
        }
        this.slots = Collections.unmodifiableSet(copy);
        this.accepts = Objects.requireNonNull(accepts, "accepts");
    }

    public synchronized DropResult handle(InventoryAction action) {
        Objects.requireNonNull(action, "action");
        ensureOpen();
        switch (action.type()) {
            case PLACE:
                return place(action.slot(), requireItem(action), action.amount());
            case SHIFT_INSERT:
                return insertAcross(requireItem(action), action.amount());
            case DRAG:
                return drag(requireItem(action), action.dragAmounts());
            case TAKE:
                return take(action.slot(), action.amount());
            default:
                throw new IllegalArgumentException("unsupported action: " + action.type());
        }
    }

    /** 此区域覆盖的槽位。 */
    public Set<Integer> slots() {
        return slots;
    }

    public synchronized Map<Integer, ItemStack> snapshot() {
        Map<Integer, ItemStack> copy = new LinkedHashMap<Integer, ItemStack>();
        for (Map.Entry<Integer, ItemStack> entry : contents.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 关闭并清空区域，将持有的每个物品堆返回给调用方。 */
    public synchronized List<ItemStack> closeAndDrain() {
        if (closed) {
            return Collections.emptyList();
        }
        closed = true;
        List<ItemStack> result = new ArrayList<ItemStack>(contents.size());
        for (ItemStack item : contents.values()) {
            result.add(item.clone());
        }
        contents.clear();
        return result;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private DropResult drag(ItemStack source, Map<Integer, Integer> requested) {
        if (!accepts.test(source)) {
            return new DropResult(0, null);
        }
        long totalRequested = 0L;
        for (Map.Entry<Integer, Integer> entry : requested.entrySet()) {
            requireSlot(entry.getKey().intValue());
            totalRequested += requirePositive(entry.getValue(), "drag amount");
        }
        if (totalRequested > source.getAmount()) {
            throw new IllegalArgumentException("drag amounts exceed the source stack");
        }
        int available = source.getAmount();
        int accepted = 0;
        Map<Integer, Integer> perSlot = new LinkedHashMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> entry : requested.entrySet()) {
            if (available == 0) {
                break;
            }
            int requestedAmount = requirePositive(entry.getValue(), "drag amount");
            int moved = addToSlot(entry.getKey().intValue(), source,
                    Math.min(requestedAmount, available));
            if (moved > 0) {
                perSlot.put(entry.getKey(), Integer.valueOf(moved));
            }
            accepted += moved;
            available -= moved;
        }
        return new DropResult(accepted, null, perSlot);
    }

    private DropResult insertAcross(ItemStack source, int requested) {
        if (!accepts.test(source)) {
            return new DropResult(0, null);
        }
        int remaining = Math.min(requested, source.getAmount());
        int accepted = 0;
        Map<Integer, Integer> perSlot = new LinkedHashMap<Integer, Integer>();
        for (Integer slot : slots) {
            ItemStack present = contents.get(slot);
            if (present == null || !present.isSimilar(source)) {
                continue;
            }
            int moved = addToSlot(slot.intValue(), source, remaining);
            recordMove(perSlot, slot, moved);
            accepted += moved;
            remaining -= moved;
            if (remaining == 0) {
                return new DropResult(accepted, null, perSlot);
            }
        }
        for (Integer slot : slots) {
            if (contents.containsKey(slot)) {
                continue;
            }
            int moved = addToSlot(slot.intValue(), source, remaining);
            recordMove(perSlot, slot, moved);
            accepted += moved;
            remaining -= moved;
            if (remaining == 0) {
                break;
            }
        }
        return new DropResult(accepted, null, perSlot);
    }

    private static void recordMove(Map<Integer, Integer> perSlot, Integer slot, int moved) {
        if (moved > 0) {
            Integer present = perSlot.get(slot);
            perSlot.put(slot, Integer.valueOf(present == null ? moved : present.intValue() + moved));
        }
    }

    private DropResult place(int slot, ItemStack source, int requested) {
        requireSlot(slot);
        if (!accepts.test(source)) {
            return new DropResult(0, null);
        }
        int amount = Math.min(requested, source.getAmount());
        int moved = addToSlot(slot, source, amount);
        Map<Integer, Integer> perSlot = new LinkedHashMap<Integer, Integer>();
        recordMove(perSlot, Integer.valueOf(slot), moved);
        return new DropResult(moved, null, perSlot);
    }

    private int addToSlot(int slot, ItemStack source, int requested) {
        ItemStack present = contents.get(Integer.valueOf(slot));
        if (present != null && !present.isSimilar(source)) {
            return 0;
        }
        int current = present == null ? 0 : present.getAmount();
        int capacity = Math.max(0, source.getMaxStackSize() - current);
        int moved = Math.min(requested, capacity);
        if (moved == 0) {
            return 0;
        }
        ItemStack replacement = present == null ? source.clone() : present.clone();
        replacement.setAmount(current + moved);
        contents.put(Integer.valueOf(slot), replacement);
        return moved;
    }

    private DropResult take(int slot, int requested) {
        requireSlot(slot);
        ItemStack present = contents.get(Integer.valueOf(slot));
        if (present == null) {
            return new DropResult(0, null);
        }
        int amount = Math.min(requested, present.getAmount());
        ItemStack removed = present.clone();
        removed.setAmount(amount);
        if (amount == present.getAmount()) {
            contents.remove(Integer.valueOf(slot));
        } else {
            ItemStack replacement = present.clone();
            replacement.setAmount(present.getAmount() - amount);
            contents.put(Integer.valueOf(slot), replacement);
        }
        return new DropResult(0, removed);
    }

    private void requireSlot(int slot) {
        if (!slots.contains(Integer.valueOf(slot))) {
            throw new IllegalArgumentException("slot is not in this drop zone: " + slot);
        }
    }

    private static ItemStack requireItem(InventoryAction action) {
        return Objects.requireNonNull(action.item(), "action item");
    }

    private static int requirePositive(Integer value, String name) {
        if (value == null || value.intValue() < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.intValue();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("drop zone is closed");
        }
    }
}
