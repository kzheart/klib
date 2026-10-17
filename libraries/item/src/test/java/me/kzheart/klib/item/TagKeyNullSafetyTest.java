package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagKeyNullSafetyTest {
    @Test
    void findReportsMissingAndPresentValues() {
        MemoryItemTagBridge bridge = new MemoryItemTagBridge();
        ItemStack item = new ItemStack(Material.DIAMOND);
        TagKey<String> tool = TagKey.string("klib:tool").using(bridge);

        assertFalse(tool.find(item).isPresent());

        tool.set(item, "mining");
        Optional<String> found = tool.find(item);

        assertTrue(found.isPresent());
        assertEquals("mining", found.get());
    }

    @Test
    void getOrDefaultFallsBackWhenTagIsMissing() {
        MemoryItemTagBridge bridge = new MemoryItemTagBridge();
        ItemStack item = new ItemStack(Material.DIAMOND);
        TagKey<Integer> durability = TagKey.integer("klib:durability").using(bridge);

        assertEquals(Integer.valueOf(10), durability.getOrDefault(item, Integer.valueOf(10)));

        durability.set(item, Integer.valueOf(3));

        assertEquals(Integer.valueOf(3), durability.getOrDefault(item, Integer.valueOf(10)));
    }
}
