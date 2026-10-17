package me.kzheart.klib.command

import java.util.function.Predicate
import java.util.function.Supplier
import me.kzheart.klib.command.api.CommandHandler
import me.kzheart.klib.command.api.CommandErrorHandler
import me.kzheart.klib.command.api.CommandHelpStyle
import org.bukkit.command.CommandSender

/** Mutable only while compiling a command tree; dispatcher instances consume the compiled tree. */
internal class CommandNode(@JvmField val literal: String?, @JvmField val argument: Arg<*>?) {
    @JvmField val children: MutableList<CommandNode> = arrayListOf()
    @JvmField var description = ""
    @JvmField var descriptionKey: String? = null
    @JvmField var permission: String? = null
    @JvmField var playerOnly = false
    @JvmField var handlerPlayerOnly = false
    @JvmField var suggestionOwner: Any? = null
    @JvmField var hasGreedyChild = false
    @JvmField var handler: CommandHandler? = null
    @JvmField var errorHandler: CommandErrorHandler? = null
    @JvmField var parent: CommandNode? = null
    @JvmField var helpStyle: Supplier<CommandHelpStyle>? = null
    @JvmField var branchAccess: Predicate<CommandSender>? = null
    @JvmField var handlerAccess: Predicate<CommandSender>? = null
    fun usageToken(): String = literal ?: requireNotNull(argument).usage()
}
