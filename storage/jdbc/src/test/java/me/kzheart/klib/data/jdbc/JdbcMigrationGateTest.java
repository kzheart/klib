package me.kzheart.klib.data.jdbc;

import me.kzheart.klib.data.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static me.kzheart.klib.data.jdbc.JdbcExecutorTest.await;
import static org.junit.jupiter.api.Assertions.*;

class JdbcMigrationGateTest {
    @TempDir Path directory;

    @Test void exactVersionSkipsMigrationAndInvalidVersionsNeverReachBusiness() throws Exception {
        DataSource source = source(); AtomicInteger calls = new AtomicInteger();
        try (JdbcExecutor ready = await(JdbcExecutor.open(source, 1, 2, engine(2, calls), 2))) {
            assertEquals(5, await(ready.work(sql -> 5)).intValue()); assertEquals(0, calls.get());
        }
        for (int invalid : new int[] {-1, 3}) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> await(JdbcExecutor.open(source, 1, 2, engine(invalid, calls), 2)));
            assertInstanceOf(StorageException.class, error.getCause());
        }
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> JdbcExecutor.open(source, 1, 2, engine(0, calls), -1));
    }

    @Test void migrationMustActuallyReachTargetVersion() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> await(JdbcExecutor.open(source(), 1, 2, engine(0, calls), 2)));
        assertTrue(failure.getCause().getMessage().contains("ended at version 0"));
        assertEquals(1, calls.get());
    }

    @Test void compiledExternalEngineAdapterOwnsMigrationRollbackAndCanRetryAfterFailure() throws Exception {
        DataSource source = source();
        try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE external_history(version INTEGER NOT NULL)")) {
            statement.executeUpdate();
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO external_history VALUES (0)")) { insert.executeUpdate(); }
            try (PreparedStatement payload = connection.prepareStatement("CREATE TABLE payload(value TEXT NOT NULL)")) { payload.executeUpdate(); }
        }
        ExistingMigrator migrator = new ExistingMigrator(source);
        SqlMigrationEngine adapter = new SqlMigrationEngine() {
            @Override public int currentVersion(DataSource ignored) throws SQLException { return migrator.version(); }
            @Override public void migrate(DataSource ignored, int target) throws SQLException { migrator.upgrade(target); }
        };
        migrator.fail = true;
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> await(JdbcExecutor.open(source, 1, 4, adapter, 1)));
        assertEquals("migration failed", failure.getCause().getMessage());
        assertEquals(0, migrator.version());
        try (Connection connection = source.getConnection(); PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM payload");
                ResultSet rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        migrator.fail = false;
        try (JdbcExecutor ready = await(JdbcExecutor.open(source, 1, 4, adapter, 1))) {
            assertEquals(1, migrator.version());
            assertEquals("applied", await(ready.work(sql -> sql.queryOne(
                    "SELECT value FROM payload", SqlParameters.none(), row -> row.getString(1)))).get());
        }
    }

    @Test void cancellingAcquisitionClosesTheEventuallyReadyExecutor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> thread = new AtomicReference<Thread>(); AtomicInteger version = new AtomicInteger();
        SqlMigrationEngine migrations = new SqlMigrationEngine() {
            @Override public int currentVersion(DataSource source) { return version.get(); }
            @Override public void migrate(DataSource source, int target) throws Exception {
                thread.set(Thread.currentThread()); entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS)); version.set(target);
            }
        };
        CompletionStage<JdbcExecutor> opening = JdbcExecutor.open(source(), 1, 1, migrations, 1);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(opening.toCompletableFuture().cancel(true));
        } finally { release.countDown(); }
        thread.get().join(5000L); assertFalse(thread.get().isAlive());
    }

    private DataSource source() { return new JdbcTestDataSource(directory.resolve("migration.sqlite")); }
    private static SqlMigrationEngine engine(int version, AtomicInteger migrations) {
        return new SqlMigrationEngine() {
            @Override public int currentVersion(DataSource source) { return version; }
            @Override public void migrate(DataSource source, int target) { migrations.incrementAndGet(); }
        };
    }

    /**
     * 隔离夹具模拟一个已有迁移工具的 API，不发布为迁移实现。
     * 它只证明适配、门禁与该夹具的 DML 原子性，不证明跨进程锁、DDL 或 checksum。
     */
    private static final class ExistingMigrator {
        private final DataSource source;
        private boolean fail;
        ExistingMigrator(DataSource source) { this.source = source; }
        int version() throws SQLException {
            try (Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT version FROM external_history");
                    ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("missing version"); return rows.getInt(1); }
        }
        void upgrade(int target) throws SQLException {
            try (Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO payload VALUES ('applied')")) { insert.executeUpdate(); }
                    try (PreparedStatement update = connection.prepareStatement("UPDATE external_history SET version = ?")) {
                        update.setInt(1, target); update.executeUpdate();
                    }
                    if (fail) throw new SQLException("migration failed");
                    connection.commit();
                } catch (SQLException failure) {
                    try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            }
        }
    }
}
