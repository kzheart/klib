package me.kzheart.klib.ui.drop

import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.function.Predicate

/** 串行化的投放区状态机；只持有克隆，不修改动作的源物品。 */
class DropZoneController(slots: Set<Int?>, private val accepts: Predicate<ItemStack>) {
    private val slots: Set<Int>
    private val contents = LinkedHashMap<Int, ItemStack>()
    private var closed = false
    init {
        require(slots.isNotEmpty()) { "drop zone must contain at least one slot" }
        this.slots = Collections.unmodifiableSet(slots.mapTo(LinkedHashSet()) {
            require(it != null && it >= 0) { "drop-zone slots must not be negative" }; it
        })
    }
    @Synchronized fun handle(action: InventoryAction): DropResult {
        check(!closed) { "drop zone is closed" }
        return when (action.type()) {
            InventoryAction.Type.PLACE -> place(action.slot(), requireItem(action), action.amount())
            InventoryAction.Type.SHIFT_INSERT -> insertAcross(requireItem(action), action.amount())
            InventoryAction.Type.DRAG -> drag(requireItem(action), action.dragAmounts())
            InventoryAction.Type.TAKE -> take(action.slot(), action.amount())
        }
    }
    fun slots(): Set<Int> = slots
    @Synchronized fun snapshot(): Map<Int, ItemStack> = Collections.unmodifiableMap(contents.mapValuesTo(LinkedHashMap()) { it.value.clone() })
    @Synchronized fun closeAndDrain(): List<ItemStack> {
        if (closed) return emptyList()
        closed = true
        return contents.values.map { it.clone() }.also { contents.clear() }
    }
    @get:JvmName("closedProperty")
    @get:JvmSynthetic
    val isClosed: Boolean get() = isClosed()
    @Synchronized fun isClosed(): Boolean = closed

    private fun drag(source: ItemStack, requested: Map<Int, Int?>): DropResult {
        if (!accepts.test(source)) return DropResult(0, null)
        var totalRequested = 0L
        for ((slot, amount) in requested) {
            requireSlot(slot)
            totalRequested += requirePositive(amount, "drag amount")
        }
        require(totalRequested <= source.amount) { "drag amounts exceed the source stack" }
        var available = source.amount
        val perSlot = LinkedHashMap<Int, Int>()
        for ((slot, amount) in requested) {
            if (available == 0) break
            val moved = addToSlot(slot, source, minOf(requirePositive(amount, "drag amount"), available))
            recordMove(perSlot, slot, moved)
            available -= moved
        }
        return DropResult(source.amount - available, null, perSlot)
    }

    private fun insertAcross(source: ItemStack, requested: Int): DropResult {
        if (!accepts.test(source)) return DropResult(0, null)
        var remaining = minOf(requested, source.amount)
        val perSlot = LinkedHashMap<Int, Int>()
        // 先填现有同类堆，再按声明顺序使用空槽。
        for (pass in 0..1) for (slot in slots) {
            val present = contents[slot]
            if (pass == 0 && (present == null || !present.isSimilar(source))) continue
            if (pass == 1 && present != null) continue
            val moved = addToSlot(slot, source, remaining)
            recordMove(perSlot, slot, moved)
            remaining -= moved
            if (remaining == 0) return DropResult(perSlot.values.sum(), null, perSlot)
        }
        return DropResult(perSlot.values.sum(), null, perSlot)
    }
    private fun place(slot: Int, source: ItemStack, requested: Int): DropResult {
        requireSlot(slot)
        if (!accepts.test(source)) return DropResult(0, null)
        val moved = addToSlot(slot, source, minOf(requested, source.amount))
        return DropResult(moved, null, if (moved > 0) mapOf(slot to moved) else emptyMap())
    }
    private fun addToSlot(slot: Int, source: ItemStack, requested: Int): Int {
        val present = contents[slot]
        if (present != null && !present.isSimilar(source)) return 0
        val current = present?.amount ?: 0
        val moved = minOf(requested, maxOf(0, source.maxStackSize - current))
        if (moved == 0) return 0
        contents[slot] = (present ?: source).clone().apply { amount = current + moved }
        return moved
    }
    private fun take(slot: Int, requested: Int): DropResult {
        requireSlot(slot)
        val present = contents[slot] ?: return DropResult(0, null)
        val amount = minOf(requested, present.amount)
        val removed = present.clone().apply { this.amount = amount }
        if (amount == present.amount) contents.remove(slot)
        else contents[slot] = present.clone().apply { this.amount -= amount }
        return DropResult(0, removed)
    }
    private fun requireSlot(slot: Int) { require(slot in slots) { "slot is not in this drop zone: $slot" } }
    private fun requireItem(action: InventoryAction): ItemStack = java.util.Objects.requireNonNull(action.item(), "action item")!!
    private fun requirePositive(value: Int?, name: String): Int { require(value != null && value > 0) { "$name must be positive" }; return value }
    private fun recordMove(perSlot: MutableMap<Int, Int>, slot: Int, moved: Int) { if (moved > 0) perSlot[slot] = (perSlot[slot] ?: 0) + moved }
}
