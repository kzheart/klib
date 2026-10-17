package me.kzheart.klib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class KPluginRebuildFailureTest {
    @Test
    void replayFailureCleansResourcesAndTriggersDisableBoundary() {
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger disposed = new AtomicInteger();
        AtomicBoolean disabled = new AtomicBoolean();
        ScopeImpl scope = PluginScopeBootstrap.create("plugin", root -> {
            root.install(disposed::incrementAndGet);
            if (builds.incrementAndGet() == 2) {
                throw new IllegalStateException("rebuild failed");
            }
        });

        boolean rebuilt = PluginScopeBootstrap.rebuild(
                scope,
                failure -> {
                    disabled.set(true);
                    scope.close();
                });

        assertFalse(rebuilt);
        assertTrue(disabled.get());
        assertTrue(scope.isClosed());
        assertEquals(2, disposed.get());
    }
}
