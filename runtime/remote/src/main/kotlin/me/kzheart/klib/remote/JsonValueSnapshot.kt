package me.kzheart.klib.remote

import java.lang.reflect.Array as ReflectArray
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/** 有界、不可变、全有或全无的 JSON 值快照。 */
class JsonValueSnapshot private constructor() {
    private val visiting = IdentityHashMap<Any, Boolean>()
    private var nodes = 0
    private fun value(value: Any?, depth: Int): Any? = when {
        value is Map<*, *> -> map(value, depth)
        value is List<*> -> container(value, depth) {
            if (value.size > MAX_CONTAINER_ENTRIES) reject("container entries")
            Collections.unmodifiableList(value.map { value(it, depth + 1) })
        }
        value != null && value.javaClass.isArray -> container(value, depth) {
            val size = ReflectArray.getLength(value)
            if (size > MAX_CONTAINER_ENTRIES) reject("container entries")
            Collections.unmodifiableList((0 until size).map { value(ReflectArray.get(value, it), depth + 1) })
        }
        else -> {
            claimNode(depth)
            when (value) {
                null, is String, is Boolean -> value
                is Number -> number(value)
                else -> throw IllegalArgumentException("unsupported JSON snapshot value")
            }
        }
    }
    private fun map(source: Map<*, *>, depth: Int): Map<String, Any?> = container(source, depth) {
        val result = LinkedHashMap<String, Any?>()
        var entries = 0
        for ((key, entry) in source) {
            if (++entries > MAX_CONTAINER_ENTRIES) reject("container entries")
            require(key is String) { "JSON snapshot keys must be strings" }
            result[Texts.requireText(key, "field name")] = value(entry, depth + 1)
        }
        Collections.unmodifiableMap(result)
    }
    private inline fun <T> container(source: Any, depth: Int, copy: () -> T): T {
        claimNode(depth)
        if (visiting.put(source, true) != null) reject("cycle")
        try { return copy() } finally { visiting.remove(source) }
    }
    private fun claimNode(depth: Int) {
        if (depth > MAX_DEPTH) reject("depth")
        if (++nodes > MAX_NODES) reject("nodes")
    }
    private fun number(value: Number): Number = when (value) {
        is Double, is Float -> value.also { require(it.toDouble().isFinite()) { "JSON snapshot number must be finite" } }
        is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> value
        else -> throw IllegalArgumentException("unsupported JSON snapshot number")
    }
    private fun reject(limit: String): Nothing = throw IllegalArgumentException("JSON snapshot exceeds $limit limit")
    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_NODES = 4096
        private const val MAX_CONTAINER_ENTRIES = 256
        @JvmStatic fun copy(source: Map<String, *>): Map<String, Any?> = JsonValueSnapshot().map(source, 0)
    }
}
