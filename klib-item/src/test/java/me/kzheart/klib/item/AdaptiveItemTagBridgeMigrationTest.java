package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveItemTagBridgeMigrationTest {

    @Test
    void pdcMissReadsLegacyThenMigratesAndRemovesOldValue() {
        MemoryBridge pdc = new MemoryBridge();
        MemoryBridge legacy = new MemoryBridge();
        TagKey<String> key = TagKey.string("klib:owner");
        ItemStack item = new ItemStack(Material.STONE);
        legacy.set(item, key, "Alex");
        AdaptiveItemTagBridge bridge = new AdaptiveItemTagBridge(
                pdc, legacy, () -> true, () -> true);

        assertEquals("Alex", bridge.get(item, key));
        assertEquals("Alex", pdc.get(item, key));
        assertFalse(legacy.has(item, key));
    }

    @Test
    void failedMigrationStillReturnsLegacyValue() {
        MemoryBridge pdc = new MemoryBridge();
        pdc.failWrites = true;
        MemoryBridge legacy = new MemoryBridge();
        TagKey<String> key = TagKey.string("klib:owner");
        ItemStack item = new ItemStack(Material.STONE);
        legacy.set(item, key, "Alex");
        AdaptiveItemTagBridge bridge = new AdaptiveItemTagBridge(
                pdc, legacy, () -> true, () -> true);

        assertEquals("Alex", bridge.get(item, key));
        assertTrue(legacy.has(item, key));
    }

    private static final class MemoryBridge implements ItemTagBridge {
        private final Map<String, Object> values = new HashMap<String, Object>();
        private boolean failWrites;

        @Override
        public <T> T get(ItemStack item, TagKey<T> key) {
            return key.valueType().javaType().cast(values.get(key.value()));
        }

        @Override
        public <T> void set(ItemStack item, TagKey<T> key, T value) {
            if (failWrites) {
                throw new IllegalStateException("write failed");
            }
            values.put(key.value(), value);
        }

        @Override
        public boolean has(ItemStack item, TagKey<?> key) {
            return values.containsKey(key.value());
        }

        @Override
        public void remove(ItemStack item, TagKey<?> key) {
            values.remove(key.value());
        }
    }
}
