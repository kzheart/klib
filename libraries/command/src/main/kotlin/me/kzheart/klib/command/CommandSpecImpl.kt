package me.kzheart.klib.command

import java.util.Locale
import java.util.function.Consumer
import java.util.function.Supplier
import me.kzheart.klib.command.api.CommandArgument
import me.kzheart.klib.command.api.CommandErrorHandler
import me.kzheart.klib.command.api.CommandHandler
import me.kzheart.klib.command.api.CommandHelpStyle
import me.kzheart.klib.command.api.CommandSpec

class CommandSpecImpl private constructor(private val name: String, private val node: CommandNode) : CommandSpec {
    override fun route(path: String?): CommandSpec {
        require(!path.isNullOrBlank()) { "route must not be blank" }
        return path.trim().split(Regex("\\s+")).fold(this) { current, word ->
            val literal = requireSingleWord(word, "literal")
            val existing = current.node.children.lastOrNull { it.literal == literal }
            if (existing != null) CommandSpecImpl(name, existing)
            else {
                current.requireCanAddChild()
                current.addNode(CommandNode(literal, null))
            }
        }
    }
    override fun <T> argument(argument: CommandArgument<T>): CommandSpec = addArgument(argument)
    fun name(): String = name
    override fun description(description: String?): CommandSpec = apply { node.description = description?.trim() ?: "" }
    override fun permission(permission: String?): CommandSpec = apply {
        require(!permission.isNullOrBlank()) { "permission must not be blank" }
        node.permission = permission.trim()
    }
    override fun playerOnly(): CommandSpec = apply { node.playerOnly = true }
    override fun executes(handler: CommandHandler): CommandSpec = apply { node.handler = handler }
    override fun errorHandler(handler: CommandErrorHandler): CommandSpec = apply { node.errorHandler = handler }
    override fun helpStyle(style: CommandHelpStyle): CommandSpec = helpStyle(Supplier { style })
    override fun helpStyle(style: Supplier<CommandHelpStyle>): CommandSpec = apply { node.helpStyle = style }
    override fun literal(literal: String, configure: Consumer<in CommandSpec>): CommandSpec = apply {
        requireCanAddChild()
        val normalized = requireSingleWord(literal, "literal")
        require(node.children.none { it.literal == normalized }) { "duplicate literal: $normalized" }
        configure.accept(addNode(CommandNode(normalized, null)))
    }
    override fun <T> argument(argument: CommandArgument<T>, configure: Consumer<in CommandSpec>): CommandSpec = apply {
        configure.accept(addArgument(argument))
    }
    private fun <T> addArgument(argument: CommandArgument<T>): CommandSpecImpl {
        requireCanAddChild()
        val typed = argument as? Arg<*> ?: throw IllegalArgumentException(
            "命令参数必须由 Arguments 工厂创建；自定义解析请使用 Arguments.custom(name, parser, suggester)")
        for (sibling in node.children.mapNotNull { it.argument }) {
            require(sibling.name() != typed.name()) { "duplicate argument: ${typed.name()}" }
            check(!sibling.isCatchAll) { "argument <${typed.name()}> is unreachable after catch-all argument <${sibling.name()}>" }
        }
        return addNode(CommandNode(null, typed)).also { if (typed.isGreedy) node.hasGreedyChild = true }
    }
    private fun addNode(child: CommandNode): CommandSpecImpl {
        child.parent = node
        node.children.add(child)
        return CommandSpecImpl(name, child)
    }
    fun withName(label: String): CommandSpecImpl = CommandSpecImpl(requireSingleWord(label, "command name"), node)
    @JvmName("root") internal fun root(): CommandNode = node
    fun descriptionKey(key: String?) { node.descriptionKey = key }
    private fun requireCanAddChild() {
        check(node.argument?.isGreedy != true) { "greedy argument must be the final node" }
        check(!node.hasGreedyChild) { "no child may follow a greedy argument" }
    }
    companion object {
        @JvmStatic fun command(name: String): CommandSpecImpl = CommandSpecImpl(requireSingleWord(name, "command name"), CommandNode(null, null))
        @JvmStatic fun requireSingleWord(value: String, what: String): String = value.trim().lowercase(Locale.ROOT).also {
            require(it.isNotEmpty() && ' ' !in it) { "$what must be one non-blank word" }
        }
    }
}
