package me.kzheart.klib.ui

import org.bukkit.inventory.ItemStack
import java.util.Optional

/** 渲染到菜单槽位中的不可变物品与动作对。 */
class MenuEntry private constructor(item: ItemStack, private val action: MenuAction, private val clickSound: String?) {
    private val item = item.clone()
    fun item(): ItemStack = item.clone()
    fun action(): MenuAction = action
    fun clickSound(): Optional<String> = Optional.ofNullable(clickSound)
    fun withClickSound(soundName: String): MenuEntry = MenuEntry(item, action, soundName)
    companion object {
        @JvmStatic fun of(item: ItemStack): MenuEntry = MenuEntry(item, MenuAction.none(), null)
        @JvmStatic fun of(item: ItemStack, action: MenuAction): MenuEntry = MenuEntry(item, action, null)
    }
}
