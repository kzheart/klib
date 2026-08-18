package me.kzheart.klib.compat.v26;

import me.kzheart.klib.compat.Capabilities;
import me.kzheart.klib.compat.v1_12.V1_12CompatImplementation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatCapabilityBehaviorTest {
    @Test
    void capabilitiesDescribeRealVersionDifferences() {
        V1_12CompatImplementation legacy = new V1_12CompatImplementation();
        V26CompatImplementation current = new V26CompatImplementation();

        assertFalse(legacy.capability(Capabilities.TEXT).get().supportsHexColors());
        assertFalse(legacy.capability(Capabilities.NBT).get().supportsPersistentDataContainer());
        assertTrue(legacy.capability(Capabilities.MATERIAL).get().usesLegacyNames());
        assertFalse(legacy.capability(Capabilities.INVENTORY).get().supportsRuntimeTitleUpdates());

        assertTrue(current.capability(Capabilities.TEXT).get().supportsHexColors());
        assertTrue(current.capability(Capabilities.NBT).get().supportsPersistentDataContainer());
        assertFalse(current.capability(Capabilities.MATERIAL).get().usesLegacyNames());
        assertTrue(current.capability(Capabilities.INVENTORY).get().supportsRuntimeTitleUpdates());
    }
}
