/* Kether action semantics: Copyright (c) 2018 Bkm016. Coerce: Copyright SpongePowered and contributors.
 * MIT License. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestReader
import java.lang.reflect.Array as ReflectArray
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import java.util.regex.Pattern

/** 统一动作续接与原 Coerce 转换；同步动作不向执行器派发。 */
class KetherSupport private constructor() {
    companion object {
        const val BREAK_LOOP = "~klib:break-loop"
        private val BRACKETS = Pattern.compile("^([(\\[{]?)(.+?)([)\\]}]?)$")
        @JvmStatic fun action(operation: Function<QuestContext.Frame, CompletableFuture<Any?>>): QuestAction<Any?> = object : QuestAction<Any?>() {
            override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> = operation.apply(frame)
        }
        @JvmStatic fun now(operation: Function<QuestContext.Frame, Any?>): QuestAction<Any?> = action { completed(operation.apply(it)) }
        @JvmStatic fun completed(value: Any?): CompletableFuture<Any?> = CompletableFuture.completedFuture(value)
        @JvmStatic fun run(frame: QuestContext.Frame, action: ParsedAction<*>): CompletableFuture<Any?> = frame.newFrame(action).run()
        @JvmStatic fun <T, R> follow(frame: QuestContext.Frame, input: CompletableFuture<T>, next: Function<T, CompletableFuture<R>>): CompletableFuture<R> =
            if (input.isDone) input.thenCompose(next) else input.thenComposeAsync(next, frame.context().executor)
        @JvmStatic fun consume(reader: QuestReader, vararg tokens: String): Boolean {
            val index = reader.index
            if (reader.hasNext() && reader.nextToken() in tokens) return true
            reader.index = index
            return false
        }
        @JvmStatic fun expects(reader: QuestReader, vararg tokens: String): String {
            val token = reader.nextToken()
            require(token in tokens) { "Expected one of ${tokens.joinToString(", ")}, got $token" }
            return token
        }
        @JvmStatic fun root(frame: QuestContext.Frame, key: String): Any? = frame.context().rootFrame().variables().getOrNull<Any>(key)
        @JvmStatic fun setRoot(frame: QuestContext.Frame, key: String, value: Any?) {
            if (value == null) frame.context().rootFrame().variables().remove(key) else frame.context().rootFrame().variables().set(key, value)
        }
        @JvmStatic fun takeBreak(frame: QuestContext.Frame): Boolean {
            if (root(frame, BREAK_LOOP) != true) return false
            setRoot(frame, BREAK_LOOP, null)
            return true
        }
        @JvmStatic fun text(value: Any?): String = value?.toString() ?: ""
        @JvmStatic fun bool(value: Any?): Boolean {
            if (value == null) return false
            if (value is Boolean) return value
            val text = value.toString().trim { it <= ' ' }
            return text == "1" || text.equals("true", true) || text.equals("yes", true)
        }
        @JvmStatic fun integer(value: Any?): Int = when (value) {
            null -> 0
            is Number -> value.toInt()
            else -> sanitized(value).let { it.toIntOrNull() ?: number(it).toInt() }
        }
        @JvmStatic fun longValue(value: Any?): Long = when (value) {
            null -> 0L
            is Number -> value.toLong()
            else -> sanitized(value).toLongOrNull() ?: 0L
        }
        @JvmStatic fun number(value: Any?): Double = when (value) {
            null -> 0.0
            is Number -> value.toDouble()
            else -> sanitized(value).toDoubleOrNull() ?: 0.0
        }
        @JvmStatic fun isInt(value: Any?): Boolean = value.toString().toIntOrNull() != null
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
        @Suppress("UNCHECKED_CAST")
        @JvmStatic fun asList(value: Any?): MutableList<Any?> = if (value is List<*>) value as MutableList<Any?> else arrayListOf(value)
        @JvmStatic fun elements(value: Any?): List<Any?> = when {
            value is Collection<*> -> ArrayList(value)
            value is Map<*, *> -> ArrayList(value.entries)
            value != null && value.javaClass.isArray -> (0 until ReflectArray.getLength(value)).map { ReflectArray.get(value, it) }
            else -> arrayListOf(value)
        }
        @JvmStatic fun unmodifiable(values: List<Any?>): List<Any?> = Collections.unmodifiableList(values)
    }
}
