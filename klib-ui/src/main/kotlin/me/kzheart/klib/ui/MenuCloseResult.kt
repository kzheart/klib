package me.kzheart.klib.ui

import org.bukkit.inventory.ItemStack
import java.util.Collections

/** 确保未归还物品不会被静默丢弃的关闭结果。 */
class MenuCloseResult internal constructor(private val reason: CloseReason, unreturned: List<ItemStack>) {
    private val unreturned = Collections.unmodifiableList(unreturned.map { it.clone() })
    fun reason(): CloseReason = reason
    /** 关闭时创建的防御性副本；修改前请先克隆。 */
    fun unreturned(): List<ItemStack> = unreturned
}
