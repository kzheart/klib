package me.kzheart.klib.command

import me.kzheart.klib.command.api.CommandArgument
import me.kzheart.klib.command.api.CommandContext
import me.kzheart.klib.command.api.CommandRegistration
import me.kzheart.klib.command.api.CommandSpec
import me.kzheart.klib.command.api.Commands
import me.kzheart.klib.core.kotlin.KlibDsl

/** Kotlin declarations compile into the same command tree used by Java. */
@KlibDsl
class CommandDsl internal constructor(private val spec: CommandSpec) {
    fun description(value: String) { spec.description(value) }
    fun permission(value: String) { spec.permission(value) }
    fun playerOnly() { spec.playerOnly() }
    fun executes(action: CommandContext.() -> Unit) { spec.executes { it.action() } }
    fun literal(name: String, block: CommandDsl.() -> Unit) {
        spec.literal(name) { CommandDsl(it).block() }
    }
    fun <T> argument(argument: CommandArgument<T>, block: ArgumentDsl<T>.() -> Unit) {
        spec.argument(argument) { ArgumentDsl(it, argument).block() }
    }
}

/** The argument identity is retained: no string lookup or unchecked cast. */
@KlibDsl
class ArgumentDsl<T> internal constructor(
    private val spec: CommandSpec,
    val argument: CommandArgument<T>
) {
    fun executes(action: CommandContext.(T) -> Unit) {
        spec.executes { context -> context.action(context.get(argument)) }
    }
    fun literal(name: String, block: CommandDsl.() -> Unit) {
        spec.literal(name) { CommandDsl(it).block() }
    }
    fun <U> argument(argument: CommandArgument<U>, block: ArgumentDsl<U>.() -> Unit) {
        spec.argument(argument) { ArgumentDsl(it, argument).block() }
    }
}

fun Commands.command(name: String, block: CommandDsl.() -> Unit): CommandRegistration =
    register(name) { CommandDsl(it).block() }
