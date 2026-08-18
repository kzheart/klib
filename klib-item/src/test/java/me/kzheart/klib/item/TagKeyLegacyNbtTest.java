package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagKeyLegacyNbtTest {
    @Test
    void legacyBridgePreservesTypedContractAndRemoval() {
        MemoryItemTagBridge legacy = new MemoryItemTagBridge();
        ItemStack item = new ItemStack(Material.STONE);
        TagKey<String> kind = TagKey.string("simplegather:type").using(legacy);
        TagKey<Integer> level = TagKey.integer("simplegather:level").using(legacy);

        assertFalse(kind.has(item));
        assertNull(kind.get(item));
        kind.set(item, "ore");
        level.set(item, 4);

        assertTrue(kind.has(item));
        assertEquals("ore", kind.get(item));
        assertEquals(4, level.get(item));
        kind.remove(item);
        assertFalse(kind.has(item));
    }

    @Test
    void rejectsKeysThatCannotMapToNbtOrNamespacedKeys() {
        assertThrows(IllegalArgumentException.class, () -> TagKey.string("missing-namespace"));
        assertThrows(IllegalArgumentException.class, () -> TagKey.string("Bad Namespace:value"));
    }

    @Test
    void supportedNbtApiExposesBoxedNumericSetters() throws Exception {
        Class<?> nbt = Class.forName("de.tr7zw.changeme.nbtapi.NBTCompound");

        assertEquals(
                Void.TYPE,
                nbt.getMethod("setInteger", String.class, Integer.class).getReturnType());
    }
}
