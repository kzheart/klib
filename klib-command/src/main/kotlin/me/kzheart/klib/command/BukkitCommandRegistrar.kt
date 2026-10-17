package me.kzheart.klib.command

import java.util.Locale
import me.kzheart.klib.command.api.CommandSpec
import me.kzheart.klib.scope.Disposable
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

/** Startup-only binding adapter. Disposal disables a binding without mutating the live server tree. */
class BukkitCommandRegistrar private constructor(private val commandMap: CommandMap,
    private val knownCommands: MutableMap<String, Command>, fallbackPrefix: String,
    private val policy: CommandRegistrationPolicy, private val plugin: Plugin?) : CommandBridge {
    private val fallbackPrefix = fallbackPrefix.trim().lowercase(Locale.ROOT)
    init { require(fallbackPrefix.trim().isNotEmpty()) { "fallbackPrefix must not be blank" } }
    constructor(commandMap: CommandMap, knownCommands: MutableMap<String, Command>, fallbackPrefix: String) :
        this(commandMap, knownCommands, fallbackPrefix, CommandRegistrationPolicy.REJECT, null)
    constructor(commandMap: CommandMap, knownCommands: MutableMap<String, Command>, fallbackPrefix: String, policy: CommandRegistrationPolicy) :
        this(commandMap, knownCommands, fallbackPrefix, policy, null)
    override fun register(name: String, spec: CommandSpec, dispatcher: CommandDispatcher): Disposable {
        requirePrimaryThread()
        val label = name.lowercase(Locale.ROOT)
        val namespaced = "$fallbackPrefix:$label"
        val previous = knownCommands[label]
        check(!knownCommands.containsKey(namespaced) && (previous == null || policy != CommandRegistrationPolicy.REJECT && ':' !in label)) {
            "Command label already registered: $label"
        }
        val command = if (plugin == null) DispatchingCommand(name, dispatcher, null) else OwnedDispatchingCommand(name, dispatcher, plugin)
        if (spec is CommandSpecImpl) { command.permission = spec.root().permission; command.description = spec.root().description }
        command.usage = "/$name"
        if (previous != null) knownCommands.remove(label)
        try {
            check(commandMap.register(fallbackPrefix, command)) { "Bukkit rejected command registration: $name" }
            command.active = true
            return command
        } catch (failure: RuntimeException) { rollback(command, label, previous); throw failure }
        catch (failure: Error) { rollback(command, label, previous); throw failure }
    }
    private fun rollback(command: Command, label: String, previous: Command?) {
        command.unregister(commandMap)
        removeByIdentity(knownCommands, command)
        if (previous != null && !knownCommands.containsKey(label)) knownCommands[label] = previous
    }
    private open class DispatchingCommand(name: String, private val dispatcher: CommandDispatcher, private val plugin: Plugin?) : Command(name), Disposable {
        @Volatile var active = false
        private fun active(): Boolean = active && (plugin?.isEnabled != false)
        override fun dispose() { active = false }
        override fun testPermissionSilent(sender: CommandSender): Boolean = active() && super.testPermissionSilent(sender)
        override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
            if (!active()) return false
            requirePrimaryThread()
            dispatcher.execute(sender, args)
            return true
        }
        override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> {
            if (!active()) return emptyList()
            requirePrimaryThread()
            return dispatcher.complete(sender, args)
        }
    }
    private class OwnedDispatchingCommand(name: String, dispatcher: CommandDispatcher, private val owner: Plugin) :
        DispatchingCommand(name, dispatcher, owner), PluginIdentifiableCommand {
        override fun getPlugin(): Plugin = owner
    }
    companion object {
        @JvmStatic fun discover(fallbackPrefix: String): CommandBridge = discover(fallbackPrefix, CommandRegistrationPolicy.REJECT)
        @JvmStatic fun discover(fallbackPrefix: String, policy: CommandRegistrationPolicy): CommandBridge =
            discover(JavaPlugin.getProvidingPlugin(BukkitCommandRegistrar::class.java), fallbackPrefix, policy)
        @JvmStatic fun discover(plugin: Plugin, fallbackPrefix: String): CommandBridge = discover(plugin, fallbackPrefix, CommandRegistrationPolicy.REJECT)
        @JvmStatic fun discover(plugin: Plugin, fallbackPrefix: String, policy: CommandRegistrationPolicy): CommandBridge {
            requirePrimaryThread()
            require(fallbackPrefix.trim().isNotEmpty()) { "fallbackPrefix must not be blank" }
            val server = java.util.Objects.requireNonNull(Bukkit.getServer(), "Bukkit server is not available")
            val map = PublicCommandReflection.invoke(server, "getCommandMap") as CommandMap
            val bridge = PaperLifecycleCommandBridge.discover(plugin, map, policy)
                ?: BukkitCommandRegistrar(map, knownCommands(map), fallbackPrefix, policy, plugin)
            return BrigadierBridge.discover(bridge, plugin, map)
        }
        @JvmStatic fun requirePrimaryThread() {
            check(Bukkit.getServer()?.isPrimaryThread != false) { "Bukkit 命令操作必须在服务器主线程执行" }
        }
        @JvmStatic fun removeByIdentity(commands: MutableMap<String, Command>, command: Command) {
            commands.keys.toList().forEach { if (commands[it] === command) commands.remove(it) }
        }
        @Suppress("UNCHECKED_CAST") private fun knownCommands(map: CommandMap): MutableMap<String, Command> {
            for (type in generateSequence(map.javaClass as Class<*>) { it.superclass }) {
                try { return type.getDeclaredField("knownCommands").apply { isAccessible = true }.get(map) as MutableMap<String, Command> }
                catch (ignored: NoSuchFieldException) { /* Older Bukkit has no public accessor. */ }
                catch (failure: IllegalAccessException) { throw IllegalStateException("Cannot access Bukkit command registry", failure) }
            }
            throw IllegalStateException("Cannot find Bukkit knownCommands")
        }
    }
}
