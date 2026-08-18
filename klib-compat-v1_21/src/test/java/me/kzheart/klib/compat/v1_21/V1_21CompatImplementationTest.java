package me.kzheart.klib.compat.v1_21;

import me.kzheart.klib.compat.Capabilities;
import me.kzheart.klib.compat.ServerVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1_21CompatImplementationTest {
    @Test
    void exposesEveryCapabilityAcrossTheTwentyOneRange() {
        V1_21CompatImplementation provider = new V1_21CompatImplementation();

        assertEquals(ServerVersion.of(1, 21, 0), provider.version());
        assertTrue(provider.supports(ServerVersion.parse("1.21.8")));
        assertTrue(provider.supports(ServerVersion.parse("25.5")));
        assertTrue(provider.supports(ServerVersion.parse("26.0")));
        assertTrue(provider.supports(ServerVersion.parse("26.1")));
        assertFalse(provider.supports(ServerVersion.parse("26.2")));
        assertTrue(provider.capability(Capabilities.TEXT).isPresent());
        assertTrue(provider.capability(Capabilities.NBT).isPresent());
        assertTrue(provider.capability(Capabilities.MATERIAL).isPresent());
        assertTrue(provider.capability(Capabilities.INVENTORY).isPresent());
    }
}
