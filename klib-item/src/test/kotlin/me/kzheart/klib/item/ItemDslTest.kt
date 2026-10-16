package me.kzheart.klib.item
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class ItemDslTest {
    @Test fun `builder boundaries and independent edits`() {
        val original = item(Material.STONE) { amount = 2 }
        val copy = original.editItem { amount = 3 }
        assertEquals(2, original.amount)
        assertEquals(3, copy.amount)
        assertThrows(IllegalArgumentException::class.java) { item("missing_material") }
        assertThrows(IllegalArgumentException::class.java) { item(Material.STONE) { amount = 0 } }
    }
    @Test fun `native properties retain validation formatting and defensive ownership`() {
        FakeItemServer.install(true)
        val lines = mutableListOf("&7first")
        val original = item(Material.STONE) {
            amount = 2
            name = "&aName"
            lore = lines
            customModelData = 12
            unbreakable = true
            assertEquals(2, amount)
            assertEquals("§aName", name)
            assertEquals(listOf("§7first"), lore)
            assertEquals(12, customModelData)
            assertTrue(unbreakable)
        }
        lines[0] = "changed"
        val edited = original.editItem { name = "new"; lore = listOf("new"); customModelData = null }
        assertEquals("§aName", original.itemMeta.displayName)
        assertEquals(listOf("§7first"), original.itemMeta.lore)
        assertEquals("new", edited.itemMeta.displayName)
        assertEquals(12, Items.customModelData(original))
        assertNull(Items.customModelData(edited))
        assertThrows(IllegalArgumentException::class.java) { item(Material.STONE) { amount = 65 } }
    }
    @Test fun `typed tag operators share TagKey storage and absence semantics`() {
        val bridge = MemoryItemTagBridge()
        val count = TagKey.integer("test:count").using(bridge)
        val proof = TagKey.bytes("test:proof").using(bridge)
        val stack = item(Material.STONE)
        assertNull(stack[count])
        stack[count] = 3
        val typed: Int? = stack[count]
        assertEquals(3, typed)
        val bytes = byteArrayOf(1)
        stack[proof] = bytes
        bytes[0] = 9
        assertArrayEquals(byteArrayOf(1), stack[proof])
        stack.removeTag(count)
        assertNull(stack[count])
        item(Material.STONE) {
            this[count] = 4
            assertEquals(4, this[count])
            removeTag(count)
            assertNull(this[count])
        }
    }

}
