/* Kether: Copyright (c) 2018 Bkm016. Coerce: Copyright SpongePowered and contributors.
 * MIT License. See META-INF/LICENSE-TabooLib-Kether.txt
 * and META-INF/LICENSE-Sponge-Coerce.txt. */
package me.kzheart.klib.script

import me.kzheart.klib.script.KetherSupport.Companion.action
import me.kzheart.klib.script.KetherSupport.Companion.completed
import me.kzheart.klib.script.KetherSupport.Companion.follow
import me.kzheart.klib.script.KetherSupport.Companion.integer
import me.kzheart.klib.script.KetherSupport.Companion.number
import me.kzheart.klib.script.KetherSupport.Companion.text
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestReader
import me.kzheart.klib.script.kether.core.SimpleReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom

/** 数值和赋值动作使用同一动作树；读取预算、转换和续接不另造解释器。 */
class NativeValueActions private constructor() {
    companion object {
        @JvmStatic fun install(registry: StatementRegistry) {
            registry.registerBuiltinKether("math", NativeParser.of(::math))
            registry.registerBuiltinKether("round", unary { value ->
                val number = number(value)
                require(!number.isNaN()) { "Cannot round NaN value." }
                Math.round(number).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            })
            val random = NativeParser.of(::random)
            registry.registerBuiltinKether("random", random)
            registry.registerBuiltinKether("random2", random)
            registry.registerBuiltinKether("set", NativeParser.of(::set))
            registry.registerBuiltinKether("permission", NativeParser.of { reader ->
                val permission = requiredValue(reader)
                action { frame -> follow(frame, frame.newFrame(permission).run<Any?>()) { value ->
                    val context = CoreScriptRuntime.context(frame)
                    val sender = context.sender().orElse(null)
                    check(sender != null && context.requireService(ScriptSenderQuery::class.java).isPlayer(sender)) { "No player selected." }
                    completed(context.requireService(PlayerQuery::class.java).hasPermission(sender, text(value)))
                } }
            })
            registry.registerBuiltinKether("sender", NativeParser.of { action { frame ->
                val context = CoreScriptRuntime.context(frame)
                val sender = context.sender().orElse(null)
                if (sender == null) completed("console") else {
                    val query = context.requireService(ScriptSenderQuery::class.java)
                    completed(if (!query.isPlayer(sender)) "console" else query.name(sender).toString())
                }
            } })
        }
        private fun unary(operation: (Any?) -> Any?): NativeParser = NativeParser.of { reader ->
            val input = requiredValue(reader)
            action { frame -> follow(frame, frame.newFrame(input).run<Any?>()) { value -> completed(operation(value)) } }
        }
        private fun math(reader: QuestReader): QuestAction<Any?> {
            val start = reader.index
            val arithmetic = Arithmetic.of(reader.nextToken())
            if (arithmetic != null) {
                reader.expect("[")
                val inputs = ArrayList<ParsedAction<*>>()
                while (!StructuredScriptActions.consume(reader, "]")) inputs.add(requiredValue(reader))
                return action { frame -> collect(frame, inputs, 0, ArrayList(), arithmetic) }
            }
            reader.index = start
            val inputs = arrayListOf(requiredValue(reader))
            val operations = ArrayList<Arithmetic>()
            while (reader.hasNext()) {
                val mark = reader.index
                try {
                    val symbol = Arithmetic.of(reader.nextToken())
                    if (symbol == null) { reader.index = mark; break }
                    val next = requiredValue(reader)
                    operations.add(symbol); inputs.add(next)
                } catch (failure: RuntimeException) {
                    if (failure is MissingValueException) throw failure
                    reader.index = mark; break
                }
            }
            require(inputs.size > 1) { "math requires an operator and another action" }
            return action { frame -> follow(frame, frame.newFrame(inputs[0]).run<Any?>()) { value ->
                val inferred = infer(value)
                if (inferred !is Number) throw ClassCastException("math base is not a Number")
                infix(frame, inputs, operations, 1, inferred)
            } }
        }
        private fun collect(frame: QuestContext.Frame, inputs: List<ParsedAction<*>>, index: Int,
            values: MutableList<Any?>, arithmetic: Arithmetic): CompletableFuture<Any?> {
            if (index == inputs.size) return completed(arithmetic.apply(values))
            return follow(frame, frame.newFrame(inputs[index]).run<Any?>()) { value ->
                values.add(value); collect(frame, inputs, index + 1, values, arithmetic)
            }.exceptionally { it.printStackTrace(); 0 }
        }
        private fun infix(frame: QuestContext.Frame, inputs: List<ParsedAction<*>>, operations: List<Arithmetic>, index: Int, current: Number): CompletableFuture<Any?> {
            if (index == inputs.size) return completed(current)
            return follow(frame, frame.newFrame(inputs[index]).run<Any?>()) { value ->
                infix(frame, inputs, operations, index + 1, operations[index - 1].apply(listOf(current, value)))
            }.exceptionally { it.printStackTrace(); 0 }
        }
        private fun random(reader: QuestReader): QuestAction<Any?> {
            val from = requiredValue(reader)
            val mark = reader.index
            return try {
                reader.expect("to")
                val to = requiredValue(reader)
                action { frame -> follow(frame, frame.newFrame(from).run<Any?>()) { left ->
                    follow(frame, frame.newFrame(to).run<Any?>()) { right -> completed(randomRange(text(left), text(right))) }
                } }
            } catch (failure: RuntimeException) {
                if (failure is MissingValueException) throw failure
                reader.index = mark
                action { frame -> follow(frame, frame.newFrame(from).run<Any?>()) { value -> completed(randomValue(value)) } }
            }
        }
        private fun randomRange(from: String, to: String): Any {
            if (isInt(from) && isInt(to)) {
                val first = integer(from); val second = integer(to)
                return ThreadLocalRandom.current().nextInt(minOf(first, second), maxOf(first, second) + 1)
            }
            val first = number(from); val second = number(to)
            val min = minOf(first, second); val max = maxOf(first, second)
            return if (min == max) max else ThreadLocalRandom.current().nextDouble(min, max)
        }
        private fun randomValue(value: Any?): Any? {
            val items: List<Any?>? = when (value) { is Collection<*> -> ArrayList(value); is Array<*> -> value.asList(); else -> null }
            if (items != null) {
                items.forEach { if (it == null) throw NullPointerException("null cannot be cast to non-null type kotlin.Any") }
                return if (items.isEmpty()) null else items[ThreadLocalRandom.current().nextInt(items.size)]
            }
            return if (value != null && isInt(value)) ThreadLocalRandom.current().nextInt(integer(value)) else randomRange("0.0", number(value).toString())
        }
        private fun set(reader: QuestReader): QuestAction<Any?> {
            requireInput(reader)
            val quotedKey = reader.peek() == '\'' || reader.peek() == '"'
            val key = requiredToken(reader)
            val bracket = key.indexOf('[')
            if (key.endsWith("]") && bracket > 0) {
                if (quotedKey) throw ClassCastException("Literal action is not a property getter")
                return propertySet(propertySource(reader, key.substring(0, bracket)), key.substring(bracket + 1, key.length - 1), assignmentValue(reader))
            }
            if (key == "property") {
                val mark = reader.index
                try {
                    val property = reader.nextToken(); reader.expect("from")
                    val source = requiredValue(reader); reader.expect("to")
                    return propertySet(source, property, requiredValue(reader))
                } catch (failure: RuntimeException) {
                    if (failure is MissingValueException) throw failure
                    reader.index = mark
                }
            }
            val mark = reader.index
            return try {
                reader.expect("to")
                val value = requiredValue(reader)
                action { frame -> follow(frame, frame.newFrame(value).run<Any?>()) { result ->
                    frame.variables().set(key, result); completed(null)
                }.exceptionally { it.printStackTrace(); null } }
            } catch (failure: RuntimeException) {
                if (failure is MissingValueException) throw failure
                reader.index = mark
                val value = requiredToken(reader)
                action { frame ->
                    val context = CoreScriptRuntime.context(frame)
                    val result = InlineValues.value(value, context)
                    context.setVariable(key, result); completed(result)
                }
            }
        }
        private fun assignmentValue(reader: QuestReader): ParsedAction<*> {
            val mark = reader.index
            return try { reader.expect("to"); requiredValue(reader) }
            catch (failure: RuntimeException) {
                if (failure is MissingValueException) throw failure
                reader.index = mark
                ParsedAction(literal(requiredToken(reader)))
            }
        }
        private fun propertySource(reader: QuestReader, source: String): ParsedAction<*> {
            if (source.startsWith("&")) {
                val variable = source.substring(1)
                return ParsedAction(action { frame -> completed(frame.variables().getOrNull<Any>(variable)) })
            }
            if (source.startsWith("*")) throw ClassCastException("Literal action is not a property getter")
            require(reader is SimpleReader) { "Property shorthand requires a native QuestReader" }
            val parser = reader.service.registry.getParser(source, reader.namespace).orElse(null)
            if (parser != null) return ParsedAction<Any?>(parser.resolve(reader))
            if (reader.service.isToleranceParser) return ParsedAction(literal(source))
            throw IllegalArgumentException("Unknown property source action: $source")
        }
        private fun propertySet(source: ParsedAction<*>, property: String, value: ParsedAction<*>): QuestAction<Any?> = action { frame ->
            follow(frame, frame.newFrame(source).run<Any?>()) { instance ->
                require(instance != null) { "Property object must be not null." }
                follow(frame, frame.newFrame(value).run<Any?>()) { result ->
                    require(ScriptProperties.write(frame, instance, property, result)) { "${instance.javaClass.simpleName}[$property] not supported yet." }
                    completed(null)
                }
            }
        }
        private fun requiredValue(reader: QuestReader): ParsedAction<*> { requireInput(reader); return reader.nextValue<Any?>() }
        private fun requiredToken(reader: QuestReader): String { requireInput(reader); return reader.nextToken() }
        private fun requireInput(reader: QuestReader) { if (!reader.hasNext() || reader.peek() == '}' || reader.peek() == ']') throw MissingValueException() }
        private fun literal(value: Any?): QuestAction<Any?> = action { completed(value) }
        private fun isInt(value: Any?): Boolean {
            if (value == null) throw NullPointerException("isInt requires a non-null value")
            return KetherSupport.isInt(value)
        }
        private fun infer(value: Any?): Any? {
            if (value !is String) return value
            value.toIntOrNull()?.let { return it }
            value.toLongOrNull()?.let { return it }
            value.toDoubleOrNull()?.let { return it }
            return when (value) { "true" -> true; "false" -> false; else -> value }
        }
    }
    private class MissingValueException : IllegalArgumentException("Expected a value action before end of input or closing delimiter") {
        companion object { private const val serialVersionUID = 1L }
    }
    private enum class Arithmetic {
        ADD, SUB, MUL, DIV;
        fun apply(values: List<Any?>): Number {
            if (values.all { it is Int || isInt(it) }) {
                var current = if (this == MUL) 1 else 0
                for ((index, input) in values.withIndex()) {
                    val value = integer(input)
                    current = when (this) { ADD -> current + value; SUB -> if (index == 0) value else current - value; MUL -> current * value; DIV -> if (index == 0) value else current / value }
                }
                return current
            }
            var current = if (this == MUL) 1.0 else 0.0
            for ((index, input) in values.withIndex()) {
                val value = number(input)
                current = when (this) { ADD -> current + value; SUB -> if (index == 0) value else current - value; MUL -> current * value; DIV -> if (index == 0) value else current / value }
            }
            return current
        }
        companion object {
            fun of(value: String): Arithmetic? = when (value) { "add", "+" -> ADD; "sub", "-" -> SUB; "mul", "*" -> MUL; "div", "/" -> DIV; else -> null }
        }
    }
}
