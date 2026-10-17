package me.kzheart.klib.data.jdbc;

import me.kzheart.klib.data.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JdbcExecutorTest {
    @TempDir Path directory;

    @Test void typedCrudUsesBoundParametersAndExplicitNullableMapping() throws Exception {
        JdbcTestDataSource source = source();
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            table(executor);
            await(executor.transaction(sql -> {
                sql.update("INSERT INTO player(id, name, note) VALUES (?, ?, ?)", statement -> {
                    statement.setInt(1, 1); statement.setString(2, "alice"); statement.setNull(3, Types.VARCHAR);
                });
                sql.update("INSERT INTO player(id, name, note) VALUES (?, ?, ?)", statement -> {
                    statement.setInt(1, 2); statement.setString(2, "bob"); statement.setString(3, "hello");
                });
                return null;
            }));
            List<Player> players = await(executor.work(sql -> sql.query(
                    "SELECT id, name, note FROM player ORDER BY id", SqlParameters.none(),
                    row -> new Player(row.getInt("id"), row.getString("name"), row.getString("note")))));
            assertEquals(2, players.size()); assertEquals("alice", players.get(0).name); assertNull(players.get(0).note);
            assertEquals("hello", players.get(1).note);
            assertFalse(await(executor.work(sql -> sql.queryOne(
                    "SELECT id FROM player WHERE name = ?", statement -> statement.setString(1, "x' OR 1=1 --"),
                    row -> row.getInt(1)))).isPresent());
            assertEquals(1, await(executor.transaction(sql -> sql.update(
                    "UPDATE player SET name = ? WHERE id = ?", statement -> {
                        statement.setString(1, "renamed"); statement.setInt(2, 1);
                    }))).intValue());
            assertEquals("renamed", await(executor.work(sql -> sql.queryOne(
                    "SELECT name FROM player WHERE id = 1", SqlParameters.none(), row -> row.getString(1)))).get());
            assertEquals(1, await(executor.transaction(sql -> sql.update(
                    "DELETE FROM player WHERE id = ?", statement -> statement.setInt(1, 2)))).intValue());
        }
        released(source);
    }

    @Test void constraintFailureRollsBackWholeTransactionAndLeavesExecutorUsable() throws Exception {
        JdbcTestDataSource source = source();
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            table(executor);
            JdbcTransactionException failure = transactionFailure(executor.transaction(sql -> {
                sql.update("INSERT INTO player(id, name) VALUES (1, 'a')", SqlParameters.none());
                sql.update("INSERT INTO player(id, name) VALUES (1, 'duplicate')", SqlParameters.none());
                return null;
            }));
            assertEquals(JdbcTransactionException.Outcome.ROLLED_BACK, failure.outcome());
            assertInstanceOf(SQLException.class, failure.getCause());
            assertEquals(0, count(executor));
            assertTrue(source.closedAutoCommit);
            await(executor.transaction(sql -> sql.update("INSERT INTO player(id, name) VALUES (2, 'ok')", SqlParameters.none())));
            assertEquals(1, count(executor));
        }
        released(source);
    }

    @Test void errorsAlsoRollbackAndRollbackFailureNeverRestoresAutoCommit() throws Exception {
        JdbcTestDataSource source = source();
        AssertionError original = new AssertionError("business error");
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            JdbcTransactionException rolledBack = transactionFailure(executor.transaction(sql -> { throw original; }));
            assertSame(original, rolledBack.getCause());
            assertEquals(JdbcTransactionException.Outcome.ROLLED_BACK, rolledBack.outcome());
            source.failRollback = true;
            int restores = source.autoCommitRestores.get();
            IllegalStateException business = new IllegalStateException("business");
            JdbcTransactionException unknown = transactionFailure(executor.transaction(sql -> { throw business; }));
            assertEquals(JdbcTransactionException.Outcome.UNKNOWN, unknown.outcome());
            assertSame(business, unknown.getCause());
            assertEquals("rollback failed", business.getSuppressed()[0].getMessage());
            assertEquals(restores, source.autoCommitRestores.get());
            assertEquals(1, source.aborts.get());
        }
        released(source);
    }

    @Test void commitFailureIsUnknownAndNeverAutomaticallyRetried() throws Exception {
        JdbcTestDataSource source = source(); source.failCommit = true;
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            JdbcTransactionException failure = transactionFailure(executor.transaction(sql -> 7));
            assertEquals(JdbcTransactionException.Outcome.UNKNOWN, failure.outcome());
            assertEquals("lost commit reply", failure.getCause().getMessage());
            assertEquals(1, source.opened.get()); assertEquals(1, source.commits.get());
        }
        released(source);
    }

    @Test void cleanupFailureReportsConfirmedCommitAndPreservesOriginalFailure() throws Exception {
        JdbcTestDataSource source = source(); source.failClose = true;
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            JdbcTransactionException failure = transactionFailure(executor.transaction(sql -> 7));
            assertEquals(JdbcTransactionException.Outcome.COMMITTED, failure.outcome());
            assertEquals("close failed", failure.getCause().getMessage());
            IllegalStateException original = new IllegalStateException("mapper failed");
            JdbcTransactionException rolledBack = transactionFailure(executor.transaction(sql -> { throw original; }));
            assertSame(original, rolledBack.getCause());
            assertEquals("close failed", original.getSuppressed()[0].getMessage());
        }
        released(source);
    }

    @Test void isolationIsRestoredAndForeignTransactionsAreRejected() throws Exception {
        JdbcTestDataSource source = source();
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            int initial = await(executor.work(sql -> sql.connection().getTransactionIsolation()));
            int active = await(executor.transaction(SqlTransactionOptions.defaults()
                    .withIsolation(Connection.TRANSACTION_READ_UNCOMMITTED), sql -> sql.connection().getTransactionIsolation()));
            assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, active);
            assertEquals(initial, source.closedIsolation); assertTrue(source.closedAutoCommit);
            source.suppliedAutoCommit = false;
            JdbcTransactionException failure = transactionFailure(executor.transaction(sql -> fail("must not execute")));
            assertEquals(JdbcTransactionException.Outcome.NOT_STARTED, failure.outcome());
        }
        released(source);
    }

    @Test void savepointsReuseTheTransactionConnectionWithoutCreatingAnotherTransaction() throws Exception {
        JdbcTestDataSource source = source();
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            table(executor);
            int before = source.opened.get();
            await(executor.transaction(sql -> {
                Connection connection = sql.connection();
                assertFalse(connection.getAutoCommit());
                sql.update("INSERT INTO player(id, name) VALUES (1, 'kept')", SqlParameters.none());
                Savepoint point = connection.setSavepoint();
                sql.update("INSERT INTO player(id, name) VALUES (2, 'removed')", SqlParameters.none());
                connection.rollback(point);
                connection.releaseSavepoint(point);
                assertSame(connection, sql.connection());
                return null;
            }));
            assertEquals(before + 1, source.opened.get());
            assertEquals(1, count(executor));
        }
        released(source);
    }

    @Test void acquisitionAndStateRestoreFailuresHaveDifferentOutcomes() throws Exception {
        JdbcTestDataSource source = source(); source.failAcquire = true;
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            JdbcTransactionException acquisition = transactionFailure(executor.transaction(sql -> 1));
            assertEquals(JdbcTransactionException.Outcome.NOT_STARTED, acquisition.outcome());
            assertEquals("acquisition failed", acquisition.getCause().getMessage());
            source.failAcquire = false; source.failRestore = true;
            JdbcTransactionException restore = transactionFailure(executor.transaction(sql -> 2));
            assertEquals(JdbcTransactionException.Outcome.COMMITTED, restore.outcome());
            assertEquals("restore failed", restore.getCause().getMessage());
            assertEquals(1, source.aborts.get());
        }
        released(source);
    }

    @Test void queryCardinalityAndMapperFailuresReleaseStatementsAndResults() throws Exception {
        JdbcTestDataSource source = source();
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            assertFailure(executor.work(sql -> sql.queryOne("SELECT 1 UNION ALL SELECT 2", SqlParameters.none(), row -> row.getInt(1))), StorageException.class);
            assertFailure(executor.work(sql -> sql.query("SELECT 1", SqlParameters.none(), row -> { throw new SQLException("mapping"); })), StorageException.class);
            assertFailure(executor.work(sql -> sql.queryOne("SELECT 1", SqlParameters.none(), row -> null)), StorageException.class);
            assertFailure(executor.work(sql -> sql.query("SELECT ?", statement -> { throw new SQLException("binding"); }, row -> row.getInt(1))), StorageException.class);
            assertEquals(0, source.statements.get()); assertEquals(0, source.rows.get());
        }
        released(source);
    }

    @Test void escapedAndCrossThreadClientsAreRejectedButCompletionCompositionWorks() throws Exception {
        JdbcTestDataSource source = source(); AtomicReference<SqlClient> escaped = new AtomicReference<SqlClient>();
        try (JdbcExecutor executor = new JdbcExecutor(source, 1, 8)) {
            await(executor.work(sql -> {
                escaped.set(sql);
                AtomicReference<Throwable> crossThread = new AtomicReference<Throwable>();
                Thread thread = new Thread(() -> { try { sql.connection(); } catch (Throwable failure) { crossThread.set(failure); } });
                thread.start(); thread.join(1000L);
                assertInstanceOf(IllegalStateException.class, crossThread.get());
                assertFailure(executor.transaction(nested -> 1), IllegalStateException.class);
                return 1;
            }));
            assertThrows(IllegalStateException.class, () -> escaped.get().connection());
            assertEquals(2, await(executor.work(sql -> 1).thenCompose(one -> executor.work(sql -> one + 1))).intValue());
        }
        released(source);
    }

    @Test void fullQueueAndShutdownRejectAsFailedStagesWhileAcceptedWorkDrains() throws Exception {
        JdbcTestDataSource source = source(); JdbcExecutor executor = new JdbcExecutor(source, 1, 1);
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        try {
            CompletionStage<Integer> running = executor.transaction(sql -> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return 1;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletionStage<Integer> queued = executor.transaction(sql -> 2);
            assertFailure(executor.work(sql -> 3), RejectedExecutionException.class);
            CompletionStage<Void> closed = executor.closeAsync();
            assertFalse(closed.toCompletableFuture().isDone());
            assertFailure(executor.work(sql -> 4), RejectedExecutionException.class);
            release.countDown();
            assertEquals(1, await(running)); assertEquals(2, await(queued)); await(closed);
            assertEquals(2, source.opened.get());
        } finally { release.countDown(); executor.close(); }
        released(source);
    }

    @Test void cancellingQueuedWorkSkipsItAndCancellingActiveWorkDoesNotPromiseRollback() throws Exception {
        JdbcTestDataSource source = source(); JdbcExecutor executor = new JdbcExecutor(source, 1, 2);
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        try {
            CompletionStage<Integer> active = executor.transaction(sql -> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return 1;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletionStage<Integer> queued = executor.work(sql -> fail("cancelled queued work"));
            assertTrue(queued.toCompletableFuture().cancel(true));
            assertTrue(active.toCompletableFuture().cancel(true));
            CompletionStage<Void> closed = executor.closeAsync(); release.countDown(); await(closed);
            assertEquals(1, source.commits.get()); assertEquals(0, source.rollbacks.get());
            assertEquals(1, source.opened.get());
        } finally { release.countDown(); executor.close(); }
        released(source);
    }

    @Test void closeInsideCallbackDoesNotDeadlockAndArgumentsFailEarly() throws Exception {
        JdbcTestDataSource source = source(); JdbcExecutor executor = new JdbcExecutor(source, 1, 1);
        assertEquals(1, await(executor.transaction(sql -> { executor.close(); return 1; })).intValue());
        await(executor.closeAsync()); released(source);
        assertThrows(IllegalArgumentException.class, () -> new JdbcExecutor(source, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JdbcExecutor(source, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> SqlTransactionOptions.defaults().withIsolation(Connection.TRANSACTION_NONE));
    }

    private JdbcTestDataSource source() { return new JdbcTestDataSource(directory.resolve("test.sqlite")); }
    private static void table(JdbcExecutor executor) throws Exception {
        await(executor.work(sql -> sql.update("CREATE TABLE player(id INTEGER PRIMARY KEY, name TEXT NOT NULL, note TEXT)", SqlParameters.none())));
    }
    private static int count(JdbcExecutor executor) throws Exception {
        return await(executor.work(sql -> sql.queryOne("SELECT COUNT(*) FROM player", SqlParameters.none(), row -> row.getInt(1)))).get();
    }
    private static void released(JdbcTestDataSource source) {
        assertEquals(source.opened.get(), source.closed.get()); assertEquals(0, source.statements.get()); assertEquals(0, source.rows.get());
    }
    static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static JdbcTransactionException transactionFailure(CompletionStage<?> stage) {
        return (JdbcTransactionException) assertFailure(stage, JdbcTransactionException.class);
    }
    private static Throwable assertFailure(CompletionStage<?> stage, Class<? extends Throwable> type) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(stage));
        assertInstanceOf(type, failure.getCause()); return failure.getCause();
    }
    private static final class Player {
        final int id; final String name; final String note;
        Player(int id, String name, String note) { this.id = id; this.name = name; this.note = note; }
    }
}
