package me.kzheart.klib.hook.papi

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemainingPlatformReturnTest {
    private fun guarded(expansion: PapiExpansion, key: String): Int =
        if (expansion.resolve(null, key) != null) expansion.resolve(null, key).length else -1

    @Test fun unmatchedKeysKeepNullAndExactKeysKeepPlatformInference() {
        val expansion = PapiDsl().key("value") { "hello" }.key("empty") { null }
            .prefixed("top_") { _, suffix -> "short:$suffix" }
            .prefixed("top_balance_") { _, suffix -> "long:$suffix" }.build()
        assertNull(expansion.resolve(null, "missing"))
        assertEquals(-1, guarded(expansion, "missing"))
        assertEquals(5, guarded(expansion, "value"))
        val value: String = expansion.resolve(null, "value")
        assertEquals("hello", value)
        assertEquals("", expansion.resolve(null, "empty"))
        assertEquals("long:gold", expansion.resolve(null, "top_balance_gold"))
    }

    @Test fun nullResolverValuesRemainCacheableAndBounded() {
        val calls = AtomicInteger()
        val resolver = PapiExpansion.cached({ _, _ -> calls.incrementAndGet(); null }, Duration.ofMinutes(1))
        assertNull(resolver.resolve(null, "value"))
        assertNull(resolver.resolve(null, "value"))
        assertEquals(1, calls.get())
        repeat(256) { resolver.resolve(null, "key$it") }
        resolver.resolve(null, "value")
        assertEquals(258, calls.get())
        assertThrows(IllegalArgumentException::class.java) {
            PapiExpansion.cached({ _, _ -> null }, Duration.ZERO)
        }
    }
}
