package me.kzheart.klib.scope;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import me.kzheart.klib.scheduler.AsyncTasks;
import me.kzheart.klib.scheduler.ExecutorScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScopeFutureTest {
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();

    @AfterEach
    void shutdown() {
        timer.shutdownNow();
        workers.shutdownNow();
    }

    private ScopeImpl scope(Executor executor) {
        return ScopeImpl.create("results", owner -> owner.registerCapability(SchedulerFactory.class,
                bound -> new ExecutorScheduler(bound, timer, workers, executor)));
    }

    private void drain() {
        Runnable next;
        while ((next = queue.poll()) != null) next.run();
    }

    @Test
    void submitAfterCloseReturnsCancelledResultWithoutAccessingCapabilities() {
        ScopeImpl scope = scope(queue::add);
        scope.close();
        CompletableFuture<Integer> result = scope.submitSync(() -> fail("closed callback ran"));
        assertTrue(result.isCancelled());
        assertTrue(queue.isEmpty());
    }

    @Test
    void queuedCloseCancelsResultAndNeverRunsBusinessCallbackOnClosingThread() {
        ScopeImpl scope = scope(queue::add);
        CompletableFuture<Integer> result = scope.submitSync(() -> fail("cancelled callback ran"));
        assertFalse(result.isDone());
        scope.close();
        assertTrue(result.isCancelled());
        drain();
        assertEquals(0, scope.resourceCount());
    }

    @Test
    void waitingSourceIsCancelledWithoutCancellingOrRollingBackExternalWork() {
        ScopeImpl scope = scope(queue::add);
        CompletableFuture<Integer> source = new CompletableFuture<Integer>();
        CompletableFuture<Integer> result = scope.submitSync(source, value -> fail("late callback ran"));
        scope.close();
        assertTrue(result.isCancelled());
        assertFalse(source.isDone());
        source.complete(42);
        assertEquals(Integer.valueOf(42), source.join());
        assertTrue(queue.isEmpty());
    }

    @Test
    void rebuiltScopeCannotReviveAnOldWaitingOrQueuedResult() {
        ScopeImpl scope = scope(queue::add);
        CompletableFuture<Integer> source = new CompletableFuture<Integer>();
        CompletableFuture<Integer> waiting = scope.submitSync(source, value -> fail("old wait ran"));
        CompletableFuture<Integer> queued = scope.submitSync(() -> fail("old queue ran"));
        scope.rebuild();
        source.complete(1);
        CompletableFuture<Integer> fresh = scope.submitSync(() -> 3);
        drain();
        assertTrue(waiting.isCancelled());
        assertTrue(queued.isCancelled());
        assertEquals(Integer.valueOf(3), fresh.join());
        assertEquals(0, scope.resourceCount());
        scope.close();
    }

    @Test
    void sourceAndCallbackFailuresTerminateAndReleaseResources() {
        ScopeImpl scope = scope(queue::add);
        IllegalArgumentException failure = new IllegalArgumentException("source");
        CompletableFuture<Integer> source = new CompletableFuture<Integer>();
        CompletableFuture<Integer> failedSource = scope.submitSync(source, value -> fail("failed source ran"));
        source.completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, failedSource::join).getCause());
        assertTrue(queue.isEmpty());
        CompletableFuture<Integer> failedCallback = scope.submitSync(() -> { throw failure; });
        drain();
        assertSame(failure, assertThrows(CompletionException.class, failedCallback::join).getCause());
        assertEquals(0, scope.resourceCount());
        scope.close();
    }

    @Test
    void backendRejectionAndMissingSchedulerReturnFailedResults() {
        RejectedExecutionException rejection = new RejectedExecutionException("queue full");
        ScopeImpl scope = scope(command -> { throw rejection; });
        CompletableFuture<Integer> result = scope.submitSync(() -> 1);
        assertSame(rejection, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(0, scope.resourceCount());
        scope.close();
        ScopeImpl unconfigured = new ScopeImpl("no-scheduler");
        assertTrue(unconfigured.submitSync(() -> 1).isCompletedExceptionally());
        assertEquals(0, unconfigured.resourceCount());
        unconfigured.close();
    }

    @Test
    void externalCancellationReleasesWaitingAndQueuedResultsWithoutTouchingSource() {
        ScopeImpl scope = scope(queue::add);
        CompletableFuture<Integer> source = new CompletableFuture<Integer>();
        CompletableFuture<Integer> waiting = scope.submitSync(source, value -> fail("cancelled wait ran"));
        CompletableFuture<Integer> queued = scope.submitSync(() -> fail("cancelled queue ran"));
        assertTrue(waiting.cancel(true));
        assertTrue(queued.cancel(true));
        assertFalse(source.isDone());
        source.complete(1);
        drain();
        assertTrue(waiting.isCancelled());
        assertTrue(queued.isCancelled());
        assertEquals(0, scope.resourceCount());
        scope.close();
    }

    @Test
    void callerCompletionAndObtrusionDetachUnfinishedSourcesAndQueuedPayloads() throws Exception {
        verifyCallerCompletion(result -> result.complete(12));
        verifyCallerCompletion(result -> result.completeExceptionally(new IllegalStateException("caller")));
        verifyCallerCompletion(result -> result.obtrudeValue(12));
        verifyCallerCompletion(result -> result.obtrudeException(new IllegalStateException("caller")));
        verifyCallerCompletion(result -> result.cancel(false));
    }

    private void verifyCallerCompletion(Consumer<CompletableFuture<Integer>> finish) throws Exception {
        ScopeImpl scope = scope(queue::add);
        CompletableFuture<Integer> source = new CompletableFuture<Integer>();
        CompletableFuture<Integer> waiting = scope.submitSync(source, value -> fail("detached callback ran"));
        CompletableFuture<Integer> queued = scope.submitSync(() -> fail("detached queue callback ran"));
        // Inspect only our own retention cell, never any JDK implementation state.
        Field cellField = ScopedFuture.class.getDeclaredField("pending");
        cellField.setAccessible(true);
        AtomicReference<?> waitingCell = (AtomicReference<?>) cellField.get(waiting);
        AtomicReference<?> queuedCell = (AtomicReference<?>) cellField.get(queued);
        assertNotNull(waitingCell.get());
        finish.accept(waiting);
        finish.accept(queued);
        assertTrue(waiting.isDone());
        assertTrue(queued.isDone());
        assertNull(waitingCell.get());
        assertNull(queuedCell.get());
        assertFalse(source.isDone());
        assertEquals(0, scope.resourceCount());
        source.complete(42);
        drain();
        scope.close();
    }

    @Test
    void externalCancellationOfRunningWorkDoesNotInterruptTheCallback() throws Exception {
        ScopeImpl scope = scope(queue::add);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        CompletableFuture<Integer> result = scope.submitSync(() -> {
            entered.countDown();
            await(release);
            finished.set(true);
            return 7;
        });
        result.whenComplete((value, failure) -> { if (result.isCancelled()) cancelled.countDown(); });
        Future<?> dispatch = workers.submit(queue.remove());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Future<?> cancellation = workers.submit(() -> result.cancel(true));
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        release.countDown();
        dispatch.get(5, TimeUnit.SECONDS);
        cancellation.get(5, TimeUnit.SECONDS);
        assertTrue(result.isCancelled());
        assertTrue(finished.get());
        assertEquals(0, scope.resourceCount());
        scope.close();
    }

    @Test
    void completedResultSurvivesCloseAndInlineExecutionDoesNotLeakHandles() {
        ScopeImpl scope = scope(Runnable::run);
        CompletableFuture<Integer> result = scope.submitSync(() -> 42);
        assertEquals(Integer.valueOf(42), result.join());
        assertEquals(0, scope.resourceCount());
        scope.close();
        scope.close();
        assertFalse(result.isCancelled());
    }

    @Test
    void runningMainCallbackFinishesBeforeCloseAndIsNotInterrupted() throws Exception {
        ScopeImpl scope = scope(queue::add);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CompletableFuture<Integer> result = scope.submitSync(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { interrupted.set(true); throw new AssertionError(failure); }
            return 7;
        });
        Future<?> dispatch = workers.submit(queue.remove());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch closeStarted = new CountDownLatch(1);
        Future<?> closing = workers.submit(() -> { closeStarted.countDown(); scope.close(); });
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
        assertFalse(closing.isDone());
        release.countDown();
        dispatch.get(5, TimeUnit.SECONDS);
        closing.get(5, TimeUnit.SECONDS);
        assertEquals(Integer.valueOf(7), result.join());
        assertFalse(interrupted.get());
    }

    @Test
    void asyncTasksCancelEvenWhenSourceNeverCompletesOrFailsAfterClose() {
        ScopeImpl scope = scope(queue::add);
        CompletableFuture<Integer> source = new CompletableFuture<Integer>();
        CompletableFuture<Integer> then = AsyncTasks.thenSync(source, scope,
                value -> fail("closed success"), failure -> fail("closed failure")).toCompletableFuture();
        CompletableFuture<Integer> on = AsyncTasks.onSync(source, scope).toCompletableFuture();
        scope.close();
        assertTrue(then.isCancelled());
        assertTrue(on.isCancelled());
        source.completeExceptionally(new IllegalStateException("late"));
        drain();
        assertTrue(then.isCancelled());
        assertTrue(on.isCancelled());
    }

    @Test
    void asyncTasksKeepSourceFailureWhenErrorCallbackAlsoFails() {
        ScopeImpl scope = scope(queue::add);
        IllegalStateException sourceFailure = new IllegalStateException("source");
        IllegalArgumentException callbackFailure = new IllegalArgumentException("callback");
        CompletableFuture<Integer> source = new CompletableFuture<Integer>();
        CompletableFuture<Integer> result = AsyncTasks.thenSync(source, scope, value -> fail("success"),
                failure -> { assertSame(sourceFailure, failure); throw callbackFailure; }).toCompletableFuture();
        source.completeExceptionally(sourceFailure);
        drain();
        assertSame(sourceFailure, AsyncTasks.unwrap(assertThrows(CompletionException.class, result::join)));
        assertArrayEquals(new Throwable[]{callbackFailure}, sourceFailure.getSuppressed());
        assertEquals(0, scope.resourceCount());
        scope.close();
    }

    @Test
    void completionCloseAndDispatchRacesAlwaysReachATerminalState() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            ScopeImpl scope = scope(queue::add);
            CompletableFuture<Integer> source = new CompletableFuture<Integer>();
            AtomicInteger callbacks = new AtomicInteger();
            CompletableFuture<Integer> result = scope.submitSync(source, value -> {
                assertFalse(scope.isClosed());
                callbacks.incrementAndGet();
                return value;
            });
            CountDownLatch start = new CountDownLatch(1);
            Future<?> completion = workers.submit(() -> { await(start); source.complete(1); drain(); });
            Future<?> closing = workers.submit(() -> { await(start); scope.close(); });
            start.countDown();
            completion.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            drain();
            assertTrue(result.isDone());
            assertTrue(result.isCancelled() || result.join() == 1);
            assertTrue(callbacks.get() <= 1);
            assertEquals(0, scope.resourceCount());
        }
    }

    @Test
    void opaqueExecutorStillCannotFinishAlreadyAcceptedJdkStages() {
        ScopeImpl scope = scope(queue::add);
        Executor executor = scope.syncExecutor();
        CompletableFuture<Integer> queued = CompletableFuture.completedFuture(1)
                .thenApplyAsync(value -> fail("opaque callback ran"), executor);
        scope.close();
        drain();
        // Deliberate compatibility limit: only submitSync owns the future it must terminate.
        assertFalse(queued.isDone());
        CompletableFuture<Integer> rejected = CompletableFuture.completedFuture(1)
                .thenApplyAsync(value -> fail("closed opaque callback ran"), executor);
        assertTrue(rejected.isCompletedExceptionally());
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, rejected::join).getCause());
        queued.cancel(false);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
}
