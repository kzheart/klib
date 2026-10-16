package me.kzheart.klib.scope

import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import me.kzheart.klib.KLogger
import me.kzheart.klib.component.KContext
import me.kzheart.klib.event.KEventDispatcher
import me.kzheart.klib.event.on
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Compiles and executes a Kotlin consumer using only public Core entry points. */
class KotlinCoreConsumerTest {
    @Test
    fun `typed event registration retains cancellation priority and synchronous delivery`() {
        val registered = AtomicReference<EventExecutor>()
        val listener = AtomicReference<Listener>()
        val manager = proxy<PluginManager> { method, arguments ->
            if (method == "registerEvent") {
                assertEquals(ConsumerEvent::class.java, arguments[0])
                assertEquals(EventPriority.HIGH, arguments[2])
                assertEquals(true, arguments[5])
                listener.set(arguments[1] as Listener)
                registered.set(arguments[3] as EventExecutor)
            }
            null
        }
        var calls = 0
        var handlingThread: Thread? = null
        lateinit var subscription: Disposable
        val root = scope("consumer") {
            registerCapability<CharSequence>("ready")
            registerCapability(install(KEventDispatcher(
                proxy<Plugin> { _, _ -> null }, manager, KLogger(Logger.getLogger("consumer")),
            )))
            childScope("listeners") {
                assertEquals("ready", requireCapability<CharSequence>())
                assertNull(findCapability<Runnable>())
                subscription = on<ConsumerEvent>(priority = EventPriority.HIGH, ignoreCancelled = true) {
                    calls++
                    handlingThread = Thread.currentThread()
                    it.isCancelled = true
                }
            }
        }
        val event = ConsumerEvent()
        registered.get().execute(listener.get(), event)
        assertEquals(1, calls)
        assertTrue(event.isCancelled)
        assertSame(Thread.currentThread(), handlingThread)
        subscription.dispose()
        registered.get().execute(listener.get(), ConsumerEvent())
        assertEquals(1, calls)
        root.close()
    }

    @Test
    fun `context event facade supports reified options and closes with the scope`() {
        val registered = AtomicReference<EventExecutor>()
        val listener = AtomicReference<Listener>()
        val manager = proxy<PluginManager> { method, arguments ->
            if (method == "registerEvent") {
                assertEquals(EventPriority.NORMAL, arguments[2])
                assertEquals(false, arguments[5])
                listener.set(arguments[1] as Listener)
                registered.set(arguments[3] as EventExecutor)
            }
            null
        }
        var calls = 0
        val root = scope("facade") {
            registerCapability(install(KEventDispatcher(
                proxy<Plugin> { _, _ -> null }, manager, KLogger(Logger.getLogger("facade")),
            )))
            KContext(this).events().on<ConsumerEvent> { calls++ }
        }
        registered.get().execute(listener.get(), ConsumerEvent())
        assertEquals(1, calls)
        root.close()
        registered.get().execute(listener.get(), ConsumerEvent())
        assertEquals(1, calls)
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args ?: emptyArray()) ?: when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as T

    class ConsumerEvent : Event(), Cancellable {
        private var cancelled = false
        override fun isCancelled(): Boolean = cancelled
        override fun setCancelled(cancel: Boolean) { cancelled = cancel }
        override fun getHandlers(): HandlerList = HANDLERS
        companion object {
            private val HANDLERS = HandlerList()
            @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
        }
    }
}
