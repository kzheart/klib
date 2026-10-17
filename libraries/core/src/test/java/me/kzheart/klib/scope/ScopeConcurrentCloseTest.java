package me.kzheart.klib.scope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeConcurrentCloseTest {

    @Test
    void concurrentCloseDisposesEachResourceOnce() throws Exception {
        int callers = 24;
        AtomicInteger disposals = new AtomicInteger();
        ScopeImpl root = new ScopeImpl("root");
        root.install(disposals::incrementAndGet);

        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int index = 0; index < callers; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                root.close();
                return null;
            }));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(1, disposals.get());
        assertTrue(root.isClosed());
    }
}
