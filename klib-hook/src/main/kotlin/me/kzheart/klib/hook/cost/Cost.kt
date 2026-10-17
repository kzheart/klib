package me.kzheart.klib.hook.cost

import org.bukkit.entity.Player

/** 一项消耗或前置条件；方法访问玩家状态，应在 Bukkit 主线程调用。 */
interface Cost {
    fun describe(): String
    fun test(player: Player): Boolean
    fun take(player: Player): Charge
}
