package me.kzheart.klib.item
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class ItemDslTest {
    @Test fun `builder boundaries and independent edits`() {
        val original = item(Material.STONE) { amount(2) }
        val copy = original.editItem { amount(3) }
        assertEquals(2, original.amount)
        assertEquals(3, copy.amount)
        assertThrows(IllegalArgumentException::class.java) { item("missing_material") }
        assertThrows(IllegalArgumentException::class.java) { item(Material.STONE) { amount(0) } }
    }
}
