package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagKeyPdcTest {
    @Test
    void pdcReplacementUsesTheSameTypedPublicApi() {
        MemoryItemTagBridge pdc = new MemoryItemTagBridge();
        ItemStack item = new ItemStack(Material.DIAMOND);
        TagKey<Boolean> bound = TagKey.bool("klib:bound").using(pdc);
        TagKey<Long> owner = TagKey.longValue("klib:owner").using(pdc);
        TagKey<byte[]> proof = TagKey.bytes("klib:proof").using(pdc);

        bound.set(item, true);
        owner.set(item, 42L);
        proof.set(item, new byte[]{1, 2, 3});

        assertTrue(bound.get(item));
        assertEquals(42L, owner.get(item));
        assertArrayEquals(new byte[]{1, 2, 3}, proof.get(item));
        bound.set(item, false);
        assertFalse(bound.get(item));
    }
}
