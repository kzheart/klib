package me.kzheart.klib.ui.drop;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 一次原子投放区状态转换的结果。 */
public final class DropResult {
    private final int accepted;
    private final ItemStack removed;
    private final Map<Integer, Integer> acceptedBySlot;

    DropResult(int accepted, ItemStack removed) {
        this(accepted, removed, Collections.<Integer, Integer>emptyMap());
    }

    DropResult(int accepted, ItemStack removed, Map<Integer, Integer> acceptedBySlot) {
        this.accepted = accepted;
        this.removed = removed == null ? null : removed.clone();
        this.acceptedBySlot = Collections.unmodifiableMap(
                new LinkedHashMap<Integer, Integer>(acceptedBySlot));
    }

    public int accepted() {
        return accepted;
    }

    public Optional<ItemStack> removed() {
        return Optional.ofNullable(removed == null ? null : removed.clone());
    }

    /** 本次转换中每个投放区槽位接收的数量。 */
    public Map<Integer, Integer> acceptedBySlot() {
        return acceptedBySlot;
    }
}
