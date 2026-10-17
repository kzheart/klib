package me.kzheart.klib.command

import java.util.Collections
import java.util.function.Predicate
import me.kzheart.klib.command.api.CommandSpec
import org.bukkit.command.CommandSender

internal class BrigadierTree private constructor(private val kind: Kind, private val token: String, private val permission: String?,
    private val playerOnly: Boolean, private val greedy: Boolean, private val handler: Boolean,
    children: List<BrigadierTree>, private val access: Predicate<CommandSender>) {
    enum class Kind { ROOT, LITERAL, ARGUMENT }
    private val children = Collections.unmodifiableList(children)
    fun accessible(sender: CommandSender): Boolean = access.test(sender)
    fun kind(): Kind = kind
    fun token(): String = token
    fun permission(): String? = permission
    fun playerOnly(): Boolean = playerOnly
    fun greedy(): Boolean = greedy
    fun handler(): Boolean = handler
    fun children(): List<BrigadierTree> = children
    companion object {
        @JvmStatic fun from(spec: CommandSpec): BrigadierTree {
            val typed = spec as? CommandSpecImpl ?: throw IllegalArgumentException("command spec must be created by klib")
            return copy(Kind.ROOT, typed.name(), typed.root())
        }
        private fun copy(rootKind: Kind, rootToken: String, node: CommandNode): BrigadierTree = BrigadierTree(
            when { node.literal != null -> Kind.LITERAL; node.argument != null -> Kind.ARGUMENT; else -> rootKind },
            node.literal ?: node.argument?.name() ?: rootToken, node.permission, node.playerOnly, node.argument?.isGreedy ?: false,
            node.handler != null, node.children.map { copy(Kind.ROOT, "", it) }, Predicate { CommandDispatcher.isAccessible(it, node) })
    }
}
