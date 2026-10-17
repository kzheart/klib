package me.kzheart.klib.command

import java.util.IdentityHashMap
import java.util.Optional
import me.kzheart.klib.command.api.CommandArgument
import me.kzheart.klib.command.api.CommandContext
import me.kzheart.klib.command.api.CommandErrorHandler
import org.bukkit.command.CommandSender

internal class CommandContextImpl(private val sender: CommandSender, private val label: String,
    values: Map<CommandArgument<*>, Any?>) : CommandContext {
    private val values = IdentityHashMap(values)
    private val byName = values.entries.associateTo(linkedMapOf()) { it.key.name() to it.value }
    @JvmField var errorHandler: CommandErrorHandler? = null
    override fun sender(): CommandSender = sender
    override fun label(): String = label
    @Suppress("UNCHECKED_CAST") override fun <T> get(argument: CommandArgument<T>): T {
        require(values.containsKey(argument)) {
            "argument was not parsed: ${argument.name()}；本次解析可用的参数名: ${byName.keys}；参数按对象身份读取，若使用 Arguments.optional(...) 等包装工厂，请用包装后的实例取值或改用 get(name, type) 按名取值"
        }
        return values[argument] as T
    }
    override fun find(name: String?): Optional<Any> = Optional.ofNullable(byName[name])
    override fun <T> get(name: String?, type: Class<T>): T {
        require(name != null && byName.containsKey(name)) { "argument was not parsed: $name；本次解析可用的参数名: ${byName.keys}" }
        return type.cast(byName[name])
    }
}
