package me.kzheart.klib.hook.papi

import java.time.Duration
import java.util.LinkedHashMap
import java.util.function.BiFunction
import java.util.function.Function
import org.bukkit.OfflinePlayer

/** 精确匹配、前缀匹配及可选缓存占位符的构建器。 */
class PapiDsl {
    private val exact = LinkedHashMap<String, PapiExpansion.Resolver>()
    private val prefixed = LinkedHashMap<String, PapiExpansion.Resolver>()

    fun key(key: String, resolver: Function<in OfflinePlayer?, *>): PapiDsl = apply {
        put(exact, key, PapiExpansion.exact(resolver))
    }
    fun keyCached(key: String, ttl: Duration, resolver: Function<in OfflinePlayer?, *>): PapiDsl = apply {
        put(exact, key, PapiExpansion.cached(PapiExpansion.exact(resolver), ttl))
    }
    fun prefixed(prefix: String, resolver: BiFunction<in OfflinePlayer?, String, *>): PapiDsl = apply {
        put(prefixed, prefix, PapiExpansion.prefixed(resolver))
    }
    fun prefixedCached(prefix: String, ttl: Duration, resolver: BiFunction<in OfflinePlayer?, String, *>): PapiDsl = apply {
        put(prefixed, prefix, PapiExpansion.cached(PapiExpansion.prefixed(resolver), ttl))
    }
    fun build(): PapiExpansion = PapiExpansion(exact, prefixed)

    companion object {
        private fun put(destination: MutableMap<String, PapiExpansion.Resolver>, key: String,
                        resolver: PapiExpansion.Resolver) {
            val normalized = key.trim { it <= ' ' }
            require(normalized.isNotEmpty()) { "placeholder key must not be blank" }
            require(destination.put(normalized, resolver) == null) { "duplicate placeholder key: $normalized" }
        }
    }
}
