package me.kzheart.klib.command;

import org.junit.jupiter.api.Test;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ServerCommandSyncTest {
    @Test void sameTurnRequestsHaveOneServerOwnedRefreshEvenWhenThePluginIsDisabled() {
        AtomicInteger syncs = new AtomicInteger(); Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
        ServerCommandSync coordinator = new ServerCommandSync(syncs::incrementAndGet, queue, null, () -> false);
        coordinator.request(); coordinator.request(); coordinator.request();
        assertEquals(1, queue.size()); assertEquals(0, syncs.get());
        queue.remove().run(); assertEquals(1, syncs.get());
        coordinator.request(); assertEquals(1, queue.size()); queue.remove().run(); assertEquals(2, syncs.get());
    }
    @Test void stoppingServerDoesNotRefreshClients() {
        AtomicInteger syncs = new AtomicInteger(); Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
        AtomicBoolean stopping = new AtomicBoolean();
        ServerCommandSync coordinator = new ServerCommandSync(syncs::incrementAndGet, queue, null, stopping::get);
        coordinator.request(); stopping.set(true); queue.remove().run(); coordinator.request();
        assertEquals(0, syncs.get()); assertTrue(queue.isEmpty());
    }
    @Test void pendingMutationsAreBatchedAndOnlyRefreshAfterMapChanges() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>(); AtomicInteger changes = new AtomicInteger(), syncs = new AtomicInteger();
        ServerCommandSync coordinator = new ServerCommandSync(() -> { assertEquals(3, changes.get()); syncs.incrementAndGet(); }, queue, pool, () -> false);
        try {
            for (int i = 0; i < 3; i++) coordinator.enqueueMutation(() -> { changes.incrementAndGet(); coordinator.request(); });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (queue.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            assertFalse(queue.isEmpty()); queue.remove().run();
            assertEquals(3, changes.get()); assertEquals(1, queue.size()); assertEquals(0, syncs.get());
            queue.remove().run(); assertEquals(1, syncs.get());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }
}
