package me.kzheart.klib.remote;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteEventSubclassCompatibilityTest {
    /** Existing same-package subclasses may hide the original non-final static factory. */
    private static final class CustomEvent extends RemoteEvent {
        private CustomEvent(Map<String, ?> fields) { super(fields); }
        public static RemoteEvent of(String type, Map<String, ?> fields) {
            return new CustomEvent(fields);
        }
    }

    @Test void staticHidingAndOverridableClassBoundaryArePreserved() {
        assertEquals("custom", CustomEvent.of("unused", Collections.singletonMap("kind", "custom"))
                .toMap().get("kind"));
    }
}
