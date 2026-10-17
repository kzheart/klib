/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import me.kzheart.klib.script.KetherSupport.Companion.action
import me.kzheart.klib.script.KetherSupport.Companion.asList
import me.kzheart.klib.script.KetherSupport.Companion.completed
import me.kzheart.klib.script.KetherSupport.Companion.consume
import me.kzheart.klib.script.KetherSupport.Companion.expects
import me.kzheart.klib.script.KetherSupport.Companion.follow
import me.kzheart.klib.script.KetherSupport.Companion.integer
import me.kzheart.klib.script.KetherSupport.Companion.number
import me.kzheart.klib.script.KetherSupport.Companion.run
import me.kzheart.klib.script.KetherSupport.Companion.takeBreak
import me.kzheart.klib.script.KetherSupport.Companion.text
import me.kzheart.klib.script.kether.core.ArgTypes
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestReader
import java.lang.reflect.Array as ReflectArray
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import java.util.regex.Pattern

/** 集合动作只描述变换；参数消费顺序和异步求值由共同组合器保证。 */
class CollectionScriptActions private constructor() {
    companion object {
        @JvmStatic fun install(registry: StatementRegistry) {
            val size = unary { value -> when {
                value is Collection<*> -> value.size
                value != null && value.javaClass.isArray -> ReflectArray.getLength(value)
                else -> value.toString().length
            } }
            register(registry, size, "size", "length")
            registry.registerBuiltinKether("mutable", unary { ArrayList(asList(it)) })
            registry.registerBuiltinKether("shuffle", unary { ArrayList(asList(it)).apply { Collections.shuffle(this) } })
            registry.registerBuiltinKether("reverse", unary { ArrayList(asList(it)).apply { reverse() } })
            val array = NativeParser.of { reader ->
                val items = reader.next(ArgTypes.listOf(ArgTypes.ACTION))
                action { frame -> collect(frame, items).thenApply { it } }
            }
            register(registry, array, "array", "arr")
            register(registry, indexed(arrayOf("in", "of")) { index, list -> list.getOrNull(index) }, "arr-get", "element", "elem")
            registry.registerBuiltinKether("arr-add", element(arrayOf("to")) { value, list -> list.add(value) })
            register(registry, element(arrayOf("to")) { value, list -> list.add(0, value); null }, "arr-add-first", "arr-push")
            registry.registerBuiltinKether("arr-remove", element(arrayOf("in")) { value, list -> list.remove(value) })
            registry.registerBuiltinKether("arr-remove-at", indexed(arrayOf("in")) { index, list -> list.removeAt(index) })
            register(registry, unary { asList(it).let { list -> if (list.isEmpty()) null else list.removeAt(0) } }, "arr-remove-first", "arr-take")
            register(registry, unary { asList(it).let { list -> if (list.isEmpty()) null else list.removeAt(list.lastIndex) } }, "arr-remove-last", "arr-drop")
            registry.registerBuiltinKether("arr-find", element(arrayOf("in", "of")) { value, list -> list.indexOf(value) })
            registry.registerBuiltinKether("split", NativeParser.of { reader ->
                val source = reader.nextValue<Any?>()
                val separator = if (consume(reader, "by", "with")) reader.nextValue<Any?>() else null
                action { frame -> follow(frame, run(frame, source)) { value ->
                    val sourceText = text(value)
                    if (separator == null) completed(sourceText.mapTo(ArrayList<Any?>()) { it.toString() })
                    else follow(frame, run(frame, separator)) { regex -> completed(Pattern.compile(text(regex)).split(sourceText, -1).toMutableList()) }
                } }
            })
            registry.registerBuiltinKether("range", NativeParser.of { reader ->
                val from = reader.nextValue<Any?>(); reader.expect("to")
                val to = reader.nextValue<Any?>()
                val step = if (consume(reader, "step")) reader.nextValue<Any?>() else null
                action { frame -> follow(frame, run(frame, from)) { start -> follow(frame, run(frame, to)) { end ->
                    if (step == null) completed(range(number(start), number(end), 0.0))
                    else follow(frame, run(frame, step)) { increment -> completed(range(number(start), number(end), number(increment))) }
                } } }
            })
        }
        private fun register(registry: StatementRegistry, parser: NativeParser, vararg names: String) {
            names.forEach { registry.registerBuiltinKether(it, parser) }
        }
        @JvmStatic fun joinList(reader: QuestReader): QuestAction<Any?> {
            val items = reader.next(ArgTypes.listOf(ArgTypes.ACTION))
            val separator = if (consume(reader, "by", "with")) reader.nextValue<Any?>() else null
            return action { frame ->
                follow(frame, if (separator == null) completed("") else run(frame, separator)) { value ->
                    Join(frame, items, text(value)).start()
                }
            }
        }
        private fun range(from: Double, to: Double, step: Double): List<Any?> {
            val result = ArrayList<Any?>()
            if (step == 0.0) {
                var value = from.toInt()
                while (value <= to.toInt()) { result.add(value); value++ }
            } else {
                val intStep = step.toInt().toDouble() == step
                var value = from
                while (value <= to) { result.add(if (intStep) value.toInt() else value); value += step }
            }
            return result
        }
        @JvmStatic fun collect(frame: QuestContext.Frame, items: List<ParsedAction<*>>): CompletableFuture<List<Any?>> {
            var result = CompletableFuture.completedFuture<List<Any?>>(ArrayList())
            for (item in items) result = follow(frame, result) { values -> follow(frame, run(frame, item)) { value ->
                @Suppress("UNCHECKED_CAST")
                (values as MutableList<Any?>).add(value)
                CompletableFuture.completedFuture(values)
            } }
            return result
        }
        private fun unary(operation: (Any?) -> Any?): NativeParser = NativeParser.of { reader ->
            val input = reader.nextValue<Any?>()
            action { frame -> follow(frame, run(frame, input)) { value -> completed(operation(value)) } }
        }
        private fun element(keywords: Array<String>, operation: (Any?, MutableList<Any?>) -> Any?): NativeParser = NativeParser.of { reader ->
            val value = reader.nextValue<Any?>(); expects(reader, *keywords)
            val list = reader.nextValue<Any?>()
            action { frame -> follow(frame, run(frame, value)) { element -> follow(frame, run(frame, list)) { target ->
                completed(operation(element, asList(target)))
            } } }
        }
        private fun indexed(keywords: Array<String>, operation: (Int, MutableList<Any?>) -> Any?): NativeParser =
            element(keywords) { value, list -> operation(integer(value), list) }
    }
    private class Join(private val frame: QuestContext.Frame, private val items: List<ParsedAction<*>>, private val separator: String) {
        private val values = ArrayList<Any?>()
        private val future = CompletableFuture<Any?>()
        fun start(): CompletableFuture<Any?> { next(0); return future }
        private fun next(index: Int) {
            if (index >= items.size) { future.complete(joined()); return }
            val input = run(frame, items[index])
            val continuation = BiConsumer<Any?, Throwable?> { value, failure ->
                if (failure != null) future.complete("") else {
                    values.add(value)
                    if (takeBreak(frame)) future.complete(joined()) else next(index + 1)
                }
            }
            val observed = if (input.isDone) input.whenComplete(continuation) else input.whenCompleteAsync(continuation, frame.context().executor)
            observed.whenComplete { _, failure -> if (failure != null && !future.isDone) future.completeExceptionally(ScriptFutures.unwrap(failure)) }
        }
        private fun joined(): String = values.joinToString(separator) { it.toString() }
    }
}
