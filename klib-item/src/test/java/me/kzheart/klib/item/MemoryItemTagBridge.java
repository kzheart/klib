package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

final class MemoryItemTagBridge implements ItemTagBridge {
    private final Map<ItemStack, Map<String, Object>> values =
            new IdentityHashMap<ItemStack, Map<String, Object>>();

    @Override
    public <T> T get(ItemStack item, TagKey<T> key) {
        Map<String, Object> itemValues = values.get(item);
        return itemValues == null ? null : key.fromStorage(copy(itemValues.get(key.value())));
    }

    @Override
    public <T> void set(ItemStack item, TagKey<T> key, T value) {
        Map<String, Object> itemValues = values.get(item);
        if (itemValues == null) {
            itemValues = new HashMap<String, Object>();
            values.put(item, itemValues);
        }
        itemValues.put(key.value(), copy(key.toStorage(value)));
    }

    @Override
    public boolean has(ItemStack item, TagKey<?> key) {
        Map<String, Object> itemValues = values.get(item);
        return itemValues != null && itemValues.containsKey(key.value());
    }

    @Override
    public void remove(ItemStack item, TagKey<?> key) {
        Map<String, Object> itemValues = values.get(item);
        if (itemValues != null) {
            itemValues.remove(key.value());
        }
    }

    private static Object copy(Object value) {
        return value instanceof byte[] ? ((byte[]) value).clone() : value;
    }
}
