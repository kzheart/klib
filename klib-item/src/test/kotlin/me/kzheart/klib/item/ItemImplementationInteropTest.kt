package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ItemImplementationInteropTest {
    @Test fun `typed Kotlin access exposes absence without a second wrapper API`() {
        val key = TagKey.integer("klib:uses").using(MemoryItemTagBridge())
        val item = ItemStack(Material.STONE)
        val missing: Int? = key.get(item)
        assertNull(missing)
        assertNull(key.getOrDefault(item, null))
        key.set(item, 7)
        assertEquals(7, key.get(item))
        assertEquals(7, key.find(item).get())
        key.remove(item)
        assertFalse(key.has(item))
    }

    @Test fun `nullable predicates preserve the vanilla matching boundary`() {
        val matcher = ExternalItems.empty().matcher("STONE")
        assertFalse(matcher.test(null))
        assertFalse(ExternalItems.empty().matches("STONE", null))
    }

    @Test fun `empty previews preserve the Java validation exception`() {
        assertThrows(IllegalArgumentException::class.java) { ItemPreview.ready(null) }
    }
}
