package me.kzheart.klib.command;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CommandMutationGateTest {
    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    }
    private Runnable awaitQueued(Queue<Runnable> queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Runnable task = queue.poll(); if (task != null) return task;
            Thread.sleep(1);
        }
        fail("server callback did not arrive"); return null;
    }
    @Test void oldReadersCanReturnToTheMainThreadBeforeAnExclusiveMutation() throws Exception {
        ThreadPoolExecutor pool = pool(); Queue<Runnable> main = new ConcurrentLinkedQueue<Runnable>();
        Map<String, Integer> children = new LinkedHashMap<String, Integer>();
        children.put("a", 1); children.put("b", 2); children.put("c", 3);
        CountDownLatch reading = new CountDownLatch(2); List<Future<Integer>> readers = new ArrayList<Future<Integer>>();
        for (int index = 0; index < 2; index++) readers.add(pool.submit(() -> {
            int count = 0; CountDownLatch resume = new CountDownLatch(1);
            for (Integer value : children.values()) {
                if (count++ == 0) {
                    reading.countDown(); main.add(resume::countDown);
                    assertTrue(resume.await(3, TimeUnit.SECONDS));
                }
            }
            return count;
        }));
        assertTrue(reading.await(3, TimeUnit.SECONDS)); AtomicInteger mutations = new AtomicInteger();
        try {
            CommandMutationGate.submit(pool, main, () -> { children.clear(); mutations.incrementAndGet(); }, problem -> fail(problem));
            assertEquals(0, mutations.get()); // submission never waits on workers requesting server work
            awaitQueued(main).run(); awaitQueued(main).run();
            for (Future<Integer> reader : readers) assertEquals(3, reader.get(3, TimeUnit.SECONDS));
            awaitQueued(main).run();
            assertEquals(1, mutations.get()); assertTrue(children.isEmpty());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    @Test void independentlyShadedCoordinatorBatchesStayOrderedOnTheSameExecutor() throws Exception {
        ThreadPoolExecutor pool = pool(); Queue<Runnable> main = new ConcurrentLinkedQueue<Runnable>();
        List<String> order = new ArrayList<String>();
        try {
            CommandMutationGate.submit(pool, main, () -> order.add("core"), problem -> fail(problem));
            CommandMutationGate.submit(pool, main, () -> order.add("quests"), problem -> fail(problem));
            awaitQueued(main).run(); awaitQueued(main).run();
            assertEquals(Arrays.asList("core", "quests"), order);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    @Test void anUnconsumedServerQueueExpiresWithoutLeavingWorkersParkedOrApplyingStaleWrites() throws Exception {
        ThreadPoolExecutor pool = pool(); Queue<Runnable> main = new ConcurrentLinkedQueue<Runnable>();
        AtomicInteger mutations = new AtomicInteger(); CountDownLatch failed = new CountDownLatch(1);
        try {
            CommandMutationGate.submit(pool, main, mutations::incrementAndGet, problem -> failed.countDown(), 100);
            Runnable stale = awaitQueued(main);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            stale.run(); assertEquals(0, mutations.get());
            assertEquals(7, pool.submit(() -> 7).get(3, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    @Test void shutdownInterruptsParkedWorkersAndCancelsStaleMutation() throws Exception {
        ThreadPoolExecutor pool = pool(); Queue<Runnable> main = new ConcurrentLinkedQueue<Runnable>();
        AtomicInteger mutations = new AtomicInteger(); CountDownLatch failed = new CountDownLatch(1);
        CommandMutationGate.submit(pool, main, mutations::incrementAndGet, problem -> failed.countDown());
        Runnable stale = awaitQueued(main); pool.shutdownNow();
        assertTrue(failed.await(3, TimeUnit.SECONDS)); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        stale.run(); assertEquals(0, mutations.get());
    }
}
