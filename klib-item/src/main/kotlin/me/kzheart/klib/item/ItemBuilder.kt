package me.kzheart.klib.item

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.OfflinePlayer
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

/** 可变的流式编辑器，返回独立的 ItemStack。 */
class ItemBuilder internal constructor(private val item: ItemStack) {
    fun amount(amount: Int): ItemBuilder = apply {
        require(amount in 1..item.maxStackSize) { "Amount is outside the material stack range: $amount" }
        item.amount = amount
    }
    fun name(name: String): ItemBuilder = editMeta { setDisplayName(color(name)) }
    fun lore(vararg lines: String): ItemBuilder = lore(lines.asList())
    fun lore(lines: List<String>): ItemBuilder = editMeta { lore = lines.map(::color) }
    fun appendLore(vararg lines: String): ItemBuilder = editMeta {
        lore = (if (hasLore()) lore.orEmpty() else emptyList()) + lines.map(::color)
    }
    fun enchant(enchantment: Enchantment, level: Int): ItemBuilder = apply {
        require(level >= 1) { "Enchantment level must be positive" }
        item.addUnsafeEnchantment(enchantment, level)
    }
    fun flags(vararg flags: ItemFlag): ItemBuilder = editMeta { addItemFlags(*flags) }
    fun customModelData(value: Int?): ItemBuilder = apply {
        val meta = requireMeta()
        if (ItemMetaReflection.invokeIfPresent(meta, "setCustomModelData", arrayOf(Int::class.javaObjectType), value)) item.itemMeta = meta
    }
    fun unbreakable(value: Boolean): ItemBuilder = editMeta { isUnbreakable = value }
    fun glow(value: Boolean): ItemBuilder = editMeta {
        if (!ItemMetaReflection.invokeIfPresent(this, "setEnchantmentGlintOverride", arrayOf(Boolean::class.javaObjectType), if (value) true else null)) {
            if (value) { addEnchant(Enchantment.LURE, 1, true); addItemFlags(ItemFlag.HIDE_ENCHANTS) }
            else if (getEnchantLevel(Enchantment.LURE) == 1 && hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
                removeEnchant(Enchantment.LURE); removeItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
        }
    }
    fun skullOwner(owner: OfflinePlayer): ItemBuilder = editMeta {
        require(this is SkullMeta) { "Item is not a skull: ${item.type}" }
        owningPlayer = owner
    }
    fun skullOwner(owner: UUID): ItemBuilder = skullOwner(Bukkit.getOfflinePlayer(owner))
    fun <T> tag(key: TagKey<T>, value: T): ItemBuilder = apply { key.set(item, value) }
    fun <T> tag(key: TagKey<T>): T? = key.get(item)
    fun removeTag(key: TagKey<*>): ItemBuilder = apply { key.remove(item) }
    fun build(): ItemStack = item.clone()
    private inline fun editMeta(edit: ItemMeta.() -> Unit): ItemBuilder = apply {
        val meta = requireMeta(); meta.edit(); item.itemMeta = meta
    }
    private fun requireMeta(): ItemMeta = item.itemMeta
        ?: throw IllegalArgumentException("Material does not support metadata: ${item.type}")
    private fun color(value: String): String = ChatColor.translateAlternateColorCodes('&', value)
}
