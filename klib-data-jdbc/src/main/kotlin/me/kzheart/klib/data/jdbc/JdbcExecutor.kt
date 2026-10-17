package me.kzheart.klib.data.jdbc

import me.kzheart.klib.data.StorageException
import me.kzheart.klib.scope.Disposable
import java.sql.Connection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 有界 SQL 执行器；每项工作借还一个连接，数据源仍归调用方所有。
 * 关闭会排空已接纳工作；不自动重试写入，不中断事务，取消 Future 不等于回滚。
 */
class JdbcExecutor(private val dataSource: DataSource, parallelism: Int, queueCapacity: Int) : Disposable, AutoCloseable {
    private val terminated = CompletableFuture<Void>()
    private val inCallback = ThreadLocal<Boolean>()
    private val workerThread = ThreadLocal<Boolean>()
    private val executor: ThreadPoolExecutor

    init {
        require(parallelism > 0 && queueCapacity > 0) { "parallelism and queueCapacity must be positive" }
        val sequence = AtomicInteger()
        executor = object : ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity), { task ->
                Thread({
                    workerThread.set(true)
                    try { task.run() } finally { workerThread.remove() }
                }, "klib-sql-${sequence.incrementAndGet()}").apply { isDaemon = true }
            }, AbortPolicy()) {
            override fun terminated() { this@JdbcExecutor.terminated.complete(null) }
        }
    }

    fun <T> work(work: SqlWork<T>): CompletionStage<T> = submit { execute(work, null) }
    fun <T> transaction(work: SqlWork<T>): CompletionStage<T> = transaction(SqlTransactionOptions.defaults(), work)
    fun <T> transaction(options: SqlTransactionOptions, work: SqlWork<T>): CompletionStage<T> = submit { execute(work, options) }

    /** 取消返回阶段不会污染真正的终止信号。 */
    fun closeAsync(): CompletionStage<Void> { executor.shutdown(); return terminated.thenApply { null } }

    override fun dispose() {
        val closed = closeAsync()
        if (workerThread.get() == true) return
        try { closed.toCompletableFuture().get(30L, TimeUnit.SECONDS) }
        catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StorageException("Interrupted while draining JDBC executor", error)
        } catch (error: ExecutionException) {
            throw StorageException("Could not drain JDBC executor; accepted work may still be running", error)
        } catch (error: TimeoutException) {
            throw StorageException("Could not drain JDBC executor; accepted work may still be running", error)
        }
    }
    override fun close() = dispose()

    private fun <T> submit(operation: () -> T): CompletionStage<T> {
        val result = CompletableFuture<T>()
        if (inCallback.get() == true) {
            result.completeExceptionally(IllegalStateException(
                "Nested work on the same JDBC executor is forbidden; reuse the current SqlClient"))
            return result
        }
        try {
            executor.execute {
                if (!result.isCancelled) {
                    try { result.complete(operation()) } catch (error: Throwable) { result.completeExceptionally(error) }
                }
            }
        } catch (error: RejectedExecutionException) { result.completeExceptionally(error) }
        return result
    }

    private fun <T> execute(work: SqlWork<T>, options: SqlTransactionOptions?): T {
        val state = TransactionState(options)
        var result: T? = null
        var client: SqlClient? = null
        try {
            val connection = dataSource.connection
            state.connection = connection
            check(connection.autoCommit) { "DataSource must supply an auto-commit connection" }
            state.begin()
            val scopedClient = SqlClient(connection)
            client = scopedClient
            inCallback.set(true)
            try { result = work.execute(scopedClient) }
            finally { inCallback.remove(); scopedClient.finish() }
            state.commit()
        } catch (error: Throwable) { state.fail(error) }
        finally { client?.finish(); state.release() }
        state.throwFailure()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** 统一拥有事务与连接复原状态，失败路径不会意外把未回滚事务提交。 */
    private class TransactionState(private val options: SqlTransactionOptions?) {
        var connection: Connection? = null
        private var failure: Throwable? = null
        private var started = false
        private var commitAttempted = false
        private var committed = false
        private var rolledBack = false
        private var readOnlyChanged = false
        private var isolationChanged = false
        private var previousReadOnly = false
        private var previousIsolation = 0

        fun begin() {
            if (options == null) return
            val active = checkNotNull(connection)
            previousReadOnly = active.isReadOnly
            previousIsolation = active.transactionIsolation
            options.readOnly()?.let {
                if (it != previousReadOnly) { readOnlyChanged = true; active.isReadOnly = it }
            }
            options.isolation()?.let {
                if (it != previousIsolation) { isolationChanged = true; active.transactionIsolation = it }
            }
            started = true
            active.autoCommit = false
        }
        fun commit() {
            if (options == null) return
            commitAttempted = true
            checkNotNull(connection).commit()
            committed = true
        }
        fun fail(error: Throwable) {
            failure = error
            if (started && !committed) {
                capture { checkNotNull(connection).rollback(); rolledBack = true }
            }
        }
        fun release() {
            val active = connection ?: return
            val safeToRestore = !started || committed || rolledBack
            var restoreFailed = false
            if (safeToRestore) {
                if (!capture { if (started) active.autoCommit = true }) restoreFailed = true
                if (!capture { if (isolationChanged) active.transactionIsolation = previousIsolation }) restoreFailed = true
                if (!capture { if (readOnlyChanged) active.isReadOnly = previousReadOnly }) restoreFailed = true
            }
            if (!safeToRestore || restoreFailed) capture { active.abort { it.run() } }
            capture { active.close() }
        }
        fun throwFailure() {
            val error = failure ?: return
            if (options == null) throw StorageException("JDBC work failed; no automatic retry", error)
            val outcome = when {
                committed -> JdbcTransactionException.Outcome.COMMITTED
                commitAttempted || (started && !rolledBack) -> JdbcTransactionException.Outcome.UNKNOWN
                rolledBack -> JdbcTransactionException.Outcome.ROLLED_BACK
                else -> JdbcTransactionException.Outcome.NOT_STARTED
            }
            throw JdbcTransactionException(outcome, error)
        }
        private inline fun capture(operation: () -> Unit): Boolean = try {
            operation(); true
        } catch (error: Throwable) {
            val original = failure
            if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
            false
        }
    }

    companion object {
        /** 精确达到目标版本才发布执行器；外部迁移失败会关闭并排空，但不会代替引擎回滚。 */
        @JvmStatic
        fun open(source: DataSource, parallelism: Int, queueCapacity: Int,
                 migrations: SqlMigrationEngine, targetVersion: Int): CompletionStage<JdbcExecutor> {
            require(targetVersion >= 0) { "targetVersion must not be negative" }
            val created = JdbcExecutor(source, parallelism, queueCapacity)
            val result = CompletableFuture<JdbcExecutor>()
            created.submit {
                val current = migrations.currentVersion(source)
                if (current < 0 || current > targetVersion) {
                    throw StorageException("Unsupported SQL schema version $current; expected at most $targetVersion")
                }
                if (current < targetVersion) migrations.migrate(source, targetVersion)
                val actual = migrations.currentVersion(source)
                if (actual != targetVersion) throw StorageException("SQL migration ended at version $actual; expected $targetVersion")
                created
            }.whenComplete { ready, failure ->
                if (failure != null) created.closeAsync().whenComplete { _, _ -> result.completeExceptionally(failure) }
                else if (!result.complete(ready)) created.closeAsync()
            }
            return result
        }
    }
}
