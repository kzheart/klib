package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import java.util.Locale

/** 创建和编辑物品的入口。 */
class Items private constructor() {
    companion object {
        @JvmStatic fun edit(item: ItemStack): ItemBuilder = ItemBuilder(item.clone())
        @JvmStatic fun of(material: Material): ItemBuilder = ItemBuilder(ItemStack(material))
        @JvmStatic fun of(material: String): ItemBuilder = of(resolveMaterial(material))
        @JvmStatic @Suppress("DEPRECATION") fun playerHead(): ItemBuilder {
            Material.getMaterial("PLAYER_HEAD")?.let { return of(it) }
            val legacy = Material.getMaterial("SKULL_ITEM") ?: throw IllegalStateException("Server has neither PLAYER_HEAD nor SKULL_ITEM")
            return ItemBuilder(ItemStack(legacy, 1, 3.toShort()))
        }
        @JvmStatic fun customModelData(item: ItemStack?): Int? {
            if (InventoryItems.isAir(item) || !item!!.hasItemMeta()) return null
            val meta = item.itemMeta ?: return null
            if (ItemMetaReflection.invokeOrNull(meta, "hasCustomModelData") != true) return null
            return ItemMetaReflection.invokeOrNull(meta, "getCustomModelData") as Int?
        }
        @JvmStatic fun supportsCustomModelData(): Boolean = try {
            ItemMeta::class.java.getMethod("setCustomModelData", Int::class.javaObjectType); true
        } catch (_: NoSuchMethodException) { false }
        @JvmStatic fun resolveMaterial(input: String): Material {
            val name = input.trim().substringAfter(':').uppercase(Locale.ROOT).replace('-', '_')
            return Material.matchMaterial(name) ?: throw IllegalArgumentException("Unknown material: $input")
        }
    }
}
