package me.kzheart.klib.ui

import java.util.concurrent.atomic.AtomicReference
import me.kzheart.klib.scope.Disposable
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MenuPageInteropTest {
    @Test
    fun `Kotlin binds configured pages and returns to its immutable filter and page`() {
        MenuTestEnvironment().use { f ->
            val yaml = mapOf("title" to "Browse", "rows" to 1,
                "layout" to listOf("MMB      "), "items" to mapOf("B" to "PAPER"))
            val layout = MenuLayout.from(yaml, "menus.yml:browse", setOf('M', 'B')) { id ->
                MenuEntry.of(ItemStack(Material.valueOf(id)))
            }
            var released = 0
            val active = AtomicReference<ReactiveMenuSession<State>>()
            val child = AtomicReference<ReactiveMenuSession<String>>()
            val detail = MenuPage.builder<String>("detail") { ui -> menu(ui.state(), 1) {} }
                .onOpen(child::set).build()
            val browse = MenuPage.builder<State>("browse") { ui ->
                val page = Paginator(listOf(1, 2, 3), layout.slots('M').size).page(ui.state().page)
                layout.compile { b ->
                    b.entries('M', page.values().map { value ->
                        MenuEntry.of(ItemStack(Material.DIAMOND), MenuAction { ui.push(detail, "item-$value") })
                    })
                    b.action('B', MenuAction { ui.back() })
                }
            }.onOpen { ui -> active.set(ui); ui.bind(Disposable { released++ }) }.build()
            f.menus.openReactive(f.player, browse, State(1, "weapons")) { f.player.closeInventory() }
            active.get().holder().model().entry(0).get().action().accept(MenuClick(f.player, 0))
            assertNull(child.get())
            f.tick()
            assertEquals("item-3", child.get().state())
            assertEquals(1, released)
            child.get().back(); f.tick()
            assertEquals(State(1, "weapons"), active.get().state())
            active.get().back(); f.tick()
            assertFalse(active.get().isCurrent())
            assertEquals(2, released)
        }
    }

    data class State(val page: Int, val filter: String)
}
