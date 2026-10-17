package me.kzheart.klib.hook

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import me.kzheart.klib.hook.cost.CostType
import me.kzheart.klib.hook.cost.CostTypes
import me.kzheart.klib.hook.cost.Costs
import me.kzheart.klib.hook.cost.RewardType
import me.kzheart.klib.hook.cost.RewardTypes
import me.kzheart.klib.hook.cost.Rewards
import me.kzheart.klib.hook.papi.Papi
import me.kzheart.klib.hook.papi.PapiDsl
import me.kzheart.klib.scope.ScopeImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookKotlinInteropTest {
    @Test
    fun kotlinLambdasWorkWithPublicFactoriesAndSamRegistries() {
        val hook = Hooks.orNoop("test", { "ready" }, "noop")
        val costs = Costs.builder().type("check", CostType { CostTypes.permission().parse(it) }).build()
        val rewards = Rewards.builder().type("say", RewardType { RewardTypes.message().parse(it) }).build()

        assertEquals("ready", hook.value())
        assertEquals("权限 test.use", costs.parseOne("check:test.use").describe())
        assertEquals(listOf("消息"), rewards.parse("say:hello").describe())
        assertThrows(UnsupportedOperationException::class.java) {
            (costs.types() as MutableCollection<String>).clear()
        }
    }

    @Test
    fun nullableOfflinePlayersAndCachedResolversRemainSupported() {
        val calls = AtomicInteger()
        val expansion = PapiDsl().keyCached("value", Duration.ofMinutes(1)) { player ->
            assertEquals(null, player)
            calls.incrementAndGet()
        }.build()

        assertEquals("1", expansion.resolve(null, "value"))
        assertEquals("1", expansion.resolve(null, "value"))
        assertEquals(1, calls.get())
    }

    @Test
    fun kotlinDslRegistrationsFollowScopeLifecycle() {
        val scope = ScopeImpl("interop")
        val registration = Papi.register(scope, Papi.noopRegistrar(), "Interop") { dsl ->
            dsl.key("value") { "ok" }
        }
        assertEquals("interop", registration.identifier())
        assertFalse(registration.isAvailable())
        scope.close()
        assertTrue(registration.isDisposed())
    }
}
