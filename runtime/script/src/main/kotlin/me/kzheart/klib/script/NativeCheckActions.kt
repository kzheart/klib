/* Kether: Copyright (c) 2018 Bkm016. Coerce: Copyright SpongePowered and contributors.
 * MIT License. See META-INF/LICENSE-TabooLib-Kether.txt
 * and META-INF/LICENSE-Sponge-Coerce.txt. */
package me.kzheart.klib.script

import java.util.Objects
import me.kzheart.klib.script.KetherSupport.Companion.action
import me.kzheart.klib.script.KetherSupport.Companion.completed
import me.kzheart.klib.script.KetherSupport.Companion.follow
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestReader
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern

/** CheckType 动作和只读宿主查询边界。 */
class NativeCheckActions private constructor() {
    companion object {
        private val BRACKETS = Pattern.compile("^([(\\[{]?)(.+?)([)\\]}]?)$")
        private val FLOAT = Pattern.compile("[+-]?(?:NaN|Infinity|(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFdD]?|0[xX](?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)[pP][+-]?[0-9]+[fFdD]?))")
        @JvmField val PLAYER_OPERATORS: List<String> = Collections.unmodifiableList(listOf(
            "locale", "world", "x", "y", "z", "yaw", "pitch", "block x", "block y", "block z",
            "compass x", "compass y", "compass z", "location", "compass target", "bed spawn",
            "bed spawn x", "bed spawn y", "bed spawn z", "name", "list name", "display name", "uuid",
            "gamemode", "address", "sneaking", "sprinting", "blocking", "gliding", "glowing", "swimming",
            "riptiding", "sleeping", "sleep ticks", "sleep ignored", "dead", "conversing", "leashed",
            "on ground", "is online", "inside vehicle", "op", "gravity", "attack cooldown", "player time",
            "first played", "last played", "absorption amount", "no damage ticks", "remaining air",
            "maximum air", "exp", "level", "exhaustion", "saturation", "food level", "health",
            "max health", "allow flight", "flying", "fly speed", "walk speed", "ping", "pose", "facing"        ))
        @JvmStatic fun install(registry: StatementRegistry) {
            registry.registerBuiltinKether("true", constant("true"))
            registry.registerBuiltinKether("false", constant("false"))
            registry.registerBuiltinKether("null", constant(null))
            registry.registerBuiltinKether("check", NativeParser.of { reader ->
                val left = requiredValue(reader)
                val symbol = requiredToken(reader)
                val right = requiredValue(reader)
                action { frame -> follow(frame, completed(null)) {
                    follow(frame, frame.newFrame(left).run<Any?>()) { first ->
                        follow(frame, frame.newFrame(right).run<Any?>()) { second -> completed(Operator.of(symbol).check(first, second)) }
                    }
                } }
            })
            registry.registerBuiltinKether("player", NativeParser.of { reader -> playerAction(reader) })
            val placeholder = NativeParser.of { reader ->
                val input = requiredValue(reader)
                action { frame ->
                    val result = follow(frame, frame.newFrame(input).run<Any?>()) { value ->
                        completed(placeholder(frame, if (value == null) "" else trimIndent(value.toString())))
                    }
                    follow(frame, result.handle { value, failure -> Outcome(value, failure) }) { outcome ->
                        if (outcome.failure == null) completed(outcome.value)
                        else { outcome.failure.printStackTrace(); completed(placeholder(frame, "")) }
                    }
                }
            }
            registry.registerBuiltinKether("papi", placeholder)
            registry.registerBuiltinKether("placeholder", placeholder)
        }
        private fun playerAction(reader: QuestReader): QuestAction<Any?> {
            val property = playerOperator(reader)
            val mark = reader.index
            val method = if (reader.hasNext() && reader.peek() != '}' && reader.peek() != ']') when (reader.nextToken()) {
                "to" -> PlayerQuery.Method.MODIFY
                "add", "increase", "+" -> PlayerQuery.Method.INCREASE
                "sub", "decrease", "-" -> PlayerQuery.Method.DECREASE
                else -> { reader.index = mark; null }
            } else null
            if (method != null) {
                val value = requiredValue(reader)
                return action { frame -> follow(frame, frame.newFrame(value).run<Any?>()) { result ->
                    val context = CoreScriptRuntime.context(frame)
                    require(context.requireService(PlayerQuery::class.java).write(player(context), property, method, result)) { "Player \"$property\" is not writable." }
                    completed(null)
                } }
            }
            return action { frame ->
                val context = CoreScriptRuntime.context(frame)
                val value = context.requireService(PlayerQuery::class.java).property(player(context), property)
                    ?: throw IllegalArgumentException("Player \"$property\" is not readable.")
                completed(value)
            }
        }
        private fun playerOperator(reader: QuestReader): String {
            requireInput(reader)
            val start = reader.index
            var best: String? = null
            var bestWords = 0
            var bestEnd = start
            for (operator in PLAYER_OPERATORS) {
                val words = operator.split(' ')
                if (words.size <= bestWords) continue
                reader.index = start
                if (words.all { reader.hasNext() && reader.peek() != '}' && reader.peek() != ']' && it == reader.nextToken() }) {
                    best = operator; bestEnd = reader.index; bestWords = words.size
                }
            }
            if (best != null) { reader.index = bestEnd; return best }
            reader.index = start
            return reader.nextToken()
        }
        private fun player(context: ScriptContext): Any {
            val sender = context.sender().orElse(null)
            check(sender != null && context.requireService(ScriptSenderQuery::class.java).isPlayer(sender)) { "No player selected." }
            return sender
        }
        private fun placeholder(frame: QuestContext.Frame, text: String): Any? {
            val context = CoreScriptRuntime.context(frame)
            return context.requireService(PlaceholderResolver::class.java).resolve(player(context), text)
        }
        @JvmStatic fun trimIndent(text: String): String = text.trimIndent()
        private fun requiredValue(reader: QuestReader): ParsedAction<*> { requireInput(reader); return reader.nextValue<Any?>() }
        private fun requiredToken(reader: QuestReader): String { requireInput(reader); return reader.nextToken() }
        private fun requireInput(reader: QuestReader) {
            require(reader.hasNext() && reader.peek() != '}' && reader.peek() != ']') { "Expected a check/query argument before end of input or closing delimiter" }
        }
        private fun constant(value: Any?): NativeParser = NativeParser.of { action { completed(value) } }
        private fun infer(value: Any?): Any? {
            if (value !is String) return value
            value.toIntOrNull()?.let { return it }; value.toLongOrNull()?.let { return it }; value.toDoubleOrNull()?.let { return it }
            return when (value) { "true" -> true; "false" -> false; else -> value }
        }
        private fun number(value: Any?): Double {
            if (value == null) return 0.0
            if (value is Number) return value.toDouble()
            val text = sanitized(value)
            return if (FLOAT.matcher(text).matches()) text.toDoubleOrNull() ?: 0.0 else 0.0
        }
        private fun sanitized(value: Any): String {
            var text = value.toString().trim { it <= ' ' }
            if (text.isEmpty()) return "0"
            val match = BRACKETS.matcher(text)
            if (match.matches() && "([{".indexOf(match.group(1)) == ")]}".indexOf(match.group(3))) text = match.group(2).trim { it <= ' ' }
            val decimal = text.indexOf('.')
            val comma = text.indexOf(',', decimal)
            if (decimal > -1 && comma > -1) return sanitized(text.substring(0, comma))
            if (text.indexOf('-', 1) != -1) return "0"
            return text.replace(",", "").split(' ')[0]
        }
        private fun contains(container: Any?, value: Any?): Boolean = when (container) {
            is Collection<*> -> container.contains(value)
            is Array<*> -> container.asList().contains(value)
            is Map<*, *> -> container.containsKey(value)
            else -> container.toString().contains(value.toString())
        }
    }
    private class Outcome(val value: Any?, val failure: Throwable?)
    private enum class Operator {
        EQUAL, NOT_EQUAL, STRICT_EQUAL, MEMORY_EQUAL, IGNORE_CASE, GT, GTE, LT, LTE, CONTAINS, IN;
        fun check(left: Any?, right: Any?): Boolean = when (this) {
            EQUAL -> {
                val first = infer(left); val second = infer(right)
                if (first is Number && second is Number) first.toDouble() == second.toDouble() else Objects.equals(first, second)
            }
            NOT_EQUAL -> !EQUAL.check(left, right)
            STRICT_EQUAL -> Objects.equals(left, right)
            MEMORY_EQUAL -> left === right
            IGNORE_CASE -> left.toString().equals(right.toString(), true)
            GT -> number(left) > number(right)
            GTE -> number(left) >= number(right)
            LT -> number(left) < number(right)
            LTE -> number(left) <= number(right)
            CONTAINS -> contains(left, right)
            IN -> contains(right, left)
        }
        companion object {
            fun of(value: String): Operator = when (value) {
                "=", "==", "is" -> EQUAL
                "!=", "!is", "not" -> NOT_EQUAL
                "=!", "is!" -> STRICT_EQUAL
                "=!!", "is!!" -> MEMORY_EQUAL
                "=?", "is?" -> IGNORE_CASE
                ">", "gt" -> GT
                ">=", "gte" -> GTE
                "<", "lt" -> LT
                "<=", "lte" -> LTE
                "contains", "has" -> CONTAINS
                "in" -> IN
                else -> throw IllegalArgumentException("Unknown check operator: $value")
            }
        }
    }
}
