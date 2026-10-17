package me.kzheart.klib.ui;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import java.util.function.BiFunction;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReactiveMenuSessionTest {
    @Test void statesArePerOpeningAndInvalidationsCoalesceOnTheNextTick() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger renders = new AtomicInteger();
            ReactiveMenuSession<Integer> first = open(f, f.player, 0, renders);
            Player another = f.player(java.util.UUID.randomUUID());
            ReactiveMenuSession<Integer> second = open(f, another, 40, new AtomicInteger());
            first.update(value -> value + 1);
            first.update(value -> value + 1);
            first.invalidate(); first.invalidate();
            assertEquals(1, f.queued());
            assertEquals(1, renders.get());
            assertEquals(Integer.valueOf(2), first.state());
            assertEquals(Integer.valueOf(40), second.state());
            f.tick();
            assertEquals(2, renders.get());
            first.update(value -> value);
            assertEquals(0, f.queued(), "equal immutable state does not schedule a render");
        }
    }

    @Test void supersedingAReadTerminatesItWithoutCancellingItsSource() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
            CompletableFuture<Integer> old = new CompletableFuture<Integer>();
            CompletableFuture<Integer> fresh = new CompletableFuture<Integer>();
            CompletionStage<Boolean> first = ui.readLatest("count", old, (state, value) -> value, failure -> fail(failure));
            CompletionStage<Boolean> second = ui.readLatest("count", fresh, (state, value) -> value, failure -> fail(failure));
            assertFalse(first.toCompletableFuture().join());
            assertFalse(old.isDone());
            assertNull(f.worker(() -> fresh.complete(3)));
            assertEquals(Integer.valueOf(0), ui.state(), "completion worker cannot touch UI state");
            f.tick();
            assertTrue(second.toCompletableFuture().join());
            assertEquals(Integer.valueOf(3), ui.state());
            old.complete(99); f.tick();
            assertEquals(Integer.valueOf(3), ui.state());
        }
    }

    @Test void callerCancellationImmediatelyReleasesWaitingAndQueuedReadReferences() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
            int baseline = resourceCount(ui.scope());
            for (boolean completedSource : new boolean[]{false, true}) {
                CompletableFuture<Integer> source = new CompletableFuture<Integer>();
                if (completedSource) source.complete(3);
                CompletableFuture<Boolean> result = ui.readLatest("cancel", source,
                        (state, value) -> fail("cancelled read applied"), failure -> fail(failure)).toCompletableFuture();
                Object read = reads(ui).get("cancel");
                AtomicReference<?> retainedDelivery = delivery(read);
                CompletableFuture<?> queued = (CompletableFuture<?>) retainedDelivery.get();
                assertNotNull(queued);
                assertTrue(resourceCount(ui.scope()) > baseline);
                assertNull(f.worker(() -> assertTrue(result.cancel(false))));
                assertTrue(result.isCancelled());
                assertTrue(reads(ui).isEmpty());
                assertNull(retainedDelivery.get(), "the UI's delivery reference is cleared immediately");
                assertTrue(queued.isCancelled());
                assertEquals(baseline, resourceCount(ui.scope()), "the managed delivery is detached from the scope");
                if (!completedSource) assertFalse(source.isDone(), "observation cancellation does not cancel a never-completing source");
                source.complete(99); f.tick();
                assertEquals(Integer.valueOf(0), ui.state());
            }
        }
    }

    @Test void cancellationBeforeDeliveryAssignmentCannotRetainTheLaterDelivery() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
            int baseline = resourceCount(ui.scope());
            AtomicReference<Object> readReference = new AtomicReference<Object>();
            CompletableFuture<Integer> source = new CompletableFuture<Integer>() {
                @Override public <U> CompletableFuture<U> handle(BiFunction<? super Integer, Throwable, ? extends U> function) {
                    // A deterministic reentrant CompletionStage: cancel the observation after it
                    // entered the map, but before Scope.submitSync has returned its delivery.
                    try {
                        Object read = reads(ui).get("early");
                        readReference.set(read);
                        Field field = read.getClass().getDeclaredField("result");
                        field.setAccessible(true);
                        assertTrue(((CompletableFuture<?>) field.get(read)).cancel(false));
                        assertNull(delivery(read).get());
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                    return super.handle(function);
                }
            };
            CompletableFuture<Boolean> result = ui.readLatest("early", source,
                    (state, value) -> fail("cancelled early read applied"), failure -> fail(failure)).toCompletableFuture();
            assertTrue(result.isCancelled());
            assertTrue(reads(ui).isEmpty());
            assertNull(delivery(readReference.get()).get());
            assertEquals(baseline, resourceCount(ui.scope()));
            assertFalse(source.isDone());
            source.complete(4); f.tick();
            assertEquals(Integer.valueOf(0), ui.state());
        }
    }

    @Test void stateChangesInvalidateEvenDifferentlyKeyedOrAlreadyQueuedReads() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
            CompletableFuture<Integer> source = new CompletableFuture<Integer>();
            CompletionStage<Boolean> result = ui.readLatest("page", source, (state, value) -> value, failure -> fail(failure));
            source.complete(2);
            ui.update(state -> 10);
            assertFalse(result.toCompletableFuture().join());
            f.tick();
            assertEquals(Integer.valueOf(10), ui.state());
        }
    }

    @Test void closeReopenReleasesSubscriptionsAndNeverRevivesOldState() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger released = new AtomicInteger();
            ReactiveMenuSession<Integer> old = open(f, f.player, 0, new AtomicInteger());
            old.bind(released::incrementAndGet);
            CompletableFuture<Integer> source = new CompletableFuture<Integer>();
            CompletionStage<Boolean> pending = old.readLatest("count", source, (state, value) -> value, failure -> fail(failure));
            old.invalidate();
            ReactiveMenuSession<Integer> fresh = open(f, f.player, 7, new AtomicInteger());
            assertEquals(1, released.get());
            assertTrue(old.holder().session().isClosed());
            assertFalse(pending.toCompletableFuture().join());
            assertFalse(source.isCancelled());
            source.complete(50); old.invalidate(); f.tick();
            assertEquals(Integer.valueOf(7), fresh.state());
            assertThrows(IllegalStateException.class, () -> old.update(value -> value + 1));
            assertEquals(CloseReason.REPLACED, old.holder().close(CloseReason.PLAYER).reason());
        }
    }

    @Test void parentCloseAndRebuildTerminatePendingObservations() throws Exception {
        for (boolean rebuild : new boolean[]{false, true}) {
            try (MenuTestEnvironment f = new MenuTestEnvironment()) {
                AtomicInteger released = new AtomicInteger();
                ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
                ui.bind(released::incrementAndGet);
                CompletableFuture<Integer> source = new CompletableFuture<Integer>();
                CompletionStage<Boolean> result = ui.readLatest("pending", source, (state, value) -> value, failure -> fail(failure));
                if (rebuild) f.scope.rebuild(); else f.scope.close();
                assertFalse(result.toCompletableFuture().join());
                assertEquals(1, released.get());
                assertFalse(source.isDone());
                source.complete(5); f.tick();
            }
        }
    }

    @Test void reconnectWithSameUuidCannotConsumeOldPlayerResults() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> old = open(f, f.player, 0, new AtomicInteger());
            CompletableFuture<Integer> source = new CompletableFuture<Integer>();
            AtomicInteger applied = new AtomicInteger();
            CompletionStage<Boolean> result = old.readLatest("read", source, (state, value) -> { applied.incrementAndGet(); return value; }, failure -> fail(failure));
            Player reconnected = f.player(f.player.getUniqueId());
            open(f, reconnected, 9, new AtomicInteger());
            source.complete(3); f.tick();
            assertFalse(result.toCompletableFuture().join());
            assertEquals(0, applied.get());
            assertFalse(old.isCurrent());
        }
    }

    @Test void quitReleasesOldConnectionWithoutClosingAReconnectedPlayer() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> old = open(f, f.player, 0, new AtomicInteger());
            AtomicInteger released = new AtomicInteger();
            old.bind(released::incrementAndGet);
            CompletionStage<Boolean> result = old.readLatest("never", new CompletableFuture<Integer>(),
                    (state, value) -> value, failure -> fail(failure));
            Player reconnected = f.player(f.player.getUniqueId());
            ReactiveMenuSession<Integer> fresh = open(f, reconnected, 1, new AtomicInteger());
            f.menus.onQuit(new PlayerQuitEvent(f.player, "quit"));
            assertFalse(result.toCompletableFuture().join());
            assertEquals(1, released.get());
            assertTrue(fresh.isCurrent());
        }
    }

    @Test void navigationWaitsATickAndDropsDuplicateOrStaleActions() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
            AtomicInteger navigated = new AtomicInteger();
            assertTrue(ui.navigate(navigated::incrementAndGet));
            assertFalse(ui.navigate(navigated::incrementAndGet));
            assertEquals(0, navigated.get());
            f.tick(); assertEquals(1, navigated.get());
            assertTrue(ui.navigate(navigated::incrementAndGet));
            ui.update(value -> value + 1);
            f.tick(); assertEquals(1, navigated.get());
            ui.navigate(navigated::incrementAndGet);
            open(f, f.player, 5, new AtomicInteger());
            f.tick(); assertEquals(1, navigated.get());
        }
    }

    @Test void onlyInvalidateCanBeCalledOffTheServerThread() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger renders = new AtomicInteger();
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, renders);
            assertInstanceOf(IllegalStateException.class, f.worker(ui::state));
            assertInstanceOf(IllegalStateException.class, f.worker(() -> ui.update(value -> value + 1)));
            assertInstanceOf(IllegalStateException.class, f.worker(() -> ui.navigate(() -> { })));
            assertInstanceOf(IllegalStateException.class, f.worker(ui.holder()::refresh));
            assertNull(f.worker(() -> { ui.invalidate(); ui.invalidate(); }));
            f.tick(); assertEquals(2, renders.get());
        }
    }

    @Test void failuresAreObservedOnTheServerThreadAndRejectedDispatchTerminates() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
            AtomicReference<Throwable> reported = new AtomicReference<Throwable>();
            CompletableFuture<Integer> source = new CompletableFuture<Integer>();
            CompletionStage<Boolean> result = ui.readLatest("read", source, (state, value) -> value, failure -> {
                assertSame(f.serverThread, Thread.currentThread()); reported.set(failure);
            });
            IllegalArgumentException failure = new IllegalArgumentException("read failed");
            f.worker(() -> source.completeExceptionally(failure));
            assertNull(reported.get()); f.tick();
            assertSame(failure, reported.get());
            assertSame(failure, assertThrows(CompletionException.class, () -> result.toCompletableFuture().join()).getCause());
            f.rejectScheduling = true;
            CompletionStage<Boolean> rejected = ui.readLatest("reject", CompletableFuture.completedFuture(4),
                    (state, value) -> value, ignored -> { });
            assertTrue(rejected.toCompletableFuture().isCompletedExceptionally());
        }
    }

    @Test void viewFailureClosesTheInventoryAndReducerFailureTerminatesItsResult() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, "broken-view", 0, session -> {
                if (session.state() > 0) throw new IllegalArgumentException("broken model");
                return empty();
            });
            CompletionStage<Boolean> failed = ui.readLatest("bad-reducer", CompletableFuture.completedFuture(1),
                    (state, value) -> { throw new IllegalStateException("bad reducer"); }, failure -> fail(failure));
            f.tick();
            assertTrue(failed.toCompletableFuture().isCompletedExceptionally());
            assertEquals(Integer.valueOf(0), ui.state());
            ui.update(state -> 1);
            assertThrows(IllegalArgumentException.class, f::tick);
            assertTrue(ui.holder().session().isClosed());
            assertEquals(CloseReason.RENDER_FAILED, ui.holder().close(CloseReason.PLAYER).reason());
        }
    }

    @Test void invalidStatesAndRenderSideEffectsFailExplicitly() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            assertThrows(NullPointerException.class, () -> f.menus.openReactive(f.player, "null", null, ui -> empty()));
            assertThrows(NullPointerException.class, () -> f.menus.openReactive(f.player, "null-view", 0, ui -> null));
            assertThrows(IllegalStateException.class, () -> f.menus.openReactive(f.player, "bad-view", 0, ui -> {
                ui.update(value -> 1); return empty();
            }));
            ReactiveMenuSession<Integer> ui = open(f, f.player, 0, new AtomicInteger());
            assertThrows(NullPointerException.class, () -> ui.update(value -> null));
            assertThrows(IllegalStateException.class, () -> ui.update(value -> { ui.update(v -> 1); return value; }));
            assertThrows(IllegalArgumentException.class, () -> ui.readLatest(" ", CompletableFuture.completedFuture(1), (state, value) -> value, ignored -> { }));
            assertEquals(Integer.valueOf(0), ui.state());
        }
    }

    private static Map<?, ?> reads(ReactiveMenuSession<?> ui) throws ReflectiveOperationException {
        Field field = ReactiveMenuSession.class.getDeclaredField("reads");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(ui);
    }

    private static AtomicReference<?> delivery(Object read) throws ReflectiveOperationException {
        Field field = read.getClass().getDeclaredField("delivery");
        field.setAccessible(true);
        return (AtomicReference<?>) field.get(read);
    }

    private static int resourceCount(Scope scope) throws ReflectiveOperationException {
        Field field = ScopeImpl.class.getDeclaredField("resources");
        field.setAccessible(true);
        return ((List<?>) field.get(scope)).size();
    }

    private static ReactiveMenuSession<Integer> open(MenuTestEnvironment f, Player player, int initial, AtomicInteger renders) {
        return f.menus.openReactive(player, "counter", Integer.valueOf(initial), ui -> {
            assertSame(f.serverThread, Thread.currentThread()); renders.incrementAndGet(); return empty();
        });
    }
    private static MenuModel empty() { return MenuCompiler.compile(MenuTemplate.builder("Counter", 1).build()); }
}
