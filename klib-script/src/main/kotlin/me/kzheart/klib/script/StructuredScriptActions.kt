/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import java.util.Objects
import me.kzheart.klib.script.KetherSupport.Companion.action
import me.kzheart.klib.script.KetherSupport.Companion.completed
import me.kzheart.klib.script.KetherSupport.Companion.follow
import me.kzheart.klib.script.KetherSupport.Companion.text
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestReader
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.MapContext
import java.lang.reflect.Array as ReflectArray
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern

/** 结构化参数保留动作树；所有异步恢复复用同一续接边界。 */
class StructuredScriptActions private constructor() {
    companion object {
        private val JEXL = JexlBuilder().cache(256).create()
        private val INLINE = Pattern.compile("\\{\\{(.*?)}}", Pattern.DOTALL)
        @JvmStatic fun install(registry: StatementRegistry) {
            val tell = unary { frame, value ->
                val context = CoreScriptRuntime.context(frame)
                var message = InlineValues.interpolate(text(value), context)
                val sender = context.sender().orElse(null)
                if (message.contains("@sender")) message = message.replace("@sender",
                    if (sender == null) "null" else context.requireService(ScriptSenderQuery::class.java).name(sender).toString())
                context.requireService(MessageSink::class.java).send(sender, message)
                completed(message)
            }
            register(registry, tell, "tell", "send", "message")
            register(registry, unary { _, value -> completed(color(text(value))) }, "colored", "color")
            register(registry, unary { frame, value -> interpolate(frame, text(value)) }, "inline", "function")
            register(registry, calculation(false), "calc", "calculate")
            registry.registerBuiltinKether("invoke", calculation(true))
            registry.registerBuiltinKether("case", NativeParser.of { caseAction(it, false, emptyList()) })
        }
        private fun register(registry: StatementRegistry, parser: NativeParser, vararg names: String) {
            names.forEach { registry.registerBuiltinKether(it, parser) }
        }
        private fun unary(operation: (QuestContext.Frame, Any?) -> CompletableFuture<Any?>): NativeParser = NativeParser.of { reader ->
            val input = reader.nextValue<Any?>()
            action { frame -> follow(frame, frame.newFrame(input).run<Any?>()) { value -> operation(frame, value) } }
        }
        private fun calculation(script: Boolean): NativeParser = NativeParser.of { reader ->
            if (consume(reader, "dynamic")) {
                val expression = reader.nextValue<Any?>()
                action { frame -> follow(frame, frame.newFrame(expression).run<Any?>()) { value -> completed(evaluate(text(value), script, frame)) } }
            } else {
                val source = reader.nextToken()
                if (script) {
                    val compiled = JEXL.createScript(source)
                    action { frame -> completed(compiled.execute(MapContext(variables(frame)))) }
                } else {
                    val compiled = JEXL.createExpression(source)
                    action { frame -> completed(compiled.evaluate(MapContext(variables(frame)))) }
                }
            }
        }
        private fun evaluate(source: String, script: Boolean, frame: QuestContext.Frame): Any? {
            val context = MapContext(variables(frame))
            return if (script) JEXL.createScript(source).execute(context) else JEXL.createExpression(source).evaluate(context)
        }
        private fun variables(frame: QuestContext.Frame): Map<String, Any?> =
            LinkedHashMap(CoreScriptRuntime.context(frame).variables()).apply { putAll(ScriptFrames.variables(frame)) }

        @JvmStatic fun caseAction(reader: QuestReader, legacy: Boolean, names: List<String>): QuestAction<Any?> {
            val input = reader.nextValue<Any?>()
            reader.expect("[")
            val branches = ArrayList<Branch>()
            while (!consume(reader, "]")) {
                when (val token = reader.nextToken()) {
                    "else" -> {
                        if (legacy) consume(reader, "->")
                        branches.add(Branch(null, emptyList(), caseValue(reader, legacy, names)))
                    }
                    "when" -> {
                        val index = reader.index
                        val operator = Operator.parse(reader.nextToken()) ?: Operator.EQUAL.also { reader.index = index }
                        val conditions = ArrayList<ParsedAction<*>>()
                        if (consume(reader, "[")) while (!consume(reader, "]")) conditions.add(reader.nextValue<Any?>())
                        else conditions.add(reader.nextValue<Any?>())
                        val arrow = reader.nextToken()
                        require(arrow == "->" || arrow == "then") { "Expected then or ->, got $arrow" }
                        branches.add(Branch(operator, conditions, caseValue(reader, legacy, names)))
                    }
                    else -> throw IllegalArgumentException("Expected when or else, got $token")
                }
            }
            return action { frame -> follow(frame, frame.newFrame(input).run<Any?>()) { value ->
                var selected = CompletableFuture.completedFuture<Branch?>(null)
                for (branch in branches) selected = follow(frame, selected) { previous ->
                    if (previous != null) CompletableFuture.completedFuture(previous)
                    else follow(frame, branch.matches(frame, value)) { matches -> CompletableFuture.completedFuture(if (matches) branch else null) }
                }
                follow(frame, selected) { branch -> if (branch == null) completed(null) else frame.newFrame(branch.action).run() }
            } }
        }
        private fun caseValue(reader: QuestReader, legacy: Boolean, names: List<String>): ParsedAction<*> {
            val start = reader.index
            val parsed = reader.nextValue<Any?>()
            if (!legacy) return parsed
            val first = reader.source(start, reader.index).trim { it <= ' ' }
            if (!caseLabel(first, names)) return parsed
            val label = StringBuilder(first)
            var joined = false
            while (reader.hasNext() && !reader.hasLineBreakBeforeNextToken()) {
                val index = reader.index
                val next = reader.nextToken()
                if (!caseLabel(next, names)) { reader.index = index; break }
                label.append(' ').append(next); joined = true
            }
            return if (joined) ParsedAction(action { completed(label.toString()) }) else parsed
        }
        private fun caseLabel(value: String, names: List<String>): Boolean = value.matches(Regex("[A-Z][A-Z0-9_-]*")) &&
            names.none { it.substringAfterLast(':').equals(value, true) }
        private fun interpolate(frame: QuestContext.Frame, source: String): CompletableFuture<Any?> {
            val matcher = INLINE.matcher(source)
            var output = CompletableFuture.completedFuture(StringBuilder())
            var end = 0
            while (matcher.find()) {
                val prefix = source.substring(end, matcher.start())
                val expression = matcher.group(1).trim { it <= ' ' }
                output = follow(frame, output) { buffer ->
                    buffer.append(prefix)
                    val value = if (expression.matches(Regex("[A-Za-z0-9_.-]+"))) completed(CoreScriptRuntime.context(frame).variableOrNull(expression))
                        else CoreScriptRuntime.evalNested(frame, expression).toCompletableFuture()
                    follow(frame, value) { result -> CompletableFuture.completedFuture(buffer.append(text(result))) }
                }
                end = matcher.end()
            }
            val tail = source.substring(end)
            return follow(frame, output) { result -> completed(InlineValues.interpolate(result.append(tail).toString(), CoreScriptRuntime.context(frame))) }
        }
        @JvmStatic fun consume(reader: QuestReader, token: String): Boolean = KetherSupport.consume(reader, token)
        @JvmStatic fun color(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                val current = value[index++]
                if (current == '&' && index < value.length) {
                    val code = Character.toLowerCase(value[index])
                    if (code == '#' && index + 6 < value.length && value.substring(index + 1, index + 7).matches(Regex("[0-9a-fA-F]{6}"))) {
                        append('\u00a7').append('x')
                        for (digit in index + 1 until index + 7) append('\u00a7').append(Character.toLowerCase(value[digit]))
                        index += 7; continue
                    }
                    if (code in "0123456789abcdefklmnorx") { append('\u00a7').append(code); index++; continue }
                }
                append(current)
            }
        }
    }
    private class Branch(private val operator: Operator?, private val conditions: List<ParsedAction<*>>, val action: ParsedAction<*>) {
        fun matches(frame: QuestContext.Frame, input: Any?): CompletableFuture<Boolean> {
            val operation = operator ?: return CompletableFuture.completedFuture(true)
            val values = conditions.map { frame.newFrame(it).run<Any?>() }
            return follow(frame, CompletableFuture.allOf(*values.toTypedArray())) {
                val results = values.map { it.join() }
                val matches = if (operation == Operator.IN || operation == Operator.CONTAINS) {
                    operation.matches(input, if (results.size == 1) results[0] else results)
                } else results.any { operation.matches(input, it) }
                CompletableFuture.completedFuture(matches)
            }
        }
    }
    private enum class Operator {
        EQUAL, NOT_EQUAL, STRICT, IDENTITY, IGNORE_CASE, GT, GTE, LT, LTE, IN, CONTAINS;
        fun matches(left: Any?, right: Any?): Boolean = when (this) {
            EQUAL -> inferredEquals(left, right)
            NOT_EQUAL -> !inferredEquals(left, right)
            STRICT -> Objects.equals(left, right)
            IDENTITY -> left === right
            IGNORE_CASE -> text(left).equals(text(right), true)
            GT -> number(left) > number(right)
            GTE -> number(left) >= number(right)
            LT -> number(left) < number(right)
            LTE -> number(left) <= number(right)
            IN -> contains(right, left)
            CONTAINS -> contains(left, right)
        }
        companion object {
            fun parse(value: String): Operator? = when (value.lowercase(Locale.ROOT)) {
                "==", "is" -> EQUAL
                "!=", "!is", "not" -> NOT_EQUAL
                "=!", "is!" -> STRICT
                "=!!", "is!!" -> IDENTITY
                "=?", "is?" -> IGNORE_CASE
                ">", "gt" -> GT
                ">=", "gte" -> GTE
                "<", "lt" -> LT
                "<=", "lte" -> LTE
                "in" -> IN
                "contains", "has" -> CONTAINS
                else -> null
            }
            private fun inferredEquals(left: Any?, right: Any?): Boolean {
                val first = infer(left); val second = infer(right)
                return if (first is Number && second is Number) first.toDouble() == second.toDouble() else Objects.equals(first, second)
            }
            private fun infer(value: Any?): Any? {
                if (value !is String) return value
                value.toDoubleOrNull()?.let { return it }
                return when (value) { "true" -> true; "false" -> false; else -> value }
            }
            private fun number(value: Any?): BigDecimal = BigDecimal(text(value))
            private fun contains(collection: Any?, value: Any?): Boolean = when {
                collection is Collection<*> -> collection.contains(value)
                collection is Map<*, *> -> collection.containsKey(value)
                collection != null && collection.javaClass.isArray -> (0 until ReflectArray.getLength(collection)).any { Objects.equals(ReflectArray.get(collection, it), value) }
                else -> collection != null && text(collection).contains(text(value))
            }
        }
    }
}
