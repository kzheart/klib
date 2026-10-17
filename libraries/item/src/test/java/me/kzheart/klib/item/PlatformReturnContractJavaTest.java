package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformReturnContractJavaTest {
    @Test void nullableGenericReadContractsDoNotInsertRuntimeNonnullChecks() {
        TagKey<String> key = TagKey.string("klib:platform").using(new MemoryItemTagBridge());
        ItemBuilder builder = Items.of(Material.STONE);
        String missing = builder.tag(key);
        assertNull(missing);
        builder.tag(key, "value");
        String present = builder.tag(key);
        assertEquals("value", present);
        assertNull(new LegacyNbtItemTagBridge().get(new ItemStack(Material.AIR), key));
        ItemStack withoutMeta = new ItemStack(Material.AIR) {
            @Override public ItemMeta getItemMeta() { return null; }
        };
        assertNull(new PdcItemTagBridge().get(withoutMeta, key));
        AdaptiveItemTagBridge adaptive = new AdaptiveItemTagBridge(new MemoryItemTagBridge(), new MemoryItemTagBridge(), () -> true, () -> false);
        assertNull(adaptive.get(new ItemStack(Material.STONE), key));
    }
}
