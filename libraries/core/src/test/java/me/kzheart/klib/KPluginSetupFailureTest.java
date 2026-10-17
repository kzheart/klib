package me.kzheart.klib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.Scope;
import org.junit.jupiter.api.Test;

class KPluginSetupFailureTest {
    @Test
    void setupFailureDisposesInstalledResources() {
        AtomicInteger disposed = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> PluginScopeBootstrap.create(
                "test-plugin",
                scope -> {
                    scope.install(disposed::incrementAndGet);
                    throw new IllegalStateException("setup failed");
                }));

        assertEquals(1, disposed.get());
    }
}
