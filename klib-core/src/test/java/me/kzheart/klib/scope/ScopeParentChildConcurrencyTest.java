package me.kzheart.klib.scope;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScopeParentChildConcurrencyTest {
    @Test
    void parentAndChildCloseCannotInvertLifecycleLocks() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (int iteration = 0; iteration < 100; iteration++) {
                ScopeImpl parent = new ScopeImpl("parent-" + iteration);
                Scope child = parent.scope("child", scope -> scope.install(
                        () -> parent.install(() -> { })));
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(2);
                try {
                    Future<?> childClose = executor.submit(() -> closeAfter(start, child));
                    Future<?> parentClose = executor.submit(() -> closeAfter(start, parent));
                    start.countDown();
                    awaitLifecycle(childClose);
                    awaitLifecycle(parentClose);
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                }
            }
        });
    }

    private static void closeAfter(CountDownLatch start, Scope scope) {
        try {
            start.await();
            scope.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitLifecycle(Future<?> future) throws InterruptedException {
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException expectedLifecycleFailure) {
            assertTrue(expectedLifecycleFailure.getCause() instanceof RuntimeException);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new AssertionError("Scope lifecycle deadlocked", timeout);
        }
    }
}
