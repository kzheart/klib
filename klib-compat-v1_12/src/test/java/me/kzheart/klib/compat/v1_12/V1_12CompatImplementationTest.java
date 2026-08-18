package me.kzheart.klib.compat.v1_12;

import org.junit.jupiter.api.Test;

import me.kzheart.klib.compat.Capabilities;
import me.kzheart.klib.compat.ServerVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1_12CompatImplementationTest {
    @Test
    void identifiesSupportedServerAndCapabilities() {
        V1_12CompatImplementation implementation = new V1_12CompatImplementation();

        assertEquals("compat-v1_12", implementation.id());
        assertEquals(ServerVersion.of(1, 12, 2), implementation.version());
        assertTrue(implementation.capability(Capabilities.TEXT).isPresent());
        assertTrue(implementation.capability(Capabilities.NBT).isPresent());
        assertTrue(implementation.capability(Capabilities.MATERIAL).isPresent());
        assertTrue(implementation.capability(Capabilities.INVENTORY).isPresent());
    }
}
