package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

/** {@link TagKey} 使用的可插拔存储边界。 */
public interface ItemTagBridge {
    <T> T get(ItemStack item, TagKey<T> key);

    <T> void set(ItemStack item, TagKey<T> key, T value);

    boolean has(ItemStack item, TagKey<?> key);

    void remove(ItemStack item, TagKey<?> key);
}
