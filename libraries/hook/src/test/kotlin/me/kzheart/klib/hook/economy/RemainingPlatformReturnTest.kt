package me.kzheart.klib.hook.economy

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemainingPlatformReturnTest {
    private fun cause(result: CurrencyResult): String =
        if (result.cause() != null) result.cause().javaClass.simpleName else "none"

    @Test fun currencyResultsRetainNullAndOriginalFailureIdentity() {
        val successful = CurrencyResult.success(BigDecimal.ONE)
        assertNull(successful.cause())
        assertEquals("none", cause(successful))
        assertEquals("none", cause(CurrencyResult.failure(BigDecimal.ONE, "rejected")))
        val failure = IllegalStateException("failure")
        val failed = CurrencyResult.failure(BigDecimal.ONE, "rejected", failure)
        val direct: Throwable = failed.cause()
        assertSame(failure, direct)
        assertEquals("IllegalStateException", cause(failed))
    }

    @Test fun compensationRecordsKeepDefensiveSnapshots() {
        val records = arrayListOf(CurrencyResult.Uncompensated("gold", BigDecimal.ONE))
        val result = CurrencyResult.rollbackFailure(BigDecimal.TEN, "rollback", null, records)
        records.clear()
        assertFalse(result.compensated())
        assertNull(result.cause())
        assertEquals(1, result.uncompensated().size)
        assertThrows(UnsupportedOperationException::class.java) { result.uncompensated().clear() }
    }
}
