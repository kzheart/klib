package me.kzheart.klib.data;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class SchemaValidationTest {
    @Test void versionsMustBePositiveUniqueAndHaveAnAction() {
        assertThrows(IllegalArgumentException.class, () -> new Migration(0, context -> { }));
        assertThrows(IllegalArgumentException.class, () -> new Migration(-1, context -> { }));
        assertThrows(NullPointerException.class, () -> new Migration(1, null));
        assertThrows(IllegalArgumentException.class, () -> new Schema("x", Arrays.asList(
                new Migration(1, context -> { }), new Migration(1, context -> { }))));
        assertThrows(NullPointerException.class, () -> new Schema("x", Collections.singletonList(null)));
    }
    @Test void orderIsImmutableAndSparsePositiveVersionsRemainSupported() {
        Schema schema = new Schema("x", Arrays.asList(new Migration(5, context -> { }), new Migration(1, context -> { })));
        assertEquals(1, schema.migrations().get(0).version()); assertEquals(5, schema.version());
        assertThrows(UnsupportedOperationException.class, () -> schema.migrations().clear());
        assertEquals(0, new Schema("empty", Collections.emptyList()).version());
    }
}
