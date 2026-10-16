@file:JvmName("EventDsl")

package me.kzheart.klib.event

import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.event.Event
import org.bukkit.event.EventPriority

/** Keeps Bukkit's event thread and synchronous cancellation/priority semantics. */
public inline fun <reified E : Event> Scope.on(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: (E) -> Unit,
): Disposable = on(E::class.java, priority, ignoreCancelled, handler)

/** Keeps Bukkit's event thread and synchronous cancellation/priority semantics. */
public inline fun <reified E : Event> Events.on(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: (E) -> Unit,
): Disposable = on(E::class.java, priority, ignoreCancelled, handler)
