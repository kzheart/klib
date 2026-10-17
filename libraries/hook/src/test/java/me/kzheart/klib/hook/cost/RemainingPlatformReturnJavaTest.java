package me.kzheart.klib.hook.cost;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemainingPlatformReturnJavaTest {
    @Test void costResultsKeepDeclaredPlatformAccessors() throws Exception {
        CostLine line = new CostLine("coins", true, null);
        assertNull(line.error());
        assertNull(CostResult.success(Collections.singletonList(line)).failedCost());
        assertEquals(String.class, CostLine.class.getDeclaredMethod("error").getReturnType());
        assertEquals(String.class, CostResult.class.getDeclaredMethod("failedCost").getReturnType());
        CostResult failed = CostResult.takeFailed(Arrays.asList(line), "coins", "failed", Arrays.asList("refund"));
        assertFalse(failed.compensated());
        assertEquals("coins", failed.failedCost());
        assertThrows(UnsupportedOperationException.class, () -> failed.refundFailures().clear());
    }
}
