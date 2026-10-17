package me.kzheart.klib.ui.drop

import org.bukkit.inventory.ItemStack
import java.util.Collections

/** 投放区状态机的不可变输入。 */
class InventoryAction private constructor(private val type: Type, private val slot: Int, item: ItemStack?,
                                         private val amount: Int, dragAmounts: Map<Int, Int?>) {
    enum class Type { PLACE, SHIFT_INSERT, DRAG, TAKE }
    private val item = item?.clone()
    private val dragAmounts = Collections.unmodifiableMap(LinkedHashMap(dragAmounts))
    init { require(amount > 0) { "amount must be positive" } }
    fun type(): Type = type
    fun slot(): Int = slot
    fun item(): ItemStack? = item?.clone()
    fun amount(): Int = amount
    fun dragAmounts(): Map<Int, Int?> = dragAmounts
    companion object {
        @JvmStatic fun place(slot: Int, item: ItemStack?, amount: Int): InventoryAction = InventoryAction(Type.PLACE, slot, item, amount, emptyMap())
        @JvmStatic fun shiftInsert(item: ItemStack): InventoryAction = InventoryAction(Type.SHIFT_INSERT, -1, item, item.amount, emptyMap())
        @JvmStatic fun drag(item: ItemStack, amounts: Map<Int, Int?>): InventoryAction = InventoryAction(Type.DRAG, -1, item, item.amount, amounts)
        @JvmStatic fun take(slot: Int, amount: Int): InventoryAction = InventoryAction(Type.TAKE, slot, null, amount, emptyMap())
    }
}
