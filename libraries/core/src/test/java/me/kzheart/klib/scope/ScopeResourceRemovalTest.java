package me.kzheart.klib.scope;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.BukkitSchedulerAdapter;
import me.kzheart.klib.scheduler.ExecutorScheduler;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeResourceRemovalTest {
    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDownExecutors() {
        timerExecutor.shutdownNow();
        asyncExecutor.shutdownNow();
    }

    @Test
    void removedResourceIsNotDisposedOnClose() {
        AtomicBoolean disposed = new AtomicBoolean();
        ScopeImpl scope = new ScopeImpl("removal");
        Disposable resource = scope.install(() -> disposed.set(true));

        scope.remove(resource);
        scope.close();

        assertFalse(disposed.get());
    }

    @Test
    void completedDelayedTaskDetachesFromOwningScope() throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("scheduler");
        ExecutorScheduler scheduler = new ExecutorScheduler(
                scope, timerExecutor, asyncExecutor, Runnable::run);
        CountDownLatch ran = new CountDownLatch(1);

        scheduler.after(Ticks.of(1L), ran::countDown);

        assertTrue(ran.await(5L, TimeUnit.SECONDS));
        awaitEmpty(scope);
        scope.close();
    }

    @Test
    void cancelledTaskDetachesFromOwningScope() {
        ScopeImpl scope = new ScopeImpl("scheduler");
        ExecutorScheduler scheduler = new ExecutorScheduler(
                scope, timerExecutor, asyncExecutor, Runnable::run);

        TaskHandle handle = scheduler.every(Ticks.of(20L), () -> { });
        assertEquals(1, scope.resourceCount());
        assertTrue(handle.cancel());

        assertEquals(0, scope.resourceCount());
        scope.close();
    }

    @Test
    void executorRecurringTaskStaysOwnedAfterSuccessAndDetachesAfterFailure()
            throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("scheduler");
        ExecutorScheduler scheduler = new ExecutorScheduler(
                scope, timerExecutor, asyncExecutor, Runnable::run);
        CountDownLatch successfulRun = new CountDownLatch(1);

        TaskHandle successful = scheduler.every(Ticks.of(1L), successfulRun::countDown);

        assertTrue(successfulRun.await(5L, TimeUnit.SECONDS));
        assertEquals(1, scope.resourceCount());
        assertTrue(successful.cancel());
        assertEquals(0, scope.resourceCount());

        CountDownLatch failedRun = new CountDownLatch(1);
        TaskHandle failed = scheduler.every(Ticks.of(1L), () -> {
            failedRun.countDown();
            throw new IllegalStateException("boom");
        });

        assertTrue(failedRun.await(5L, TimeUnit.SECONDS));
        awaitEmpty(scope);
        assertTrue(failed.isDone());
        scope.close();
    }

    @Test
    void bukkitRecurringTaskStaysOwnedAfterSuccessAndDetachesAfterFailure() {
        ScopeImpl scope = new ScopeImpl("bukkit-scheduler");
        AtomicReference<Runnable> scheduled = new AtomicReference<Runnable>();
        AtomicInteger cancellations = new AtomicInteger();
        BukkitScheduler bukkitScheduler = proxy(BukkitScheduler.class, (method, arguments) -> {
            if ("runTaskTimer".equals(method)) {
                scheduled.set((Runnable) arguments[1]);
                return proxy(BukkitTask.class, (taskMethod, ignoredArguments) -> {
                    if ("cancel".equals(taskMethod)) {
                        cancellations.incrementAndGet();
                    }
                    return null;
                });
            }
            return null;
        });
        BukkitSchedulerAdapter scheduler = new BukkitSchedulerAdapter(
                proxy(Plugin.class, (method, arguments) -> null),
                scope,
                bukkitScheduler,
                new KLogger(Logger.getLogger("ScopeResourceRemovalTest")));

        TaskHandle successful = scheduler.every(Ticks.of(1L), () -> { });
        scheduled.get().run();
        assertEquals(1, scope.resourceCount());
        assertTrue(successful.cancel());
        assertEquals(0, scope.resourceCount());
        assertEquals(1, cancellations.get());

        TaskHandle failed = scheduler.every(Ticks.of(1L), () -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(IllegalStateException.class, () -> scheduled.get().run());
        assertEquals(0, scope.resourceCount());
        assertTrue(failed.isDone());
        assertTrue(failed.isCancelled());
        assertEquals(2, cancellations.get());
        scope.close();
    }

    @Test
    void completedAsyncTaskDetachesFromOwningScope() throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("scheduler");
        ExecutorScheduler scheduler = new ExecutorScheduler(
                scope, timerExecutor, asyncExecutor, Runnable::run);
        CountDownLatch delivered = new CountDownLatch(1);

        AsyncTask<String> task = scheduler.async(() -> "value")
                .thenSync(value -> delivered.countDown());

        assertTrue(delivered.await(5L, TimeUnit.SECONDS));
        awaitEmpty(scope);
        assertTrue(task.isDone());
        scope.close();
    }

    @Test
    void failedAsyncTaskDetachesFromOwningScope() throws InterruptedException {
        ScopeImpl scope = new ScopeImpl("scheduler");
        ExecutorScheduler scheduler = new ExecutorScheduler(
                scope, timerExecutor, asyncExecutor, Runnable::run);
        CountDownLatch failed = new CountDownLatch(1);

        scheduler.async(() -> {
            throw new IllegalStateException("boom");
        }).onError(failure -> failed.countDown());

        assertTrue(failed.await(5L, TimeUnit.SECONDS));
        awaitEmpty(scope);
        scope.close();
    }

    private static void awaitEmpty(ScopeImpl scope) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (scope.resourceCount() > 0) {
            if (System.nanoTime() > deadline) {
                assertEquals(0, scope.resourceCount(), "terminated tasks must leave the scope");
            }
            Thread.sleep(10L);
        }
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        Object value = Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (target, method, arguments) -> {
                    Object result = invocation.invoke(method.getName(), arguments);
                    return result == null ? defaultValue(method.getReturnType()) : result;
                });
        return type.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return Integer.valueOf(0);
    }

    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
