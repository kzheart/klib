package me.kzheart.klib.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event

abstract class KEvent : Event, Cancellable {
    private var cancelled = false
    protected constructor() : super()
    protected constructor(asynchronous: Boolean) : super(asynchronous)
    fun call(): Boolean { Bukkit.getPluginManager().callEvent(this); return !cancelled }
    final override fun isCancelled(): Boolean = cancelled
    final override fun setCancelled(cancelled: Boolean) { this.cancelled = cancelled }
}
