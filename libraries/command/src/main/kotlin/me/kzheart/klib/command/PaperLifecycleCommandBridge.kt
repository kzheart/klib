package me.kzheart.klib.command

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import me.kzheart.klib.command.api.CommandSpec
import me.kzheart.klib.scope.Disposable
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.Plugin

/** Registrar access is confined to the Paper COMMANDS callback; bindings only retain their own state. */
internal class PaperLifecycleCommandBridge private constructor(private val plugin: Plugin, private val commandMap: CommandMap,
    private val policy: CommandRegistrationPolicy, private val manager: Any, private val eventType: Any,
    private val handlerType: Class<*>, private val basicType: Class<*>) : CommandBridge {
    private val declarations = hashSetOf<String>()
    override fun register(name: String, spec: CommandSpec, dispatcher: CommandDispatcher): Disposable {
        BukkitCommandRegistrar.requirePrimaryThread()
        val label = name.lowercase(Locale.ROOT)
        check(declarations.add(label)) { "Command already declared: $label" }
        val binding = Binding(label, spec, dispatcher)
        val basic = Proxy.newProxyInstance(basicType.classLoader, arrayOf(basicType), binding)
        val handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType)) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> objectMethod(proxy, method, args)
                method.name != "run" -> throw UnsupportedOperationException(method.toString())
                !binding.active() -> null
                else -> {
                    BukkitCommandRegistrar.requirePrimaryThread()
                    try {
                        binding.checkCollision()
                        val registrar = requireNotNull(PublicCommandReflection.invoke(args[0], "registrar"))
                        PublicCommandReflection.invoke(registrar, "register", label, binding.description, emptyList<Any>(), basic)
                        binding.registered = true
                        null
                    } catch (failure: RuntimeException) { binding.dispose(); throw failure }
                    catch (failure: Error) { binding.dispose(); throw failure }
                }
            }
        }
        try {
            PublicCommandReflection.invoke(manager, "registerEventHandler", eventType, handler)
            return binding
        } catch (failure: RuntimeException) { binding.dispose(); declarations.remove(label); throw failure }
        catch (failure: Error) { binding.dispose(); declarations.remove(label); throw failure }
    }
    private inner class Binding(private val label: String, spec: CommandSpec, private val dispatcher: CommandDispatcher) : Disposable, InvocationHandler {
        private val namespaced = "${plugin.name.lowercase(Locale.ROOT)}:$label"
        val description = (spec as CommandSpecImpl).root().description
        @Volatile private var enabled = true
        var registered = false
        fun active(): Boolean = enabled && plugin.isEnabled
        override fun dispose() { enabled = false }
        fun checkCollision() {
            val bare = commandMap.getCommand(label)
            val qualified = commandMap.getCommand(namespaced)
            check(!(qualified != null && !previouslyOwned(qualified) || bare != null && !previouslyOwned(bare) &&
                (policy == CommandRegistrationPolicy.REJECT || ':' in label))) { "Command label already registered: $label" }
        }
        private fun previouslyOwned(command: Command): Boolean = registered && command is PluginIdentifiableCommand && command.plugin === plugin
        @Suppress("UNCHECKED_CAST") override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return objectMethod(proxy, method, args)
            return when (method.name) {
                "permission" -> null
                "canUse" -> active()
                "execute" -> {
                    if (active()) {
                        BukkitCommandRegistrar.requirePrimaryThread()
                        dispatcher.execute(sender(requireNotNull(args?.get(0))), args[1] as Array<String>)
                    }
                    null
                }
                "suggest" -> if (!active()) emptyList<String>() else {
                    BukkitCommandRegistrar.requirePrimaryThread()
                    dispatcher.complete(sender(requireNotNull(args?.get(0))), args[1] as Array<String>)
                }
                else -> throw UnsupportedOperationException(method.toString())
            }
        }
    }
    companion object {
        private const val EVENTS = "io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents"
        private const val HANDLER = "io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler"
        private const val BASIC = "io.papermc.paper.command.brigadier.BasicCommand"
        @JvmStatic fun discover(plugin: Plugin, map: CommandMap, policy: CommandRegistrationPolicy): CommandBridge? = try {
            val events = PublicCommandReflection.type(EVENTS)
            val handler = PublicCommandReflection.type(HANDLER)
            val basic = PublicCommandReflection.type(BASIC)
            PaperLifecycleCommandBridge(plugin, map, policy, requireNotNull(PublicCommandReflection.invoke(plugin, "getLifecycleManager")),
                events.getField("COMMANDS").get(null), handler, basic)
        } catch (missing: ClassNotFoundException) { null }
        catch (failure: ReflectiveOperationException) { throw IllegalStateException("Cannot access Paper COMMANDS lifecycle", failure) }
        private fun sender(source: Any): CommandSender = PublicCommandReflection.invoke(source, "getSender") as CommandSender
        private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any = when (method.name) {
            "toString" -> "Klib lifecycle command"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> throw UnsupportedOperationException(method.toString())
        }
    }
}
