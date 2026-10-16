package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.util.UUID

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class ItemDsl

/** Native properties and typed tags backed by the same validated Java item engine. */
@ItemDsl
class ItemScope internal constructor(private val builder: ItemBuilder) {
    var amount: Int
        get() = builder.build().amount
        set(value) { builder.amount(value) }
    var name: String
        get() = builder.build().itemMeta?.displayName.orEmpty()
        set(value) { builder.name(value) }
    var lore: List<String>
        get() = builder.build().itemMeta?.lore?.toList().orEmpty()
        set(value) { builder.lore(value) }
    var customModelData: Int?
        get() = Items.customModelData(builder.build())
        set(value) { builder.customModelData(value) }
    var unbreakable: Boolean
        get() = builder.build().itemMeta?.isUnbreakable ?: false
        set(value) { builder.unbreakable(value) }

    fun enchant(enchantment: Enchantment, level: Int) { builder.enchant(enchantment, level) }
    fun flags(vararg flags: ItemFlag) { builder.flags(*flags) }
    fun glow(value: Boolean = true) { builder.glow(value) }
    fun skullOwner(owner: OfflinePlayer) { builder.skullOwner(owner) }
    fun skullOwner(owner: UUID) { builder.skullOwner(owner) }
    fun appendLore(vararg lines: String) { builder.appendLore(*lines) }
    operator fun <T> get(key: TagKey<T>): T? = builder.tag(key)
    operator fun <T : Any> set(key: TagKey<T>, value: T) { builder.tag(key, value) }
    fun removeTag(key: TagKey<*>) { builder.removeTag(key) }
    internal fun build(): ItemStack = builder.build()
}

fun item(material: Material, block: ItemScope.() -> Unit = {}): ItemStack =
    ItemScope(Items.of(material)).apply(block).build()

fun item(material: String, block: ItemScope.() -> Unit = {}): ItemStack =
    ItemScope(Items.of(material)).apply(block).build()

/** Edits a defensive copy; neither the source nor a returned stack aliases the builder. */
fun ItemStack.editItem(block: ItemScope.() -> Unit): ItemStack =
    ItemScope(Items.edit(this)).apply(block).build()

/** Typed access to the established TagKey engine; absent tags return null. */
operator fun <T> ItemStack.get(key: TagKey<T>): T? = key.get(this)
/** Like Bukkit ItemStack setters, this mutates the receiver. Use editItem for a copy. */
operator fun <T : Any> ItemStack.set(key: TagKey<T>, value: T) { key.set(this, value) }
fun ItemStack.removeTag(key: TagKey<*>) { key.remove(this) }
