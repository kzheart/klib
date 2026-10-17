package me.kzheart.klib.hook.cost

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemainingPlatformReturnTest {
    private fun error(line: CostLine): String = if (line.error() != null) line.error().uppercase() else "none"
    private fun failure(result: CostResult): Int = if (result.failedCost() != null) result.failedCost().length else 0

    @Test fun successfulCostsKeepNullInsteadOfNonNullAssertions() {
        val line = CostLine("balance", true, null)
        assertEquals("none", error(line))
        assertNull(line.error())
        val result = CostResult.success(listOf(line))
        assertNull(result.failedCost())
        assertEquals(0, failure(result))
        assertTrue(result.success())
        assertTrue(result.compensated())
    }

    @Test fun failedCostsKeepFirstFailureAndImmutableSnapshots() {
        val lines = arrayListOf(CostLine("first", false, "blocked"), CostLine("second", false, null))
        val result = CostResult.unsatisfied(lines)
        val name: String = result.failedCost()
        assertEquals("first", name)
        assertEquals(5, failure(result))
        assertEquals("BLOCKED", error(lines[0]))
        assertEquals("blocked", result.message())
        lines.clear()
        assertEquals(2, result.lines().size)
        assertThrows(UnsupportedOperationException::class.java) { result.lines().clear() }
        assertEquals("unsatisfied", CostResult.unsatisfied(listOf(CostLine("missing", false, null))).message())
        assertThrows(IllegalArgumentException::class.java) { CostResult.unsatisfied(emptyList()) }
    }
}
