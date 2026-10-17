package me.kzheart.klib.hook.papi

import java.text.DecimalFormat
import java.time.Duration
import java.util.LinkedHashMap
import java.util.UUID
import java.util.function.BiFunction
import java.util.function.Function
import org.bukkit.OfflinePlayer

/** 由精确键和前缀键组成的已编译占位符解析器。 */
class PapiExpansion internal constructor(exact: Map<String, Resolver>, prefixed: Map<String, Resolver>) {
    private val exact = LinkedHashMap(exact)
    // 最长前缀优先匹配；相同长度仍保持注册顺序。
    private val prefixed = LinkedHashMap<String, Resolver>().apply {
        prefixed.entries.sortedByDescending { it.key.length }.forEach { put(it.key, it.value) }
    }

    fun resolve(player: OfflinePlayer?, parameters: String): String? {
        val direct = exact[parameters]
        if (direct != null) return direct.resolve(player, "")
        for ((prefix, resolver) in prefixed) {
            if (parameters.startsWith(prefix)) return resolver.resolve(player, parameters.substring(prefix.length))
        }
        return null
    }

    fun interface Resolver {
        fun resolve(player: OfflinePlayer?, suffix: String): String?
    }

    companion object {
        @JvmStatic fun exact(function: Function<in OfflinePlayer?, *>): Resolver =
            Resolver { player, _ -> stringify(function.apply(player)) }
        @JvmStatic fun prefixed(function: BiFunction<in OfflinePlayer?, String, *>): Resolver =
            Resolver { player, suffix -> stringify(function.apply(player, suffix)) }
        @JvmStatic fun cached(delegate: Resolver, ttl: Duration): Resolver {
            require(!ttl.isZero && !ttl.isNegative) { "ttl must be positive" }
            return CachingResolver(delegate, ttl.toNanos())
        }
        private fun stringify(value: Any?): String = when (value) {
            null -> ""
            is Number -> DecimalFormat("0.##").format(value)
            else -> value.toString()
        }
    }

    private class CachingResolver(private val delegate: Resolver, private val ttlNanos: Long) : Resolver {
        private val cache = object : LinkedHashMap<Key, Value>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Value>?): Boolean = size > MAX_ENTRIES
        }

        @Synchronized override fun resolve(player: OfflinePlayer?, suffix: String): String? {
            val now = System.nanoTime()
            val key = Key(player?.uniqueId, suffix)
            val existing = cache[key]
            if (existing != null && now - existing.createdAt < ttlNanos) return existing.content
            val content = delegate.resolve(player, suffix)
            cache[key] = Value(content, now)
            return content
        }

        companion object { private const val MAX_ENTRIES = 256 }
    }

    private data class Key(val playerId: UUID?, val suffix: String)
    private class Value(val content: String?, val createdAt: Long)
}
