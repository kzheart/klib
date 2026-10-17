package me.kzheart.klib.scheduler;

import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskFailureTest {
    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDownExecutors() {
        timerExecutor.shutdownNow();
        asyncExecutor.shutdownNow();
    }

    @Test
    void failedTaskReportsFailureAndSkipsResultCallbacks() throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("failure");
        ExecutorScheduler scheduler = new ExecutorScheduler(
                scope, timerExecutor, asyncExecutor, Runnable::run);
        IllegalStateException boom = new IllegalStateException("boom");
        AtomicBoolean resultDelivered = new AtomicBoolean();
        AtomicReference<Throwable> observed = new AtomicReference<Throwable>();
        CountDownLatch failed = new CountDownLatch(1);

        AsyncTask<String> task = scheduler.<String>async(() -> {
            throw boom;
        });
        task.onError(failure -> {
            observed.set(failure);
            failed.countDown();
        });
        task.thenSync(value -> resultDelivered.set(true));

        assertTrue(failed.await(5L, TimeUnit.SECONDS));
        assertTrue(task.isDone());
        assertTrue(task.isFailed());
        assertFalse(task.isCancelled());
        assertTrue(observed.get() == boom);
        assertFalse(resultDelivered.get());
        scope.close();
    }

    @Test
    void errorCallbackRegisteredAfterFailureStillFires() throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("failure");
        ExecutorScheduler scheduler = new ExecutorScheduler(
                scope, timerExecutor, asyncExecutor, Runnable::run);

        AsyncTask<String> task = scheduler.<String>async(() -> {
            throw new IllegalStateException("boom");
        });
        awaitFailed(task);

        CountDownLatch late = new CountDownLatch(1);
        task.onError(failure -> late.countDown());

        assertTrue(late.await(5L, TimeUnit.SECONDS));
        scope.close();
    }

    private static void awaitFailed(AsyncTask<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!task.isFailed()) {
            assertTrue(System.nanoTime() < deadline, "task did not fail in time");
            Thread.sleep(10L);
        }
    }
}
