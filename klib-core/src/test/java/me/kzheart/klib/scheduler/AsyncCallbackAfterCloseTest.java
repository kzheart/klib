package me.kzheart.klib.scheduler;

import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCallbackAfterCloseTest {
    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDownExecutors() {
        timerExecutor.shutdownNow();
        asyncExecutor.shutdownNow();
    }

    @RepeatedTest(20)
    void queuedCallbackDoesNotRunAfterOwningScopeCloses() throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("test");
        QueuedExecutor syncExecutor = new QueuedExecutor();
        ExecutorScheduler scheduler = new ExecutorScheduler(scope, timerExecutor, asyncExecutor, syncExecutor);
        AtomicBoolean called = new AtomicBoolean();

        AsyncTask<String> task = scheduler.async(() -> "ready").thenSync(value -> called.set(true));

        assertTrue(syncExecutor.awaitSubmission());
        scope.close();
        syncExecutor.runAll();

        assertTrue(task.isCancelled());
        assertFalse(called.get());
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<Runnable>();
        private final CountDownLatch submitted = new CountDownLatch(1);

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
            submitted.countDown();
        }

        private boolean awaitSubmission() throws InterruptedException {
            return submitted.await(5L, TimeUnit.SECONDS);
        }

        private void runAll() {
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = tasks.poll();
                }
                if (task == null) {
                    return;
                }
                task.run();
            }
        }
    }
}
