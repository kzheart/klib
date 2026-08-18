package me.kzheart.klib.scope;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeRebuildNoDuplicateTest {

    @Test
    void rebuildDisposesTheOldGraphBeforeReplayingConfiguration() {
        AtomicInteger activeResources = new AtomicInteger();
        AtomicInteger builds = new AtomicInteger();
        ScopeImpl root = ScopeImpl.create("root", scope -> {
            builds.incrementAndGet();
            activeResources.incrementAndGet();
            scope.install(activeResources::decrementAndGet);
        });

        root.rebuild();
        root.rebuild();

        assertEquals(3, builds.get());
        assertEquals(1, activeResources.get());
        root.close();
        assertEquals(0, activeResources.get());
    }

    @Test
    void capabilitiesAreInheritedAndRecreatedOnRebuild() {
        AtomicInteger generation = new AtomicInteger();
        ScopeImpl root = ScopeImpl.create("root", scope ->
                scope.registerCapability(CharSequence.class, "generation-" + generation.incrementAndGet()));
        Scope child = root.scope("child", scope -> { });

        assertEquals("generation-1", child.requireCapability(CharSequence.class));
        assertSame(root.findCapability(CharSequence.class).get(), child.findCapability(CharSequence.class).get());

        root.rebuild();

        assertEquals("generation-2", root.requireCapability(CharSequence.class));
        assertThrows(IllegalStateException.class, () -> root.registerCapability(CharSequence.class, "duplicate"));
    }
}
