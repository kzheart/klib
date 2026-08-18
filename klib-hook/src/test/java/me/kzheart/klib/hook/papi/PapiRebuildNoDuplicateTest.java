package me.kzheart.klib.hook.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class PapiRebuildNoDuplicateTest {

    @Test
    void rebuildAlwaysDisposesTheOldExpansionBeforeRegisteringTheNext() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        ScopeImpl scope = ScopeImpl.create("papi", current -> Papi.register(
                current,
                registrar,
                "gather",
                expansion -> expansion
                        .key("points", player -> Integer.valueOf(12))
                        .prefixed("top_", (player, suffix) -> suffix)));

        assertEquals(1, registrar.active.get());
        scope.rebuild();
        scope.rebuild();
        scope.rebuild();

        assertEquals(1, registrar.active.get());
        assertEquals(4, registrar.registrations.get());
        assertEquals("12", registrar.latest.resolve(null, "points"));
        assertEquals("daily", registrar.latest.resolve(null, "top_daily"));
        assertNull(registrar.latest.resolve(null, "unknown"));

        scope.close();
        assertEquals(0, registrar.active.get());
    }

    @Test
    void cachedPlaceholderAcceptsNullPlayerAndHonoursTtl() {
        AtomicInteger calls = new AtomicInteger();
        PapiDsl dsl = new PapiDsl().keyCached(
                "count",
                Duration.ofSeconds(1),
                player -> Integer.valueOf(calls.incrementAndGet()));
        PapiExpansion expansion = dsl.build();

        assertEquals("1", expansion.resolve(null, "count"));
        assertEquals("1", expansion.resolve(null, "count"));
        assertEquals(1, calls.get());
    }

    private static final class RecordingRegistrar implements PapiRegistrar {
        private final AtomicInteger registrations = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private PapiExpansion latest;

        @Override
        public me.kzheart.klib.scope.Disposable register(
                String identifier,
                PapiExpansion expansion
        ) {
            registrations.incrementAndGet();
            active.incrementAndGet();
            latest = expansion;
            AtomicInteger disposed = new AtomicInteger();
            return () -> {
                if (disposed.compareAndSet(0, 1)) {
                    active.decrementAndGet();
                }
            };
        }
    }
}
