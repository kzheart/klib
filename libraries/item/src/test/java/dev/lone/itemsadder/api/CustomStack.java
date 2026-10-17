package dev.lone.itemsadder.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 测试替身。 */
public final class CustomStack {
    public static final short MARKER = 99;
    private final String id;

    private CustomStack(String id) {
        this.id = id;
    }

    public static CustomStack getInstance(String id) {
        return "ruby:gem".equals(id) ? new CustomStack(id) : null;
    }

    @SuppressWarnings("deprecation")
    public static CustomStack byItemStack(ItemStack item) {
        return item != null && item.getDurability() == MARKER ? new CustomStack("ruby:gem") : null;
    }

    @SuppressWarnings("deprecation")
    public ItemStack getItemStack() {
        return new ItemStack(Material.EMERALD, 1, MARKER);
    }

    public String getNamespacedID() {
        return id;
    }
}
