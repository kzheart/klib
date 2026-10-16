package me.kzheart.klib.ui

import me.kzheart.klib.core.kotlin.KlibDsl
import org.bukkit.inventory.ItemStack

/** Builds the existing immutable model; all slot/layout validation is shared. */
@KlibDsl
class MenuDsl internal constructor(private val builder: MenuTemplate.Builder) {
    fun slot(index: Int, block: MenuEntryDsl.() -> Unit) {
        builder.slot(index, MenuEntryDsl().apply(block).build())
    }
    fun character(symbol: Char, block: MenuEntryDsl.() -> Unit) {
        builder.character(symbol, MenuEntryDsl().apply(block).build())
    }
    fun layout(vararg rows: String) { builder.layout(*rows) }
    fun cancelClicks(value: Boolean) { builder.cancelClicks(value) }
    fun slot(index: Int, item: ItemStack, action: MenuClick.() -> Unit = {}) {
        builder.slot(index, MenuEntry.of(item) { it.action() })
    }
    fun character(symbol: Char, item: ItemStack, action: MenuClick.() -> Unit = {}) {
        builder.character(symbol, MenuEntry.of(item) { it.action() })
    }
}

fun menu(title: String, rows: Int, block: MenuDsl.() -> Unit): MenuModel {
    val builder = MenuTemplate.builder(title, rows)
    MenuDsl(builder).block()
    return MenuCompiler.compile(builder.build())
}

@KlibDsl
class MenuEntryDsl internal constructor() {
    private var stack: ItemStack? = null
    private var action: MenuAction = MenuAction.none()
    private var sound: String? = null
    fun item(value: ItemStack) { stack = value.clone() }
    fun onClick(block: MenuClick.() -> Unit) { action = MenuAction { it.block() } }
    fun clickSound(value: String) { sound = value }
    internal fun build(): MenuEntry {
        val entry = MenuEntry.of(requireNotNull(stack) { "menu entry requires an item" }, action)
        return sound?.let(entry::withClickSound) ?: entry
    }
}
