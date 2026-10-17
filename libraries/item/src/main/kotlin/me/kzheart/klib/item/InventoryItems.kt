package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** 事务式背包操作工具。 */
class InventoryItems private constructor() {
    companion object {
        @JvmStatic fun isAir(item: ItemStack?): Boolean = item == null || item.type == Material.AIR || item.amount <= 0
        @JvmStatic fun count(inventory: Inventory, prototype: ItemStack): Int =
            inventory.storageContents.filter { !isAir(it) && it.isSimilar(prototype) }.sumOf { it.amount }
        @JvmStatic fun take(inventory: Inventory, prototype: ItemStack, amount: Int): Boolean {
            require(amount >= 0) { "Amount must not be negative" }
            if (amount == 0) return true
            if (count(inventory, prototype) < amount) return false
            var remaining = amount
            val contents = inventory.storageContents
            for (slot in contents.indices) {
                if (remaining <= 0) break
                val item = contents[slot]
                if (isAir(item) || !item.isSimilar(prototype)) continue
                val consumed = minOf(remaining, item.amount)
                remaining -= consumed
                contents[slot] = if (consumed == item.amount) null else item.clone().apply { this.amount -= consumed }
            }
            inventory.storageContents = contents
            return true
        }
        @JvmStatic fun hasSpace(inventory: Inventory, item: ItemStack, amount: Int): Boolean {
            require(amount >= 0) { "Amount must not be negative" }
            var capacity = 0
            for (present in inventory.storageContents) {
                if (isAir(present)) capacity += item.maxStackSize
                else if (present.isSimilar(item)) capacity += maxOf(0, present.maxStackSize - present.amount)
                if (capacity >= amount) return true
            }
            return amount == 0
        }
        @JvmStatic fun give(player: Player, vararg items: ItemStack): List<ItemStack> =
            player.inventory.addItem(*items).values.map { item ->
                player.world.dropItemNaturally(player.location, item)
                item.clone()
            }
    }
}
