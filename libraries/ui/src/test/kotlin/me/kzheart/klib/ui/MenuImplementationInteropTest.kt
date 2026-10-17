package me.kzheart.klib.ui

import me.kzheart.klib.ui.annotation.Button
import me.kzheart.klib.ui.annotation.Click
import me.kzheart.klib.ui.annotation.Menu
import me.kzheart.klib.ui.prompt.PromptParser
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional

class MenuImplementationInteropTest {
    @Test fun `Kotlin declarations use the same annotation compiler and immutable model`() {
        val controller = KotlinMenu()
        val model = AnnotatedMenu(controller).render { }
        assertEquals("Kotlin declaration", model.title())
        assertEquals(9, model.size())
        assertTrue(model.entry(0).isPresent)
        assertEquals(Material.STONE, model.entry(0).get().item().type)
    }

    @Test fun `Kotlin nullable SPI implementations remain valid`() {
        val parser = object : PromptParser<String> {
            override fun parse(message: String?): Optional<String> = Optional.ofNullable(message)
        }
        assertFalse(parser.parse(null).isPresent)
    }

    @Test fun `pagination snapshots input before computing windows`() {
        val input = mutableListOf("a", "b", "c")
        val paginator = Paginator(input, 2)
        input.clear()
        assertEquals(listOf("c"), paginator.page(Int.MAX_VALUE).values())
        assertEquals(1, paginator.page(Int.MAX_VALUE).index())
        assertTrue(paginator.page(1).hasPrevious())
    }

    @Test fun `invalid nullable drag amount is rejected before mutation`() {
        val zone = me.kzheart.klib.ui.drop.DropZoneController(setOf(0)) { true }
        val action = me.kzheart.klib.ui.drop.InventoryAction.drag(ItemStack(Material.STONE, 2), mapOf(0 to null))
        assertThrows(IllegalArgumentException::class.java) { zone.handle(action) }
        assertTrue(zone.snapshot().isEmpty())
    }

    @Test fun `menu state getters preserve Kotlin properties without changing Java methods`() {
        val zone = me.kzheart.klib.ui.drop.DropZoneController(setOf(0)) { true }
        assertFalse(zone.isClosed)
        assertFalse(zone.isClosed())
        zone.closeAndDrain()
        assertTrue(zone.isClosed)
        assertTrue(zone.isClosed())
        for (type in listOf(MenuSession::class.java, ReactiveMenuSession::class.java,
                me.kzheart.klib.ui.chat.ChatPanelSession::class.java)) {
            val property = if (type == ReactiveMenuSession::class.java) "currentProperty" else "closedProperty"
            val method = if (type == ReactiveMenuSession::class.java) "isCurrent" else "isClosed"
            assertTrue(type.getMethod(property).isSynthetic)
            assertFalse(type.getMethod(method).isSynthetic)
        }
    }

    // Compiled consumer contracts also cover sessions requiring a live Bukkit server to construct.
    private fun sessionPropertyConsumer(menu: MenuSession, reactive: ReactiveMenuSession<String>,
                                        chat: me.kzheart.klib.ui.chat.ChatPanelSession) =
        listOf(menu.isClosed, menu.isClosed(), reactive.isCurrent, reactive.isCurrent(), chat.isClosed, chat.isClosed())

    @Menu(title = "Kotlin declaration", layout = ["a        "])
    class KotlinMenu {
        @Button('a') fun entry(): MenuEntry = MenuEntry.of(ItemStack(Material.STONE))
        @Click('a') fun click(click: MenuClick) { }
    }
}
