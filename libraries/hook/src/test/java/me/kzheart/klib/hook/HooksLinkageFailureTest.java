package me.kzheart.klib.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class HooksLinkageFailureTest {

    @Test
    void detectorLinkageErrorResolvesToFailedNoopHook() {
        Hook<String> hook = Hooks.orNoop("Vault", () -> {
            throw new NoClassDefFoundError("net/milkbowl/vault/economy/Economy");
        }, "noop");

        assertFalse(hook.available());
        assertEquals(DependencyStatus.FAILED, hook.status());
        assertEquals("noop", hook.value());
    }

    @Test
    void detectorRuntimeExceptionStillResolvesToFailed() {
        Hook<String> hook = Hooks.orNoop("Vault", () -> {
            throw new IllegalStateException("boom");
        }, "noop");

        assertEquals(DependencyStatus.FAILED, hook.status());
        assertEquals("noop", hook.value());
    }
}
