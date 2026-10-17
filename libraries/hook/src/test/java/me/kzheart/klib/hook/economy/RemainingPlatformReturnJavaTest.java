package me.kzheart.klib.hook.economy;

import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemainingPlatformReturnJavaTest {
    @Test void resultValidatesRequiredFieldsButRetainsNullCause() throws Exception {
        assertNull(CurrencyResult.failure(BigDecimal.ONE, "failed", null).cause());
        assertEquals(Throwable.class, CurrencyResult.class.getDeclaredMethod("cause").getReturnType());
        assertFalse(Modifier.isFinal(CurrencyResult.class.getDeclaredMethod("success", BigDecimal.class).getModifiers()));
        assertThrows(NullPointerException.class, () -> CurrencyResult.success(null));
        assertThrows(NullPointerException.class, () -> CurrencyResult.failure(BigDecimal.ONE, null));
        assertThrows(NullPointerException.class, () -> new CurrencyResult.Uncompensated(null, BigDecimal.ONE));
    }
}
