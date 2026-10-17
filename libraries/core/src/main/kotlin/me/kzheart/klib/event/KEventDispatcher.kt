package me.kzheart.klib.event

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import me.kzheart.klib.KLogger
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager

/** One Bukkit registration per route; every consumer remains owned by its scope. */
class KEventDispatcher(private val plugin: Plugin, private val pluginManager: PluginManager, private val logger: KLogger) : Disposable {
    private val lifecycleLock = Any()
    private val routes = HashMap<RouteKey, Route>()
    @Volatile private var closed = false
    private val executor = EventExecutor { listener, event ->
        for (subscription in (listener as RouteListener).route.subscriptions) {
            try { subscription.accept(event) } catch (failure: Throwable) {
                logger.error("事件处理失败: ${event.eventName}", failure)
            }
        }
    }
    fun <E : Event> on(scope: Scope, eventType: Class<E>, handler: Consumer<in E>): Disposable =
        on(scope, eventType, EventPriority.NORMAL, false, handler)
    fun <E : Event> on(scope: Scope, eventType: Class<E>, priority: EventPriority,
                      ignoreCancelled: Boolean, handler: Consumer<in E>): Disposable {
        val subscription = scope.install(Subscription(scope, eventType, handler))
        try {
            synchronized(lifecycleLock) {
                if (closed) { subscription.disposed = true; error("Event dispatcher is closed") }
                check(!subscription.disposed) { "Scope closed while registering event handler" }
                val key = RouteKey(eventType, priority, ignoreCancelled)
                val route = routes[key] ?: Route(key).also {
                    try { pluginManager.registerEvent(eventType, it.listener, priority, executor, plugin, ignoreCancelled) }
                    catch (failure: RuntimeException) { subscription.disposed = true; throw failure }
                    routes[key] = it
                }
                subscription.route = route
                route.subscriptions.add(subscription)
            }
        } catch (failure: RuntimeException) { scope.remove(subscription); throw failure }
        return subscription
    }
    override fun dispose(): Unit = synchronized(lifecycleLock) {
        if (closed) return
        closed = true
        routes.values.forEach { it.subscriptions.clear(); HandlerList.unregisterAll(it.listener) }
        routes.clear()
    }
    private fun remove(subscription: Subscription<*>) = synchronized(lifecycleLock) {
        val route = checkNotNull(subscription.route)
        route.subscriptions.remove(subscription)
        if (route.subscriptions.isEmpty() && routes.remove(route.key) != null) HandlerList.unregisterAll(route.listener)
    }
    private inner class Subscription<E : Event>(private val owner: Scope, private val type: Class<E>, private val handler: Consumer<in E>) : Disposable {
        var route: Route? = null
        @Volatile var disposed = false
        fun accept(event: Event) {
            // Sibling subclasses may share HandlerList; dispatch only the requested type.
            if (type.isInstance(event) && !disposed && !closed) handler.accept(type.cast(event))
        }
        override fun dispose() {
            synchronized(lifecycleLock) {
                if (disposed) return
                disposed = true
                if (route == null) return
            }
            remove(this)
            owner.remove(this)
        }
    }
    private class Route(val key: RouteKey) {
        val listener = RouteListener(this)
        val subscriptions = CopyOnWriteArrayList<Subscription<*>>()
    }
    private class RouteListener(val route: Route) : Listener
    private data class RouteKey(val eventType: Class<out Event>, val priority: EventPriority, val ignoreCancelled: Boolean)
}
