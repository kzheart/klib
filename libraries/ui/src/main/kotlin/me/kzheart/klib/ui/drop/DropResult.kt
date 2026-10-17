package me.kzheart.klib.ui.drop

import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.Optional

/** 一次原子投放区状态转换的结果。 */
class DropResult internal constructor(private val accepted: Int, removed: ItemStack?, acceptedBySlot: Map<Int, Int>) {
    internal constructor(accepted: Int, removed: ItemStack?) : this(accepted, removed, emptyMap())
    private val removed = removed?.clone()
    private val acceptedBySlot = Collections.unmodifiableMap(LinkedHashMap(acceptedBySlot))
    fun accepted(): Int = accepted
    fun removed(): Optional<ItemStack> = Optional.ofNullable(removed?.clone())
    fun acceptedBySlot(): Map<Int, Int> = acceptedBySlot
}
