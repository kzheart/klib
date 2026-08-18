package me.kzheart.klib.compat.v26;

import me.kzheart.klib.compat.Capabilities;
import me.kzheart.klib.compat.ServerVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V26CompatImplementationTest {
    @Test
    void exposesEveryCapabilityOnlyForTheTwentySixReleaseLine() {
        V26CompatImplementation provider = new V26CompatImplementation();

        assertEquals(ServerVersion.of(26, 2, 0), provider.version());
        assertTrue(provider.supports(ServerVersion.parse("26.2")));
        assertTrue(provider.supports(ServerVersion.parse("26.3")));
        assertFalse(provider.supports(ServerVersion.parse("27.1")));
        assertTrue(provider.capability(Capabilities.TEXT).isPresent());
        assertTrue(provider.capability(Capabilities.NBT).isPresent());
        assertTrue(provider.capability(Capabilities.MATERIAL).isPresent());
        assertTrue(provider.capability(Capabilities.INVENTORY).isPresent());
    }
}
