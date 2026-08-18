package me.kzheart.klib.scheduler;

import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerSyncDispatchTest {
    private static final String SYNC_THREAD = "klib-sync-test";

    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, SYNC_THREAD)
    );

    @AfterEach
    void shutDownExecutors() {
        timerExecutor.shutdownNow();
        asyncExecutor.shutdownNow();
        syncExecutor.shutdownNow();
    }

    private ScopeImpl newScope() {
        ScopeImpl scope = new ScopeImpl("test");
        ExecutorScheduler scheduler =
                new ExecutorScheduler(scope, timerExecutor, asyncExecutor, syncExecutor);
        scope.registerCapability(SchedulerFactory.class, ignored -> scheduler);
        return scope;
    }

    @Test
    void schedulerDiagnosticSnapshotOnlyReadsCurrentExecutorState() {
        ScopeImpl scope = new ScopeImpl("diagnostic-scheduler");
        ExecutorScheduler scheduler =
                new ExecutorScheduler(scope, timerExecutor, asyncExecutor, syncExecutor);

        java.util.Map<String, ?> snapshot = scheduler.diagnosticSnapshot();

        assertEquals("executor", snapshot.get("backend"));
        assertEquals("diagnostic-scheduler", snapshot.get("scope"));
        assertEquals(Boolean.FALSE, snapshot.get("scope_closed"));
        scope.close();
    }

    @Test
    void syncRunsTaskOnSyncExecutorNotTimerThread() throws InterruptedException {
        ScopeImpl scope = newScope();
        AtomicReference<String> thread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(1);

        TaskHandle handle = scope.sync(() -> {
            thread.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertTrue(done.await(5L, TimeUnit.SECONDS));
        assertEquals(SYNC_THREAD, thread.get());
        assertFalse(handle.isCancelled());
        scope.close();
    }

    @Test
    void syncExecutorDrivesCompletionStageCallbackOnSyncExecutor() throws InterruptedException {
        ScopeImpl scope = newScope();
        AtomicReference<String> thread = new AtomicReference<String>();
        AtomicReference<String> value = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(1);

        Executor executor = scope.syncExecutor();
        CompletableFuture.supplyAsync(() -> "profile", asyncExecutor)
                .thenAcceptAsync(loaded -> {
                    value.set(loaded);
                    thread.set(Thread.currentThread().getName());
                    done.countDown();
                }, executor);

        assertTrue(done.await(5L, TimeUnit.SECONDS));
        assertEquals("profile", value.get());
        assertEquals(SYNC_THREAD, thread.get());
        scope.close();
    }

    @Test
    void thenSyncBridgesCompletionStageResultToSyncExecutor() throws InterruptedException {
        ScopeImpl scope = newScope();
        AtomicReference<String> thread = new AtomicReference<String>();
        AtomicReference<Integer> value = new AtomicReference<Integer>();
        CountDownLatch done = new CountDownLatch(1);

        AsyncTasks.thenSync(
                CompletableFuture.supplyAsync(() -> 42, asyncExecutor),
                scope,
                loaded -> {
                    value.set(loaded);
                    thread.set(Thread.currentThread().getName());
                    done.countDown();
                });

        assertTrue(done.await(5L, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(42), value.get());
        assertEquals(SYNC_THREAD, thread.get());
        scope.close();
    }

    @Test
    void thenSyncReportsUnwrappedFailureAndSkipsValueCallback() throws InterruptedException {
        ScopeImpl scope = newScope();
        IllegalStateException failure = new IllegalStateException("boom");
        AtomicBoolean valueSeen = new AtomicBoolean();
        AtomicReference<Throwable> reported = new AtomicReference<Throwable>();
        AtomicReference<String> thread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(1);

        CompletableFuture<String> stage = new CompletableFuture<String>();
        AsyncTasks.thenSync(
                stage,
                scope,
                loaded -> valueSeen.set(true),
                error -> {
                    reported.set(error);
                    thread.set(Thread.currentThread().getName());
                    done.countDown();
                });
        stage.completeExceptionally(failure);

        assertTrue(done.await(5L, TimeUnit.SECONDS));
        assertSame(failure, reported.get());
        assertEquals(SYNC_THREAD, thread.get());
        assertFalse(valueSeen.get());
        scope.close();
    }

    @Test
    void syncExecutorDropsCommandsAfterScopeCloses() throws InterruptedException {
        ScopeImpl scope = newScope();
        Executor executor = scope.syncExecutor();
        scope.close();

        AtomicBoolean executed = new AtomicBoolean();
        executor.execute(() -> executed.set(true));

        CountDownLatch drained = new CountDownLatch(1);
        syncExecutor.execute(drained::countDown);
        assertTrue(drained.await(5L, TimeUnit.SECONDS));
        assertFalse(executed.get());
    }
}
