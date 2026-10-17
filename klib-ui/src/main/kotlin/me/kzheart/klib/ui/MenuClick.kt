package me.kzheart.klib.ui

import org.bukkit.entity.Player

/** 传递给菜单动作的稳定点击输入。 */
class MenuClick internal constructor(private val player: Player, private val slot: Int,
                                     private val type: MenuClickType, private val refresh: Runnable) {
    constructor(player: Player, slot: Int) : this(player, slot, MenuClickType.LEFT)
    constructor(player: Player, slot: Int, type: MenuClickType) : this(player, slot, type,
        Runnable { throw IllegalStateException("Refresh requires an annotated menu") })
    init { require(slot >= 0) { "slot must not be negative" } }
    fun refresh() = refresh.run()
    fun player(): Player = player
    fun slot(): Int = slot
    fun type(): MenuClickType = type
}
