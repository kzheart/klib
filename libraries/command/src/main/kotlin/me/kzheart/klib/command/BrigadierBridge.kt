package me.kzheart.klib.command

import java.lang.reflect.Proxy
import java.util.Locale
import java.util.logging.Level
import me.kzheart.klib.command.api.CommandSpec
import me.kzheart.klib.scope.Disposable
import org.bukkit.Bukkit
import org.bukkit.command.CommandMap
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin

/** Projects only the player's copied client tree, leaving the server execution tree untouched. */
class BrigadierBridge internal constructor(private val fallback: CommandBridge, private val registry: Registry) : CommandBridge {
    internal fun interface Registry { fun register(name: String, tree: BrigadierTree): Disposable }
    override fun register(name: String, spec: CommandSpec, dispatcher: CommandDispatcher): Disposable {
        val projection = registry.register(name, BrigadierTree.from(spec))
        val raw = try { fallback.register(name, spec, dispatcher) }
            catch (failure: RuntimeException) { projection.dispose(); throw failure }
            catch (failure: Error) { projection.dispose(); throw failure }
        return Disposable { raw.dispose(); projection.dispose() }
    }
    internal class PaperRegistry(private val plugin: Plugin, private val commands: CommandMap) : Registry, EventExecutor {
        private val trees = hashMapOf<String, Projection>()
        private val executionMarker: Any = try {
            val command = PublicCommandReflection.type("com.mojang.brigadier.Command")
            Proxy.newProxyInstance(command.classLoader, arrayOf(command)) { proxy, method, args ->
                when (method.name) { "run" -> 1; "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === args[0]; else -> "Klib client command marker" }
            }
        } catch (failure: ClassNotFoundException) { throw IllegalStateException("Missing Brigadier API", failure) }
        override fun register(name: String, tree: BrigadierTree): Disposable {
            BukkitCommandRegistrar.requirePrimaryThread()
            val label = name.lowercase(Locale.ROOT)
            check(!trees.containsKey(label)) { "Command already declared: $label" }
            val projection = Projection(tree)
            trees[label] = projection
            return Disposable { projection.active = false }
        }
        override fun execute(ignored: Listener?, event: Event) {
            if (event.isAsynchronous) return
            BukkitCommandRegistrar.requirePrimaryThread()
            try { project(PublicCommandReflection.invoke(event, "getPlayer") as Player,
                requireNotNull(PublicCommandReflection.invoke(event, "getCommandNode"))) }
            catch (failure: RuntimeException) { plugin.logger.log(Level.WARNING, "Unable to project klib client command tree", failure) }
        }
        fun project(player: Player, root: Any) {
            BukkitCommandRegistrar.requirePrimaryThread()
            val children = PublicCommandReflection.invoke(root, "getChildren") as Collection<*>
            for (original in children.toList()) {
                requireNotNull(original)
                val label = PublicCommandReflection.invoke(original, "getName") as String
                val normalized = label.lowercase(Locale.ROOT)
                val prefix = "${plugin.name.lowercase(Locale.ROOT)}:"
                val projection = trees[normalized] ?: if (normalized.startsWith(prefix)) trees[normalized.substring(prefix.length)] else null
                if (projection == null || !owned(label)) continue
                if (!projection.active || !plugin.isEnabled || !projection.tree.accessible(player)) {
                    PublicCommandReflection.invoke(root, "removeCommand", label)
                    continue
                }
                val builder = requireNotNull(PublicCommandReflection.invokeStatic(LITERAL, "literal", label))
                configure(builder, projection.tree, player, suggestions(original))
                val replacement = PublicCommandReflection.invoke(builder, "build")
                PublicCommandReflection.invoke(root, "removeCommand", label)
                PublicCommandReflection.invoke(root, "addChild", replacement)
            }
        }
        private fun owned(label: String): Boolean = (commands.getCommand(label) as? PluginIdentifiableCommand)?.plugin === plugin
        private fun suggestions(root: Any): Any? {
            val raw = PublicCommandReflection.invoke(root, "getRedirect") ?: root
            val args = PublicCommandReflection.invoke(raw, "getChild", "args") ?: return null
            return PublicCommandReflection.invoke(args, "getCustomSuggestions")
        }
        private fun configure(builder: Any, tree: BrigadierTree, player: Player, suggestions: Any?) {
            PublicCommandReflection.invoke(builder, "executes", executionMarker)
            for (child in tree.children()) {
                if (!child.accessible(player)) continue
                val next = if (child.kind() == BrigadierTree.Kind.LITERAL) PublicCommandReflection.invokeStatic(LITERAL, "literal", child.token())
                else {
                    val argument = PublicCommandReflection.invokeStatic(STRING, if (child.greedy()) "greedyString" else "word")
                    requireNotNull(PublicCommandReflection.invokeStatic(ARGUMENT, "argument", child.token(), argument)).also {
                        if (suggestions != null) PublicCommandReflection.invoke(it, "suggests", suggestions)
                    }
                }
                configure(requireNotNull(next), child, player, suggestions)
                PublicCommandReflection.invoke(builder, "then", next)
            }
        }
        private class Projection(val tree: BrigadierTree) { @Volatile var active = true }
        companion object {
            private const val EVENT = "com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent"
            private const val LITERAL = "com.mojang.brigadier.builder.LiteralArgumentBuilder"
            private const val ARGUMENT = "com.mojang.brigadier.builder.RequiredArgumentBuilder"
            private const val STRING = "com.mojang.brigadier.arguments.StringArgumentType"
            @JvmStatic fun discover(plugin: Plugin, map: CommandMap): Registry? = try {
                val candidate = PublicCommandReflection.type(EVENT)
                PublicCommandReflection.type(LITERAL)
                PublicCommandReflection.type("com.mojang.brigadier.tree.CommandNode").getMethod("removeCommand", String::class.java)
                if (!Event::class.java.isAssignableFrom(candidate)) null else {
                    val registry = PaperRegistry(plugin, map)
                    Bukkit.getPluginManager().registerEvent(candidate.asSubclass(Event::class.java), object : Listener {}, EventPriority.NORMAL, registry, plugin, true)
                    registry
                }
            } catch (missing: ClassNotFoundException) { null } catch (missing: NoSuchMethodException) { null }
        }
    }
    companion object {
        @JvmStatic fun discover(fallback: CommandBridge, plugin: Plugin, map: CommandMap): CommandBridge =
            PaperRegistry.discover(plugin, map)?.let { BrigadierBridge(fallback, it) } ?: fallback
    }
}
