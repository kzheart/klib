package me.kzheart.klib.script

import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** 公开位置参数语句适配层；原生动作树由各动作模块负责。 */
class BuiltInStatements private constructor() {
    enum class CommandMode {
        PLAYER, OPERATOR, CONSOLE;
        companion object {
            @JvmStatic fun of(token: String): CommandMode = when (token.lowercase(Locale.ROOT)) {
                "player" -> PLAYER
                "op", "operator" -> OPERATOR
                "console", "server" -> CONSOLE
                else -> throw IllegalArgumentException("Unknown command sender $token")
            }
        }
    }
    companion object {
        @JvmField val COMMAND = QuestActionParser(::command)
        @JvmStatic fun install(registry: StatementRegistry) {
            if (!registry.beginBuiltinInstall()) return
            fun add(name: String, parser: QuestActionParser) { registry.registerBuiltin("klib", name, parser) }
            add("literal") { call, context -> completed(call.text(0, context)) }
            StructuredScriptActions.install(registry); NativeValueActions.install(registry)
            NativeCheckActions.install(registry); NativeLogicalActions.install(registry)
            FlowScriptActions.install(registry); CollectionScriptActions.install(registry)
            TextScriptActions.install(registry); GameScriptActions.install(registry)
            add("get") { call, context -> require(call, 1, context, "name"); completed(context.variableOrNull(call.argument(0))) }
            add("unset") { call, context -> require(call, 1, context, "name"); completed(context.removeVariable(call.argument(0))) }
            add("eq", compare { it == 0 }); add("ne", compare { it != 0 })
            add("gt", compare { it > 0 }); add("gte", compare { it >= 0 })
            add("lt", compare { it < 0 }); add("lte", compare { it <= 0 })
            add("not") { call, context -> require(call, 1, context, "value"); completed(!InlineValues.truthy(call.value(0, context))) }
            add("and", logical(true)); add("or", logical(false))
            add("add", arithmetic { left, right -> left.add(right) })
            add("sub", arithmetic { left, right -> left.subtract(right) })
            add("mul", arithmetic { left, right -> left.multiply(right) })
            add("div", arithmetic { left, right -> left.divide(right, MathContext.DECIMAL128) })
            add("if", QuestActionParser(::conditional))
            add("namespace") { call, context -> require(call, 2, context, "block"); call.eval(call.argument(1), context.withNamespaces(call.argument(0))) }
            add("command", COMMAND)
            add("perm") { call, context ->
                require(call, 1, context, "permission")
                completed(context.requireService(PlayerQuery::class.java).hasPermission(context.sender().orElse(null), InlineValues.text(call.argument(0), context)))
            }
            add("type", QuestActionParser(::type))
            add("delay") { call, context -> require(call, 1, context, "duration"); context.requireService(DelayScheduler::class.java).delay(parseDuration(call.argument(0), context)) }
            add("list") { call, context -> completed(Collections.unmodifiableList(call.arguments().indices.map { call.value(it, context) })) }
            add("join") { call, context ->
                require(call, 2, context, "value")
                val delimiter = InlineValues.text(call.argument(0), context)
                completed((1 until call.arguments().size).joinToString(delimiter) { InlineValues.text(call.argument(it), context) })
            }
        }
        private fun compare(matches: (Int) -> Boolean): QuestActionParser = QuestActionParser { call, context ->
            require(call, 2, context, "right"); completed(matches(compareValues(call.value(0, context), call.value(1, context))))
        }
        private fun logical(and: Boolean): QuestActionParser = QuestActionParser { call, context ->
            require(call, 1, context, "value")
            var result = and
            for (index in call.arguments().indices) {
                val value = InlineValues.truthy(call.value(index, context))
                result = if (and) result && value else result || value
            }
            completed(result)
        }
        private fun arithmetic(operation: (BigDecimal, BigDecimal) -> BigDecimal): QuestActionParser = QuestActionParser { call, context ->
            require(call, 2, context, "right")
            var result = number(call.value(0, context), context)
            for (index in 1 until call.arguments().size) result = operation(result, number(call.value(index, context), context))
            completed(result.stripTrailingZeros())
        }
        private fun conditional(call: StatementCall, context: ScriptContext): CompletionStage<Any?> {
            require(call, 3, context, "then-block")
            val thenIndex = if (call.argument(1).equals("then", true)) 2 else 1
            val thenBlock = call.argument(thenIndex)
            val elseIndex = thenIndex + 1
            return call.eval(call.argument(0), context).thenCompose { value -> when {
                InlineValues.truthy(value) -> call.eval(thenBlock, context)
                elseIndex + 1 < call.arguments().size && call.argument(elseIndex).equals("else", true) -> call.eval(call.argument(elseIndex + 1), context)
                else -> completed(null)
            } }
        }
        private fun command(call: StatementCall, context: ScriptContext): CompletionStage<Any?> {
            require(call, 1, context, "command")
            val commandIndex = if (call.argument(0).equals("inline", true)) 1 else 0
            require(call, commandIndex + 1, context, "command")
            val asIndex = (commandIndex + 1 until call.arguments().size).firstOrNull { call.argument(it).equals("as", true) } ?: -1
            val commandEnd = if (asIndex < 0) call.arguments().size else asIndex
            val command = buildString {
                for (index in commandIndex until commandEnd) {
                    if (isNotEmpty()) append(' ')
                    append(InlineValues.text(call.argument(index), context))
                }
            }
            val console = asIndex >= 0 && asIndex + 1 < call.arguments().size && call.argument(asIndex + 1).equals("console", true)
            return completed(dispatchCommand(context, command, console))
        }
        @JvmStatic fun dispatchCommand(context: ScriptContext, command: String, console: Boolean): Any? =
            context.requireService(CommandSink::class.java).dispatch(if (console) null else context.sender().orElse(null), command)
        @JvmStatic fun dispatchCommand(context: ScriptContext, command: String, mode: CommandMode): Any? {
            val sink = context.requireService(CommandSink::class.java)
            var text = NativeCheckActions.trimIndent(command)
            if (mode == CommandMode.CONSOLE) return sink.dispatch(null, text.replace("@sender", "console"))
            val sender = context.sender().orElse(null)
            if (text.contains("@sender")) text = text.replace("@sender", if (sender == null) "console" else context.requireService(ScriptSenderQuery::class.java).name(sender).toString())
            return if (mode == CommandMode.OPERATOR) sink.dispatchAsOperator(sender, text) else sink.dispatch(sender, text)
        }
        private fun type(call: StatementCall, context: ScriptContext): CompletionStage<Any?> {
            require(call, 2, context, "value")
            val type = call.argument(0).lowercase(Locale.ROOT)
            val value = InlineValues.text(call.argument(1), context)
            return completed(when (type) {
                "boolean", "bool" -> { require(value.equals("true", true) || value.equals("false", true)) { "Invalid boolean: $value" }; value.toBoolean() }
                "int", "integer" -> value.toInt()
                "long" -> value.toLong()
                "double" -> value.toDouble()
                "number", "decimal" -> BigDecimal(value)
                "string" -> value
                else -> throw IllegalArgumentException("Unknown type: $type")
            })
        }
        private fun require(call: StatementCall, count: Int, context: ScriptContext, argument: String) {
            require(call.arguments().size >= count) { ScriptMessages.text(context.locale(), "missing-argument", argument) }
        }
        private fun compareValues(left: Any?, right: Any?): Int = when {
            left == null || right == null -> if (left == right) 0 else if (left == null) -1 else 1
            left is Number && right is Number -> BigDecimal(left.toString()).compareTo(BigDecimal(right.toString()))
            else -> left.toString().compareTo(right.toString())
        }
        private fun number(value: Any?, context: ScriptContext): BigDecimal = try { if (value is BigDecimal) value else BigDecimal(value.toString()) }
            catch (failure: NumberFormatException) { throw IllegalArgumentException(ScriptMessages.text(context.locale(), "invalid-number", value.toString()), failure) }
        private fun parseDuration(value: String, context: ScriptContext): Duration {
            try {
                val normalized = value.lowercase(Locale.ROOT)
                return when {
                    normalized.endsWith("ms") -> Duration.ofMillis(normalized.dropLast(2).toLong())
                    normalized.endsWith("s") -> Duration.ofMillis(BigDecimal(normalized.dropLast(1)).multiply(BigDecimal.valueOf(1000L)).longValueExact())
                    normalized.endsWith("t") -> Duration.ofMillis(normalized.dropLast(1).toLong() * 50L)
                    else -> Duration.ofMillis(normalized.toLong())
                }
            } catch (failure: ArithmeticException) { throw IllegalArgumentException(ScriptMessages.text(context.locale(), "invalid-number", value), failure) }
            catch (failure: NumberFormatException) { throw IllegalArgumentException(ScriptMessages.text(context.locale(), "invalid-number", value), failure) }
        }
        private fun completed(value: Any?): CompletionStage<Any?> = CompletableFuture.completedFuture(value)
    }
}
