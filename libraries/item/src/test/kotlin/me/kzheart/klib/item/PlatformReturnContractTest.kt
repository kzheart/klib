package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlatformReturnContractTest {
    // Each concrete bridge, as well as ItemBuilder, must preserve the unannotated Java T return.
    private fun builderValue(builder: ItemBuilder, key: TagKey<String>): String = builder.tag(key)
    private fun legacyValue(bridge: LegacyNbtItemTagBridge, item: ItemStack, key: TagKey<String>): String = bridge.get(item, key)
    private fun pdcValue(bridge: PdcItemTagBridge, item: ItemStack, key: TagKey<String>): String = bridge.get(item, key)
    private fun adaptiveValue(bridge: AdaptiveItemTagBridge, item: ItemStack, key: TagKey<String>): String = bridge.get(item, key)

    @Test fun `builder and adaptive bridges allow both nonnull assignments and genuine absence`() {
        val key = TagKey.string("klib:platform").using(MemoryItemTagBridge())
        val builder = Items.of(Material.STONE)
        assertNull(builder.tag(key))
        assertSame(builder, builder.tag(key, "stored"))
        assertEquals("stored", builderValue(builder, key))
        builder.removeTag(key)
        assertNull(builder.tag(key))
        val memory = MemoryItemTagBridge()
        val bridge = AdaptiveItemTagBridge(memory, MemoryItemTagBridge(), { true }, { false })
        val item = ItemStack(Material.STONE)
        assertNull(bridge.get(item, key))
        bridge.set(item, key, "migrated")
        assertEquals("migrated", adaptiveValue(bridge, item, key))
        bridge.remove(item, key)
        assertNull(bridge.get(item, key))
    }

    @Test fun `concrete native and PDC bridges return actual null for absent storage`() {
        val key = TagKey.string("klib:platform")
        assertNull(LegacyNbtItemTagBridge().get(ItemStack(Material.AIR), key))
        val withoutMeta = object : ItemStack(Material.AIR) {
            override fun getItemMeta(): ItemMeta? = null
        }
        assertNull(PdcItemTagBridge().get(withoutMeta, key))
    }
}
