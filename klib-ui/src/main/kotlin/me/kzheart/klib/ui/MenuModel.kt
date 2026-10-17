package me.kzheart.klib.ui

import java.util.Collections
import java.util.Optional

/** 字符映射、显式槽位和 YAML 模板共用的不可变输出。 */
class MenuModel internal constructor(private val title: String, private val rows: Int,
                                     private val cancelClicks: Boolean, entries: Map<Int, MenuEntry>) {
    private val entries: Map<Int, MenuEntry>
    init {
        require(rows in 1..6) { "rows must be between 1 and 6" }
        val copy = LinkedHashMap<Int, MenuEntry>()
        for ((slot, entry) in entries) {
            require(slot in 0 until rows * 9) { "slot is outside menu: $slot" }
            copy[slot] = java.util.Objects.requireNonNull(entry, "entry")
        }
        this.entries = Collections.unmodifiableMap(copy)
    }
    fun title(): String = title
    fun rows(): Int = rows
    fun size(): Int = rows * 9
    fun cancelClicks(): Boolean = cancelClicks
    fun entries(): Map<Int, MenuEntry> = entries
    fun entry(slot: Int): Optional<MenuEntry> = Optional.ofNullable(entries[slot])
    fun shouldCancel(clickedTopInventory: Boolean): Boolean = cancelClicks && clickedTopInventory
    fun shouldCancel(type: MenuClickType, clickedTopInventory: Boolean): Boolean =
        cancelClicks && (clickedTopInventory || type.shift() || type == MenuClickType.DOUBLE_CLICK)
    fun shouldCancelDrag(rawSlots: Set<Int?>, topSize: Int): Boolean =
        cancelClicks && rawSlots.any { it != null && it >= 0 && it < topSize }
}
