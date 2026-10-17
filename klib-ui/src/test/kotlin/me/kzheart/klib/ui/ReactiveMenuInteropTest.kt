package me.kzheart.klib.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class ReactiveMenuInteropTest {
    @Test
    fun `Kotlin uses the same Java state and lifecycle core`() {
        MenuTestEnvironment().use { fixture ->
            val ui = fixture.menus.openReactive(fixture.player, "kotlin", State(0)) { session ->
                menu("Kotlin", 1) { }
            }
            ui.update { it.copy(page = 1) }
            val late = CompletableFuture<Int>()
            val observation = ui.readLatest("page", late,
                { current, page -> current.copy(page = page) }, { throw AssertionError(it) })
            ui.update { it.copy(page = 2) }
            assertFalse(observation.toCompletableFuture().join())
            late.complete(100)
            fixture.tick()
            assertEquals(2, ui.state().page)
            assertEquals(1, fixture.opens)
        }
    }

    data class State(val page: Int)
}
