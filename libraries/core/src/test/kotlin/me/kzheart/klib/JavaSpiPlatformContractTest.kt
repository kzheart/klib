package me.kzheart.klib

import me.kzheart.klib.command.api.CommandContext
import me.kzheart.klib.command.api.CommandHandler
import me.kzheart.klib.scheduler.KScheduler
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scope.Scope
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Existing Kotlin SPI implementers may refine Java platform types either way. */
class JavaSpiPlatformContractTest {
    @Test fun nullableImplementerRemainsSourceCompatible() {
        val factory: SchedulerFactory = object : SchedulerFactory {
            override fun forScope(scope: Scope?): KScheduler? = null
        }
        assertNull(factory.forScope(null))
        var called = false
        val handler: CommandHandler = object : CommandHandler {
            override fun execute(context: CommandContext?) { called = context == null }
        }
        handler.execute(null)
        assertTrue(called)
    }
}
