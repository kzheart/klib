package me.kzheart.klib.scope;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeLifecycleFailureTest {

    @Test
    void rebuildTeardownFailureLeavesScopeClosed() {
        ScopeImpl root = ScopeImpl.create("root", scope -> scope.install(() -> {
            throw new IllegalStateException("teardown failed");
        }));

        assertThrows(ScopeLifecycleException.class, root::rebuild);

        assertTrue(root.isClosed());
        assertThrows(IllegalStateException.class, () -> root.install(() -> { }));
    }

    @Test
    void closedChildDetachesFromParentAndCanBeRecreated() {
        AtomicInteger childDisposals = new AtomicInteger();
        ScopeImpl root = new ScopeImpl("root");
        Scope child = root.scope("child", scope -> scope.install(childDisposals::incrementAndGet));

        child.close();
        assertEquals(1, childDisposals.get());

        Scope recreated = root.scope("child", scope -> scope.install(childDisposals::incrementAndGet));
        assertNotNull(recreated);

        root.close();
        assertEquals(2, childDisposals.get());
    }

    @Test
    void propagatePreservesTheOriginalCheckedCause() {
        Exception original = new Exception("checked failure");
        ScopeLifecycleException failure = assertThrows(
                ScopeLifecycleException.class,
                () -> ScopeImpl.create("root", scope -> sneakyThrow(original)));

        assertEquals(original, failure.getCause());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
