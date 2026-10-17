package me.kzheart.klib.event

import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import me.kzheart.klib.reflect.Declarations
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class Events(private val owner: Scope) {
    fun <E : Event> on(type: Class<E>, handler: Consumer<in E>): Disposable = owner.on(type, handler)
    fun <E : Event> on(type: Class<E>, priority: EventPriority, ignoreCancelled: Boolean, handler: Consumer<in E>): Disposable =
        owner.on(type, priority, ignoreCancelled, handler)
    fun register(listener: Listener): Disposable {
        val methods = Declarations.methods(listener.javaClass).filter { it.isAnnotationPresent(EventHandler::class.java) }
        methods.forEach { method ->
            Declarations.voidMethod(method, 1)
            if (!Event::class.java.isAssignableFrom(method.parameterTypes[0])) throw Declarations.invalid(method, "parameter must extend Event")
        }
        return owner.scope("events-${ids.incrementAndGet()}") { child ->
            methods.forEach { method ->
                val annotation = method.getAnnotation(EventHandler::class.java)
                val type = method.parameterTypes[0].asSubclass(Event::class.java)
                child.on(type, annotation.priority, annotation.ignoreCancelled) { Declarations.invoke(listener, method, it) }
            }
        }
    }
    companion object { private val ids = AtomicLong() }
}
