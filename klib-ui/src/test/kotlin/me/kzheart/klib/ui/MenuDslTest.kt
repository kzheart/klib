package me.kzheart.klib.ui
import me.kzheart.klib.item.item
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class MenuDslTest {
    @Test fun `documentation hierarchical example compiles`() {
        val model = menu("工具", 1) {
            layout("x        ")
            character('x') { item(item(Material.STONE) { amount(1) }); onClick {} }
        }
        assertEquals(Material.STONE, model.entry(0).get().item().type)
    }
    @Test fun `model uses shared validation and defensive copies`() {
        val source = ItemStack(Material.STONE, 2)
        val model = menu("测试", 1) { layout("x        "); character('x', source) }
        source.amount = 3
        assertEquals(2, model.entry(0).get().item().amount)
        model.entry(0).get().item().amount = 4
        assertEquals(2, model.entry(0).get().item().amount)
        assertThrows(IllegalArgumentException::class.java) { menu("bad", 0) {} }
        assertThrows(IllegalArgumentException::class.java) { menu("bad", 7) {} }
        for (slot in listOf(-1, 9)) assertThrows(IllegalArgumentException::class.java) {
            menu("bad", 1) { slot(slot, source) }
        }
        assertEquals(8, menu("last", 1) { slot(8, source) }.entries().keys.single())
    }
}
