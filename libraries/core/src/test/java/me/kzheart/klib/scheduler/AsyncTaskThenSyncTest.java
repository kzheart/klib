package me.kzheart.klib.scheduler;

import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskThenSyncTest {
    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "klib-sync-test")
    );

    @AfterEach
    void shutDownExecutors() {
        timerExecutor.shutdownNow();
        asyncExecutor.shutdownNow();
        syncExecutor.shutdownNow();
    }

    @Test
    void thenSyncReceivesAsyncResultOnSyncExecutor() throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("test");
        ExecutorScheduler scheduler = new ExecutorScheduler(scope, timerExecutor, asyncExecutor, syncExecutor);
        AtomicReference<String> callbackThread = new AtomicReference<String>();
        AtomicReference<Integer> result = new AtomicReference<Integer>();
        CountDownLatch callback = new CountDownLatch(1);

        AsyncTask<Integer> task = scheduler.async(() -> 21 * 2).thenSync(value -> {
            result.set(value);
            callbackThread.set(Thread.currentThread().getName());
            callback.countDown();
        });

        assertTrue(callback.await(5L, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(42), result.get());
        assertEquals("klib-sync-test", callbackThread.get());
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
        scope.close();
    }
}
