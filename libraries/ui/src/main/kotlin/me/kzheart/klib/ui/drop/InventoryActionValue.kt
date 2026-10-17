package me.kzheart.klib.ui.drop

import me.kzheart.klib.ui.drop.InventoryAction.Type

import org.bukkit.inventory.ItemStack
import java.util.Collections

/** 投放区状态机的不可变输入。 */
internal class InventoryActionValue private constructor(private val type: Type, private val slot: Int, item: ItemStack?,
                                         private val amount: Int, dragAmounts: Map<Int, Int?>) {
    private val item = item?.clone()
    private val dragAmounts = Collections.unmodifiableMap(LinkedHashMap(dragAmounts))
    init { require(amount > 0) { "amount must be positive" } }
    fun type(): Type = type
    fun slot(): Int = slot
    fun item(): ItemStack? = item?.clone()
    fun amount(): Int = amount
    fun dragAmounts(): Map<Int, Int?> = dragAmounts
    companion object {
        @JvmStatic fun place(slot: Int, item: ItemStack?, amount: Int): InventoryActionValue = InventoryActionValue(Type.PLACE, slot, item, amount, emptyMap())
        @JvmStatic fun shiftInsert(item: ItemStack): InventoryActionValue = InventoryActionValue(Type.SHIFT_INSERT, -1, item, item.amount, emptyMap())
        @JvmStatic fun drag(item: ItemStack, amounts: Map<Int, Int?>): InventoryActionValue = InventoryActionValue(Type.DRAG, -1, item, item.amount, amounts)
        @JvmStatic fun take(slot: Int, amount: Int): InventoryActionValue = InventoryActionValue(Type.TAKE, slot, null, amount, emptyMap())
    }
}
