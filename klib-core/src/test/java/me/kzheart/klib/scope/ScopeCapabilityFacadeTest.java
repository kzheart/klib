package me.kzheart.klib.scope;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ScopeCapabilityFacadeTest {
    @Test
    void optionalModulePrimitivesNameTheMissingModule() {
        Scope scope = new ScopeImpl("facade-test");

        IllegalStateException commandFailure = assertThrows(
                IllegalStateException.class,
                () -> scope.command("test", spec -> { }));
        IllegalStateException configFailure = assertThrows(
                IllegalStateException.class,
                () -> scope.config(Object.class, "config.yml"));

        assertTrue(commandFailure.getMessage().contains("klib-command"));
        assertTrue(configFailure.getMessage().contains("klib-config"));
    }
}
