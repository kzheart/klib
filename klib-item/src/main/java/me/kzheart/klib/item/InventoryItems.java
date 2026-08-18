package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 事务式背包操作工具。 */
public final class InventoryItems {
    private InventoryItems() {
    }

    public static boolean isAir(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    public static int count(Inventory inventory, ItemStack prototype) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(prototype, "prototype");
        int result = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (!isAir(item) && item.isSimilar(prototype)) {
                result += item.getAmount();
            }
        }
        return result;
    }

    public static boolean take(Inventory inventory, ItemStack prototype, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        if (amount == 0) {
            return true;
        }
        if (count(inventory, prototype) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (isAir(item) || !item.isSimilar(prototype)) {
                continue;
            }
            int consumed = Math.min(remaining, item.getAmount());
            remaining -= consumed;
            if (consumed == item.getAmount()) {
                contents[slot] = null;
            } else {
                ItemStack reduced = item.clone();
                reduced.setAmount(item.getAmount() - consumed);
                contents[slot] = reduced;
            }
        }
        inventory.setStorageContents(contents);
        return true;
    }

    public static boolean hasSpace(Inventory inventory, ItemStack item, int amount) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(item, "item");
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        int capacity = 0;
        for (ItemStack present : inventory.getStorageContents()) {
            if (isAir(present)) {
                capacity += item.getMaxStackSize();
            } else if (present.isSimilar(item)) {
                capacity += Math.max(0, present.getMaxStackSize() - present.getAmount());
            }
            if (capacity >= amount) {
                return true;
            }
        }
        return amount == 0;
    }

    public static List<ItemStack> give(Player player, ItemStack... items) {
        Objects.requireNonNull(player, "player");
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(items);
        List<ItemStack> dropped = new ArrayList<ItemStack>(overflow.size());
        for (Map.Entry<Integer, ItemStack> entry : overflow.entrySet()) {
            ItemStack item = entry.getValue();
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            dropped.add(item.clone());
        }
        return dropped;
    }
}
