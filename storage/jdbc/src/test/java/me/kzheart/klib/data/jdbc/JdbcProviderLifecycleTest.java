package me.kzheart.klib.data.jdbc;

import me.kzheart.klib.data.StorageException;
import me.kzheart.klib.data.StorageSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JdbcProviderLifecycleTest {
    @TempDir Path directory;

    @Test void lateOpenDoesNotFailBeforePhysicalConnectionCloseFinishes() throws Exception {
        try (Fixture fixture = new Fixture(true, true)) {
            CompletableFuture<?> opened = fixture.provider.open().toCompletableFuture();
            fixture.await(fixture.acquiring);
            fixture.provider.dispose();
            fixture.allowAcquire.countDown();
            fixture.await(fixture.closing);
            assertFalse(opened.isDone());
            assertEquals(0, fixture.actual.closed.get());
            fixture.allowClose.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> opened.get(5, TimeUnit.SECONDS));
            assertEquals("provider is disposed", failure.getCause().getMessage());
            assertEquals(1, fixture.actual.closed.get());
        }
    }

    @Test void lateCloseFailureRemainsObservableAndTerminatesTheOpeningWorker() throws Exception {
        try (Fixture fixture = new Fixture(true, true)) {
            fixture.actual.failClose = true;
            CompletableFuture<?> opened = fixture.provider.open().toCompletableFuture();
            fixture.await(fixture.acquiring);
            fixture.provider.dispose();
            fixture.allowAcquire.countDown();
            fixture.await(fixture.closing);
            assertFalse(opened.isDone());
            fixture.allowClose.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> opened.get(5, TimeUnit.SECONDS));
            assertInstanceOf(StorageException.class, failure.getCause());
            assertEquals("close failed", failure.getCause().getCause().getMessage());
            assertEquals(1, fixture.actual.closed.get());
        }
    }

    @Test void externalDisposeDrainsAcceptedTransactionBeforeClosingConnection() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            StorageSession session = fixture.provider.open().toCompletableFuture().get(5, TimeUnit.SECONDS);
            CountDownLatch working = new CountDownLatch(1);
            CountDownLatch finishWork = new CountDownLatch(1);
            CompletableFuture<?> transaction = session.transaction(tx -> {
                working.countDown();
                fixture.await(finishWork);
                tx.put("test", "accepted", new byte[]{1});
                return null;
            }).toCompletableFuture();
            CompletableFuture<Void> closing = null;
            try {
                fixture.await(working);
                closing = CompletableFuture.runAsync(fixture.provider::dispose);
                assertEquals(0, fixture.actual.closed.get());
                finishWork.countDown();
                transaction.get(5, TimeUnit.SECONDS);
                fixture.await(fixture.closing);
                assertFalse(closing.isDone());
                assertEquals(1, fixture.actual.commits.get());
                fixture.allowClose.countDown();
                closing.get(5, TimeUnit.SECONDS);
                assertEquals(1, fixture.actual.closed.get());
                assertThrows(ExecutionException.class, () -> session.get("test", "accepted").toCompletableFuture().get());
            } finally {
                finishWork.countDown();
                fixture.allowClose.countDown();
                if (closing != null) closing.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void transactionCallbackDisposeDoesNotCloseItsConnectionOrWaitForItself() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            StorageSession session = fixture.provider.open().toCompletableFuture().get(5, TimeUnit.SECONDS);
            session.transaction(tx -> {
                fixture.provider.dispose();
                assertEquals(0, fixture.actual.closed.get());
                tx.put("test", "after-dispose", new byte[]{1});
                return null;
            }).toCompletableFuture().get(5, TimeUnit.SECONDS);
            fixture.await(fixture.closing);
            fixture.worker.get().join(5000);
            assertEquals(1, fixture.actual.commits.get());
            assertEquals(1, fixture.actual.closed.get());
        }
    }

    private final class Fixture implements AutoCloseable {
        final JdbcTestDataSource actual = new JdbcTestDataSource(directory.resolve("lifecycle.db"));
        final CountDownLatch acquiring = new CountDownLatch(1);
        final CountDownLatch closing = new CountDownLatch(1);
        final CountDownLatch allowAcquire;
        final CountDownLatch allowClose;
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final AbstractJdbcStorageProvider provider;

        Fixture(boolean blockAcquire, boolean blockClose) {
            allowAcquire = new CountDownLatch(blockAcquire ? 1 : 0);
            allowClose = new CountDownLatch(blockClose ? 1 : 0);
            DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (!method.getName().equals("getConnection")) return invoke(actual, method, args);
                worker.set(Thread.currentThread());
                acquiring.countDown();
                await(allowAcquire);
                Connection connection = actual.getConnection();
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, operation, arguments) -> {
                    if (operation.getName().equals("close")) {
                        closing.countDown();
                        await(allowClose);
                    }
                    return invoke(connection, operation, arguments);
                });
            });
            provider = new AbstractJdbcStorageProvider(source, SqlDialect.SQLITE, null) { };
        }

        void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(5, TimeUnit.SECONDS)); }

        @Override public void close() {
            allowAcquire.countDown();
            allowClose.countDown();
            provider.dispose();
            Thread thread = worker.get();
            if (thread != null) {
                try { thread.join(5000); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                assertFalse(thread.isAlive(), "storage worker must terminate");
            }
            assertEquals(actual.opened.get(), actual.closed.get(), "all acquired physical connections must close");
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
