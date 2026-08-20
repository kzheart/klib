package me.kzheart.klib.data.sqlite;

import me.kzheart.klib.data.StorageProvider;
import me.kzheart.klib.data.StorageSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDisposeDrainTest {
    @TempDir
    Path directory;

    @Test
    void disposeWaitsForQueuedWorkAndConnectionClose() throws Exception {
        StorageProvider provider = new SQLiteStorageProvider(directory.resolve("drain.sqlite"));
        StorageSession session = provider.open().toCompletableFuture().get(5L, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        AtomicReference<Throwable> disposeFailure = new AtomicReference<Throwable>();

        session.transaction(context -> {
            entered.countDown();
            if (!release.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test transaction was not released");
            }
            context.put("test", "key", "value".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        assertTrue(entered.await(5L, TimeUnit.SECONDS));

        Thread disposer = new Thread(() -> {
            try {
                session.dispose();
            } catch (Throwable failure) {
                disposeFailure.set(failure);
            } finally {
                disposed.countDown();
            }
        }, "jdbc-dispose-test");
        disposer.start();

        assertFalse(disposed.await(200L, TimeUnit.MILLISECONDS),
                "dispose must not return while an earlier transaction is still running");
        release.countDown();
        assertTrue(disposed.await(5L, TimeUnit.SECONDS));
        assertNull(disposeFailure.get());
        assertTrue(session.get("test", "key").toCompletableFuture().isCompletedExceptionally());
        provider.dispose();
    }
}
