package me.kzheart.klib.script;

import java.lang.reflect.Field;
import me.kzheart.klib.script.taboolib.common.OpenAPI;
import me.kzheart.klib.script.taboolib.common.OpenResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyOpenResultAbiTest {
    @Test void exportedProtocolKeepsLegacyFieldNamesAndNullableValues() throws Exception {
        Object value = new Object();
        OpenResult result = OpenResult.successful(value);
        assertSame(boolean.class, OpenResult.class.getDeclaredField("successful").getType());
        assertSame(Object.class, OpenResult.class.getDeclaredField("value").getType());
        assertEquals(true, field(result, "successful"));
        assertSame(value, field(result, "value"));
        assertSame(value, result.getValue());
        assertNull(field(OpenResult.successful(null), "value"));
        assertEquals(false, field(OpenResult.failed(), "successful"));
        assertNull(field(OpenResult.failed(), "value"));
    }
    @Test void actualContainerExportUsesTheSameProtocolShape() throws Exception {
        OpenResult result = OpenAPI.call("unknown-channel", new Object[0]);
        assertEquals(false, field(result, "successful"));
        assertNull(field(result, "value"));
        assertFalse(result.isSuccessful());
    }
    private static Object field(Object result, String name) throws Exception {
        Field field = result.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(result);
    }
}
