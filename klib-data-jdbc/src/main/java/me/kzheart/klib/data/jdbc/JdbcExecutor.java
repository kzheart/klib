package me.kzheart.klib.data.jdbc;

import me.kzheart.klib.data.StorageException;
import me.kzheart.klib.scope.Disposable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 与 KV 独立的有界 SQL 执行器。每次工作借还一条连接，数据源所有权留给调用方。
 * 关闭排空已接受的工作；不重试写入，不中断已开始事务，取消 Future 不等于回滚。
 */
public final class JdbcExecutor implements Disposable, AutoCloseable {
    private final DataSource dataSource;
    private final ThreadPoolExecutor executor;
    private final CompletableFuture<Void> terminated = new CompletableFuture<Void>();
    private final ThreadLocal<Boolean> inCallback = new ThreadLocal<Boolean>();
    private final ThreadLocal<Boolean> workerThread = new ThreadLocal<Boolean>();

    public JdbcExecutor(DataSource dataSource, int parallelism, int queueCapacity) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (parallelism <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("parallelism and queueCapacity must be positive");
        }
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity), task -> {
                    Thread thread = new Thread(() -> {
                        workerThread.set(Boolean.TRUE);
                        try { task.run(); } finally { workerThread.remove(); }
                    }, "klib-sql-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() {
                terminated.complete(null);
            }
        };
    }

    /**
     * 在独立 I/O 线程执行迁移门禁，只有达到精确目标版本才返回可用执行器。
     * 外部引擎失败时排空并关闭执行器；不替外部引擎修复或回滚 schema。
     */
    public static CompletionStage<JdbcExecutor> open(DataSource source, int parallelism,
            int queueCapacity, SqlMigrationEngine migrations, int targetVersion) {
        Objects.requireNonNull(migrations, "migrations");
        if (targetVersion < 0) throw new IllegalArgumentException("targetVersion must not be negative");
        JdbcExecutor created = new JdbcExecutor(source, parallelism, queueCapacity);
        CompletableFuture<JdbcExecutor> result = new CompletableFuture<JdbcExecutor>();
        created.submit(() -> {
            int current = migrations.currentVersion(source);
            if (current < 0 || current > targetVersion) {
                throw new StorageException("Unsupported SQL schema version " + current
                        + "; expected at most " + targetVersion);
            }
            if (current < targetVersion) migrations.migrate(source, targetVersion);
            int actual = migrations.currentVersion(source);
            if (actual != targetVersion) {
                throw new StorageException("SQL migration ended at version " + actual
                        + "; expected " + targetVersion);
            }
            return created;
        }).whenComplete((ready, failure) -> {
            if (failure != null) {
                created.closeAsync().whenComplete((ignored, closeFailure) -> result.completeExceptionally(failure));
            } else if (!result.complete(ready)) {
                created.closeAsync();
            }
        });
        return result;
    }

    /** 独立自动提交工作；多条语句的原子写入必须使用 transaction。 */
    public <T> CompletionStage<T> work(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        return submit(() -> execute(work, null));
    }

    public <T> CompletionStage<T> transaction(SqlWork<T> work) {
        return transaction(SqlTransactionOptions.defaults(), work);
    }

    public <T> CompletionStage<T> transaction(SqlTransactionOptions options, SqlWork<T> work) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(work, "work");
        return submit(() -> execute(work, options));
    }

    /** 停止接受任务，异步排空所有已接受工作并归还连接；不关闭 DataSource。 */
    public CompletionStage<Void> closeAsync() {
        executor.shutdown();
        // 返回派生结果，调用方取消等待不能污染真正的终止信号。
        return terminated.thenApply(ignored -> null);
    }

    /** 最多等待 30 秒；工作线程内只发起关闭，使用 closeAsync 观察最终释放。 */
    @Override public void dispose() {
        CompletionStage<Void> closed = closeAsync();
        if (Boolean.TRUE.equals(workerThread.get())) return;
        try {
            closed.toCompletableFuture().get(30L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while draining JDBC executor", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new StorageException("Could not drain JDBC executor; accepted work may still be running", error);
        }
    }

    @Override public void close() { dispose(); }

    private <T> CompletionStage<T> submit(CheckedSupplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<T>();
        if (Boolean.TRUE.equals(inCallback.get())) {
            result.completeExceptionally(new IllegalStateException(
                    "Nested work on the same JDBC executor is forbidden; reuse the current SqlClient"));
            return result;
        }
        try {
            executor.execute(() -> {
                if (result.isCancelled()) return;
                try { result.complete(operation.get()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    private <T> T execute(SqlWork<T> work, SqlTransactionOptions options) {
        Connection connection = null;
        SqlClient client = null;
        T result = null;
        Throwable failure = null;
        boolean started = false;
        boolean commitAttempted = false;
        boolean committed = false;
        boolean rolledBack = false;
        boolean readOnlyChanged = false;
        boolean isolationChanged = false;
        boolean previousReadOnly = false;
        int previousIsolation = 0;
        try {
            connection = dataSource.getConnection();
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("DataSource must supply an auto-commit connection");
            }
            if (options != null) {
                previousReadOnly = connection.isReadOnly();
                previousIsolation = connection.getTransactionIsolation();
                if (options.readOnly() != null && options.readOnly().booleanValue() != previousReadOnly) {
                    readOnlyChanged = true;
                    connection.setReadOnly(options.readOnly().booleanValue());
                }
                if (options.isolation() != null && options.isolation().intValue() != previousIsolation) {
                    isolationChanged = true;
                    connection.setTransactionIsolation(options.isolation().intValue());
                }
                started = true;
                connection.setAutoCommit(false);
            }
            client = new SqlClient(connection);
            inCallback.set(Boolean.TRUE);
            try { result = work.execute(client); }
            finally { inCallback.remove(); client.finish(); }
            if (options != null) {
                commitAttempted = true;
                connection.commit();
                committed = true;
            }
        } catch (Throwable error) {
            failure = error;
            if (started && !committed) {
                try { connection.rollback(); rolledBack = true; }
                catch (Throwable rollbackError) { failure = append(failure, rollbackError); }
            }
        } finally {
            if (client != null) client.finish();
            if (connection != null) {
                boolean safeToRestore = !started || committed || rolledBack;
                boolean restoreFailed = false;
                if (safeToRestore) {
                    try { if (started) connection.setAutoCommit(true); }
                    catch (Throwable error) { failure = append(failure, error); restoreFailed = true; }
                    try { if (isolationChanged) connection.setTransactionIsolation(previousIsolation); }
                    catch (Throwable error) { failure = append(failure, error); restoreFailed = true; }
                    try { if (readOnlyChanged) connection.setReadOnly(previousReadOnly); }
                    catch (Throwable error) { failure = append(failure, error); restoreFailed = true; }
                }
                if (!safeToRestore || restoreFailed) {
                    // 尤其不能在 rollback 失败后 setAutoCommit(true)，那可能提交残留事务。
                    try { connection.abort(Runnable::run); }
                    catch (Throwable error) { failure = append(failure, error); }
                }
                try { connection.close(); }
                catch (Throwable error) { failure = append(failure, error); }
            }
        }
        if (failure != null) {
            if (options == null) throw new StorageException("JDBC work failed; no automatic retry", failure);
            JdbcTransactionException.Outcome outcome = committed ? JdbcTransactionException.Outcome.COMMITTED
                    : commitAttempted || (started && !rolledBack) ? JdbcTransactionException.Outcome.UNKNOWN
                    : rolledBack ? JdbcTransactionException.Outcome.ROLLED_BACK
                    : JdbcTransactionException.Outcome.NOT_STARTED;
            throw new JdbcTransactionException(outcome, failure);
        }
        return result;
    }

    private static Throwable append(Throwable original, Throwable additional) {
        if (original == null) return additional;
        if (original != additional) original.addSuppressed(additional);
        return original;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
