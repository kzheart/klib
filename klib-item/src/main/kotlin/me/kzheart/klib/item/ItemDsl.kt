package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class ItemDsl

/** Delegates mutations and defensive copying to the established Java builder. */
fun item(material: Material, block: @ItemDsl ItemBuilder.() -> Unit = {}): ItemStack =
    Items.of(material).apply(block).build()

fun item(material: String, block: @ItemDsl ItemBuilder.() -> Unit = {}): ItemStack =
    Items.of(material).apply(block).build()

/** Both the source stack and the returned stack remain independently owned. */
fun ItemStack.editItem(block: @ItemDsl ItemBuilder.() -> Unit): ItemStack =
    Items.edit(this).apply(block).build()
