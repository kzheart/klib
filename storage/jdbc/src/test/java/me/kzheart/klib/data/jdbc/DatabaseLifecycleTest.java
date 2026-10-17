package me.kzheart.klib.data.jdbc;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import me.kzheart.klib.data.StorageException;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static me.kzheart.klib.data.jdbc.JdbcExecutorTest.await;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseLifecycleTest {
    @TempDir Path directory;

    @Test void closeStopsAdmissionDrainsAcceptedWorkAndOnlyThenClosesOwnedSource() throws Exception {
        JdbcTestDataSource source = source(); AtomicInteger closed = new AtomicInteger();
        ScopeImpl scope = new ScopeImpl("database");
        Database db = await(Databases.open(scope, DatabaseConfig.builder(() -> JdbcSource.owned(source, closed::incrementAndGet))
            .workers(1).queueCapacity(1).closeTimeout(Duration.ofMillis(20)).build()));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            CompletionStage<Integer> running = db.transactionAsync(sql -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return 1; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletionStage<Integer> queued = db.readAsync(sql -> 2);
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> await(db.readAsync(sql -> 3)));
            assertInstanceOf(RejectedExecutionException.class, rejected.getCause());
            assertThrows(StorageException.class, db::dispose);
            CompletionStage<Void> done = db.closeAsync();
            assertFalse(done.toCompletableFuture().isDone()); assertEquals(0, closed.get());
            release.countDown(); assertEquals(1, await(running)); assertEquals(2, await(queued)); await(done);
            assertEquals(1, closed.get()); await(db.closeAsync()); assertEquals(1, closed.get());
            assertThrows(Exception.class, () -> await(db.readAsync(sql -> 4)));
        } finally { release.countDown(); await(db.closeAsync()); scope.close(); }
        assertEquals(source.opened.get(), source.closed.get());
    }

    @Test void cancellationAndScopeCloseDuringAcquisitionReleaseTheLateSourceWithoutLockInversion() throws Exception {
        for (boolean cancel : new boolean[] { true, false }) {
            ScopeImpl scope = new ScopeImpl("opening");
            JdbcTestDataSource source = source(); AtomicInteger closed = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), cleaned = new CountDownLatch(1);
            CompletableFuture<Database> pending = Databases.open(scope, DatabaseConfig.builder(() -> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return JdbcSource.owned(source, () -> { closed.incrementAndGet(); cleaned.countDown(); });
            }).build()).toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closer = new Thread(() -> { try { scope.close(); } catch (Throwable error) { closeFailure.set(error); } });
            try {
                if (cancel) pending.cancel(false); else closer.start();
                release.countDown(); assertTrue(cleaned.await(5, TimeUnit.SECONDS));
                if (!cancel) { closer.join(5000); assertFalse(closer.isAlive()); assertNull(closeFailure.get()); }
                assertEquals(1, closed.get());
            } finally { release.countDown(); scope.close(); closer.join(5000); }
            assertEquals(source.opened.get(), source.closed.get());
        }
    }

    @Test void anExternalAutoCloseablePoolIsNeverClosedByTheDatabase() throws Exception {
        JdbcTestDataSource source = source(); AtomicInteger externalClose = new AtomicInteger();
        DataSource external = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { DataSource.class, AutoCloseable.class }, (p,m,a) -> {
            if (m.getName().equals("close")) { externalClose.incrementAndGet(); return null; }
            return m.invoke(source, a);
        });
        ScopeImpl scope = new ScopeImpl("external");
        Database db = await(Databases.open(scope, DatabaseConfig.external(external).build()));
        assertEquals(1, await(db.readAsync(sql -> sql.queryOne("SELECT 1 AS n", SqlArguments.of(), SqlRows.mapper(row -> row.intValue("n"))).get())).intValue());
        await(db.closeAsync()); scope.close(); assertEquals(0, externalClose.get());
        assertEquals(source.opened.get(), source.closed.get());
    }

    @Test void mappedRowsCannotEscapeToAnotherRowThreadOrCallback() throws Exception {
        JdbcTestDataSource source = source(); AtomicReference<SqlRow> escaped = new AtomicReference<>();
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            await(executor.work(sql -> sql.query("SELECT 1 AS n UNION ALL SELECT 2", SqlArguments.of(), SqlRows.mapper(row -> {
                if (escaped.get() != null) assertThrows(IllegalStateException.class, () -> escaped.get().intValue("n"));
                escaped.set(row);
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread other = new Thread(() -> { try { row.intValue("n"); } catch (Throwable failure) { error.set(failure); } });
                other.start(); try { other.join(1000); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                assertInstanceOf(IllegalStateException.class, error.get());
                return row.intValue("n");
            }))));
            assertThrows(IllegalStateException.class, () -> escaped.get().string("n"));
            assertThrows(Exception.class, () -> await(executor.work(sql -> sql.queryOne("SELECT NULL AS missing", SqlArguments.of(), SqlRows.mapper(row -> row.string("missing"))))));
        }
    }

    @Test void openAndMigrationFailureDrainAndReleaseOwnedSourcesBeforeFailing() throws Exception {
        for (boolean migration : new boolean[] { false, true }) {
            JdbcTestDataSource source = source(); AtomicInteger closed = new AtomicInteger();
            if (!migration) source.failAcquire = true;
            DatabaseConfig.Builder config = DatabaseConfig.builder(() -> JdbcSource.owned(source, closed::incrementAndGet));
            if (migration) config.migrations(new SqlMigrationEngine() {
                public int currentVersion(DataSource dataSource) throws Exception {
                    try (java.sql.Connection ignored = dataSource.getConnection()) { assertFalse(ignored.isClosed()); return 0; }
                }
                public void migrate(DataSource dataSource, int target) { /* faulty external engine: does not reach target */ }
            }, 1);
            ScopeImpl scope = new ScopeImpl("failed-open");
            try { assertThrows(Exception.class, () -> await(Databases.open(scope, config.build()))); }
            finally { scope.close(); }
            assertEquals(1, closed.get()); assertEquals(source.opened.get(), source.closed.get());
        }
    }

    @Test void aFailingLogHandlerCannotLeaveOpeningOrCleanupUnfinished() throws Exception {
        KLogger logger = new KLogger(new Logger("broken-test-handler", null) {
            @Override public void log(Level level, String message) { throw new IllegalStateException("log handler failed"); }
        });
        for (boolean fail : new boolean[] { false, true }) {
            JdbcTestDataSource source = source(); source.failAcquire = fail;
            AtomicInteger closed = new AtomicInteger(); ScopeImpl scope = new ScopeImpl("log-failure");
            CompletionStage<Database> pending = Databases.open(scope, DatabaseConfig.builder(() -> JdbcSource.owned(source, closed::incrementAndGet)).logger(logger).build());
            try {
                if (fail) assertThrows(ExecutionException.class, () -> await(pending));
                else await(await(pending).closeAsync());
            } finally { scope.close(); }
            assertEquals(1, closed.get());
        }
    }
    private JdbcTestDataSource source() { return new JdbcTestDataSource(directory.resolve("test.sqlite")); }
}
