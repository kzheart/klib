package me.kzheart.klib.hook.cost

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 发放物品，背包放不下的部分掉落在玩家脚下。 */
internal class Inventories private constructor() {
    companion object {
        @JvmStatic fun give(player: Player, items: Collection<ItemStack>) {
            if (items.isEmpty()) return
            val overflow = player.inventory.addItem(*items.toTypedArray())
            for (item in overflow.values) player.world.dropItemNaturally(player.location, item)
        }
    }
}
