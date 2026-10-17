package me.kzheart.klib.ui

import me.kzheart.klib.lang.RichText
import me.kzheart.klib.ui.chat.ChatPanelButton
import me.kzheart.klib.ui.drop.InventoryAction
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlatformReturnContractTest {
    private fun inventoryProperty(holder: MenuHolder): Inventory = holder.inventory
    private fun inventoryMethod(holder: MenuHolder): Inventory = holder.getInventory()
    private fun itemAmount(action: InventoryAction): Int = if (action.item() != null) action.item().amount else 0
    private fun buttonText(button: ChatPanelButton): Int =
        (if (button.id() != null) button.id().length else 0) +
        (if (button.hover() != null) button.hover().plainText().length else 0) +
        (if (button.value() != null) button.value().plainText().length else 0)

    @Test fun `take actions and client-only buttons preserve absent platform values`() {
        assertNull(InventoryAction.take(0, 1).item())
        assertEquals(0, itemAmount(InventoryAction.take(0, 1)))
        val source = ItemStack(Material.STONE, 2)
        val action = InventoryAction.place(0, source, 2)
        source.amount = 5
        assertEquals(2, itemAmount(action))
        action.item().amount = 9
        assertEquals(2, itemAmount(action))
        val button = ChatPanelButton.suggest(RichText.plain("label"), "suggested")
        assertNull(button.id())
        assertNull(button.hover())
        assertNull(button.value())
        assertEquals(0, buttonText(button))
        assertEquals(9, buttonText(button.hover(RichText.plain("hover")).value("text")))
    }

    @Test fun `holder property and getter remain platform types before and after attach`() {
        MenuTestEnvironment().use { env ->
            val model = MenuCompiler.compile(MenuTemplate.builder("Platform", 1).build())
            val session = MenuSession.open(env.scope, "platform", model) { emptyList() }
            val holder = MenuHolder(env.player, session, env.menus)
            assertNull(holder.inventory)
            assertNull(holder.getInventory())
            val inventory = env.inventory(holder, 9)
            holder.attach(inventory)
            assertSame(inventory, inventoryProperty(holder))
            assertSame(inventory, inventoryMethod(holder))
            assertThrows(IllegalStateException::class.java) { holder.attach(inventory) }
        }
    }
}
