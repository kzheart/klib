package me.kzheart.klib.config;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformReturnContractJavaTest {
    @Test void missingRawValuesStayNullAcrossBothDocumentEngines() {
        ConfigNode yaml = YamlDocument.parse("test.yml", "value: null").node("value");
        Object raw = yaml.raw();
        assertNull(raw);
        ValueDocument values = ValueDocument.of("test.json", Collections.singletonMap("value", null), (path, snapshot) -> "{}");
        assertNull(values.node("value").raw());
        assertNull(values.node("missing").raw());
        assertEquals("value", yaml.name());
    }
}
