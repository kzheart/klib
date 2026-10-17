package me.kzheart.klib.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MigrationSourceCompatibilityTest {
    private class NullableAction : Migration.MigrationAction {
        var called = false
        override fun apply(context: TransactionContext?) { called = true }
    }
    private class NonNullAction : Migration.MigrationAction {
        override fun apply(context: TransactionContext) = Unit
    }

    @Test fun `Java SAM preserves nullable and nonnull Kotlin implementations`() {
        val nullable = NullableAction()
        nullable.apply(null)
        assertTrue(nullable.called)
        assertEquals(2, Migration(2, nullable).version())
        assertEquals(3, Migration(3, NonNullAction()).version())
        assertTrue(Migration.MigrationAction::class.java.isAnnotationPresent(FunctionalInterface::class.java))
        assertArrayEquals(arrayOf(Exception::class.java), Migration.MigrationAction::class.java
            .getMethod("apply", TransactionContext::class.java).exceptionTypes)
    }

    @Test fun `migration keeps version validation before action validation`() {
        assertThrows(IllegalArgumentException::class.java) { Migration(0, null) }
        assertThrows(NullPointerException::class.java) { Migration(1, null) }
    }
}
