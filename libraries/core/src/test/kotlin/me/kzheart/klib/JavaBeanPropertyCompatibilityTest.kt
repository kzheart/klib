package me.kzheart.klib

import me.kzheart.klib.component.ComponentHandle
import me.kzheart.klib.random.WeightedPool
import me.kzheart.klib.scope.ScopeImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JavaBeanPropertyCompatibilityTest {
    @Test fun `component handle preserves both old Kotlin getter spellings`() {
        val scope = ScopeImpl("bean-contract")
        val handle = ComponentHandle(scope)
        assertFalse(handle.isClosed)
        assertEquals(handle.isClosed(), handle.isClosed)
        handle.close()
        assertTrue(handle.isClosed)
        assertTrue(ComponentHandle::class.java.getMethod("closedProperty").isSynthetic)
        assertFalse(ComponentHandle::class.java.getMethod("isClosed").isSynthetic)
    }

    @Test fun `weighted pool retains method and property access`() {
        val empty = WeightedPool.builder<String>().build()
        val filled = WeightedPool.builder<String>().add("value", 1.0).build()
        assertTrue(empty.isEmpty)
        assertTrue(empty.isEmpty())
        assertFalse(filled.isEmpty)
        assertFalse(filled.isEmpty())
    }
}
