package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void unsupportedLegacyIsNeverTouchedForPdcMissHasOrRemoval() {
        MemoryBridge pdc = new MemoryBridge();
        TagKey<String> key = TagKey.string("klib:owner");
        ItemStack item = new ItemStack(Material.STONE);
        AdaptiveItemTagBridge bridge = new AdaptiveItemTagBridge(
                pdc, new UnusableLegacyBridge(), () -> true, () -> false);

        assertNull(bridge.get(item, key));
        assertFalse(bridge.has(item, key));
        bridge.set(item, key, "Alex");
        assertEquals("Alex", bridge.get(item, key));
        bridge.remove(item, key);
        assertFalse(bridge.has(item, key));
        bridge.remove(item, key);
    }

    @Test
    void oldServersStillUseTheSupportedLegacyBridgeForCrud() {
        MemoryBridge legacy = new MemoryBridge();
        TagKey<String> key = TagKey.string("klib:owner");
        ItemStack item = new ItemStack(Material.STONE);
        AdaptiveItemTagBridge bridge = new AdaptiveItemTagBridge(
                new UnusableLegacyBridge(), legacy, () -> false, () -> true);

        assertNull(bridge.get(item, key));
        bridge.set(item, key, "Alex");
        assertTrue(bridge.has(item, key));
        assertEquals("Alex", bridge.get(item, key));
        bridge.remove(item, key);
        assertFalse(bridge.has(item, key));
    }

    @Test
    void unavailableBackendsFailExplicitlyInsteadOfClaimingSuccessfulRemoval() {
        TagKey<String> key = TagKey.string("klib:owner");
        ItemStack item = new ItemStack(Material.STONE);
        AdaptiveItemTagBridge bridge = new AdaptiveItemTagBridge(
                new UnusableLegacyBridge(), new UnusableLegacyBridge(), () -> false, () -> false);

        assertThrows(IllegalStateException.class, () -> bridge.get(item, key));
        assertThrows(IllegalStateException.class, () -> bridge.has(item, key));
        assertThrows(IllegalStateException.class, () -> bridge.remove(item, key));
    }

    @Test
    void supportedLegacyDataTypeErrorsStillPropagate() {
        MemoryBridge legacy = new MemoryBridge();
        TagKey<String> key = TagKey.string("klib:owner");
        ItemStack item = new ItemStack(Material.STONE);
        legacy.values.put(key.value(), Integer.valueOf(12));
        AdaptiveItemTagBridge bridge = new AdaptiveItemTagBridge(
                new MemoryBridge(), legacy, () -> true, () -> true);

        assertThrows(ClassCastException.class, () -> bridge.get(item, key));
    }

    @Test
    void supportedLegacyOperationErrorsAreNotSwallowed() {
        TagKey<String> key = TagKey.string("klib:owner");
        ItemStack item = new ItemStack(Material.STONE);
        AdaptiveItemTagBridge bridge = new AdaptiveItemTagBridge(
                new MemoryBridge(), new UnusableLegacyBridge(), () -> true, () -> true);

        assertThrows(IllegalStateException.class, () -> bridge.get(item, key));
        assertThrows(IllegalStateException.class, () -> bridge.has(item, key));
        assertThrows(IllegalStateException.class, () -> bridge.remove(item, key));
    }

    private static final class UnusableLegacyBridge implements ItemTagBridge {
        @Override public <T> T get(ItemStack item, TagKey<T> key) { throw failure(); }
        @Override public <T> void set(ItemStack item, TagKey<T> key, T value) { throw failure(); }
        @Override public boolean has(ItemStack item, TagKey<?> key) { throw failure(); }
        @Override public void remove(ItemStack item, TagKey<?> key) { throw failure(); }
        private IllegalStateException failure() { return new IllegalStateException("legacy operation unavailable"); }
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
