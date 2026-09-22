package me.kzheart.klib.scheduler;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnnotatedTasksTest {
    public static class Worker {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        @Every(ticks=1, thread=TaskThread.ASYNC) public void work() {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
    }
    @Test void asyncTicksDoNotOverlapAndClosingStopsFurtherDispatch() throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ScopeImpl root = new ScopeImpl("tasks");
        Worker worker = new Worker();
        try {
            root.registerCapability(SchedulerFactory.class, owner -> new ExecutorScheduler(owner, timer, pool, Runnable::run));
            Disposable registration = new Tasks(root).register(worker);
            assertTrue(worker.entered.await(3, TimeUnit.SECONDS));
            timer.schedule(() -> { }, 180, TimeUnit.MILLISECONDS).get(3, TimeUnit.SECONDS);
            assertEquals(1, worker.calls.get());
            registration.dispose(); worker.release.countDown();
            timer.schedule(() -> { }, 100, TimeUnit.MILLISECONDS).get(3, TimeUnit.SECONDS);
            assertEquals(1, worker.calls.get());
        } finally { worker.release.countDown(); root.close(); timer.shutdownNow(); pool.shutdownNow(); }
    }
    public static class Invalid { @Every(ticks=0) public void run() { fail("must not execute"); } }
    @Test void invalidScheduleFailsBeforeCapabilityLookup() {
        ScopeImpl root = new ScopeImpl("root");
        assertThrows(IllegalArgumentException.class, () -> new Tasks(root).register(new Invalid())); root.close();
    }
}
