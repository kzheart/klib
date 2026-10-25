package me.kzheart.klib.data.jdbc

import me.kzheart.klib.KLogger
import me.kzheart.klib.data.StorageException
import me.kzheart.klib.data.StorageSession
import me.kzheart.klib.data.StorageTransaction
import me.kzheart.klib.data.TransactionContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** 单线程会话：连接、重连和事务状态只由执行器线程拥有。 */
internal class JdbcStorageSession(
    private val connectionFactory: () -> Connection,
    private val dialect: SqlDialect,
    private val executor: ExecutorService,
    private val onClose: (JdbcStorageSession) -> Unit,
    private val logger: KLogger?
) : StorageSession {
    private val disposed = AtomicBoolean()
    private val executorThread = Thread.currentThread()
    private var connection: Connection? = connect()
    private var reconnectFailureReported = false

    override fun get(namespace: String, key: String): CompletionStage<Optional<ByteArray>> =
        readOnly { it.get(namespace, key) }

    override fun put(namespace: String, key: String, value: ByteArray): CompletionStage<Void> {
        val copy = value.copyOf()
        return write { it.put(namespace, key, copy) }
    }

    override fun delete(namespace: String, key: String): CompletionStage<Void> {
        return write { it.delete(namespace, key) }
    }

    override fun entries(namespace: String): CompletionStage<Map<String, ByteArray>> = readOnly { it.entries(namespace) }

    private fun write(operation: (TransactionContext) -> Unit): CompletionStage<Void> =
        transaction(object : StorageTransaction<Void> {
            override fun execute(context: TransactionContext): Void? { operation(context); return null }
        })

    override fun <T> transaction(transaction: StorageTransaction<T>): CompletionStage<T> = submit { execute(transaction, true) }
    private fun <T> readOnly(operation: StorageTransaction<T>): CompletionStage<T> = submit { execute(operation, false) }

    private fun <T> execute(transaction: StorageTransaction<T>, transactional: Boolean): T {
        try {
            try {
                return run(transaction, transactional)
            } catch (error: Exception) {
                if (transactional || !shouldRetry(error)) throw error
                logger?.warn("数据库连接已失效，正在重建连接并重试一次读取：${error.javaClass.simpleName}: ${error.message}")
                resetConnection()
                val result = run(transaction, false)
                logger?.success("数据库连接已恢复")
                return result
            }
        } catch (error: Exception) {
            throw if (error is StorageException) error else StorageException("Storage transaction failed", error)
        }
    }

    private fun <T> run(transaction: StorageTransaction<T>, transactional: Boolean): T {
        val active = ensureConnection()
        if (!transactional) return transaction.execute(JdbcTransactionContext(active, dialect))
        val previousAutoCommit = active.autoCommit
        active.autoCommit = false
        val result = try {
            transaction.execute(JdbcTransactionContext(active, dialect)).also { active.commit() }
        } catch (error: Exception) {
            try { active.rollback() } catch (rollbackError: SQLException) { error.addSuppressed(rollbackError) }
            try { active.autoCommit = previousAutoCommit } catch (restoreError: SQLException) { error.addSuppressed(restoreError) }
            throw error
        }
        active.autoCommit = previousAutoCommit
        return result
    }

    private fun ensureConnection(): Connection {
        connection?.let { current ->
            try {
                if (!current.isClosed && current.isValid(VALIDATION_TIMEOUT_SECONDS)) return current
            } catch (_: SQLException) { /* 检查失败同样视为断开。 */ }
            logger?.warn("数据库连接已断开，正在自动重连")
            closeQuietly(current)
            connection = null
        }
        return reconnect().also { connection = it }
    }

    private fun reconnect(): Connection {
        try {
            val connected = connect()
            if (reconnectFailureReported) logger?.success("数据库自动重连成功")
            reconnectFailureReported = false
            return connected
        } catch (error: SQLException) {
            if (!reconnectFailureReported) logger?.error("数据库自动重连失败，后续读写都会失败，请检查数据库服务与网络", error)
            reconnectFailureReported = true
            throw error
        }
    }

    private fun connect(): Connection {
        val created = connectionFactory()
        try {
            initializeTables(created)
            return created
        } catch (error: SQLException) {
            try { created.close() } catch (closeError: SQLException) { error.addSuppressed(closeError) }
            throw error
        }
    }

    /** PostgreSQL 的 IF NOT EXISTS 不能串行化系统表写入；锁与两张表须在同一事务内。 */
    private fun initializeTables(created: Connection) {
        if (dialect != SqlDialect.POSTGRESQL) {
            created.createStatement().use { statement ->
                statement.execute(dialect.createKv())
                statement.execute(dialect.createSchema())
            }
            return
        }
        val previousAutoCommit = created.autoCommit
        created.autoCommit = false
        try {
            created.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_xact_lock(hashtext('klib:jdbc:bootstrap'),hashtext(current_schema()))")
                statement.execute(dialect.createKv())
                statement.execute(dialect.createSchema())
            }
            created.commit()
        } catch (error: SQLException) {
            try { created.rollback() } catch (rollbackError: SQLException) { error.addSuppressed(rollbackError) }
            throw error
        }
        created.autoCommit = previousAutoCommit
    }

    private fun resetConnection() { connection?.let(::closeQuietly); connection = null }

    private fun shouldRetry(error: Exception): Boolean {
        if (generateSequence<Throwable>(error) { it.cause }.none { it is SQLException }) return false
        val current = connection ?: return true
        return try { current.isClosed || !current.isValid(VALIDATION_TIMEOUT_SECONDS) }
        catch (_: SQLException) { true }
    }

    private fun <T> submit(operation: () -> T): CompletionStage<T> {
        if (disposed.get()) return CompletableFuture<T>().also { it.completeExceptionally(IllegalStateException("session is disposed")) }
        return CompletableFuture.supplyAsync(operation, executor)
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val closed = CompletableFuture.runAsync(::closeConnection, executor).whenComplete { _, _ -> finishClose() }
        if (Thread.currentThread() === executorThread) return
        try {
            closed.get(30L, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StorageException("Interrupted while closing JDBC storage", error)
        } catch (error: ExecutionException) {
            val cause = error.cause
            throw if (cause is StorageException) cause else StorageException("Could not close JDBC storage", cause)
        } catch (error: TimeoutException) {
            throw StorageException("Timed out closing JDBC storage", error)
        }
    }

    /** 仅供打开线程清理未发布的会话；普通 dispose 仍须排在已接受事务之后。 */
    internal fun disposeBeforePublication() {
        check(Thread.currentThread() === executorThread) { "Unpublished session must close on its opening thread" }
        check(disposed.compareAndSet(false, true)) { "Unpublished session was already disposed" }
        try { closeConnection() } finally { finishClose() }
    }

    private fun closeConnection() {
        try { connection?.close(); connection = null }
        catch (error: SQLException) {
            val failure = StorageException("Could not close JDBC storage", error)
            logger?.error("关闭数据库连接时发生异常", failure)
            throw failure
        }
    }

    private fun finishClose() {
        executor.shutdown()
        onClose(this)
    }

    /** 参数绑定与错误转换集中处理，查询只声明各自的读写逻辑。 */
    private class JdbcTransactionContext(private val connection: Connection, private val dialect: SqlDialect) : TransactionContext {
        override fun get(namespace: String, key: String): Optional<ByteArray> {
            requireName(namespace, "namespace"); requireName(key, "key")
            return statement("SELECT entry_value FROM klib_kv WHERE namespace = ? AND entry_key = ?", "Could not read key") {
                setString(1, namespace); setString(2, key)
                executeQuery().use { if (it.next()) Optional.of(it.getBytes(1).copyOf()) else Optional.empty() }
            }
        }

        override fun put(namespace: String, key: String, value: ByteArray) {
            requireName(namespace, "namespace"); requireName(key, "key")
            statement(dialect.upsertKv(), "Could not write key") {
                setString(1, namespace); setString(2, key); setBytes(3, value.copyOf()); executeUpdate()
            }
        }

        override fun delete(namespace: String, key: String) {
            requireName(namespace, "namespace"); requireName(key, "key")
            statement("DELETE FROM klib_kv WHERE namespace = ? AND entry_key = ?", "Could not delete key") {
                setString(1, namespace); setString(2, key); executeUpdate()
            }
        }

        override fun entries(namespace: String): Map<String, ByteArray> {
            requireName(namespace, "namespace")
            return statement("SELECT entry_key, entry_value FROM klib_kv WHERE namespace = ? ORDER BY entry_key", "Could not list keys") {
                setString(1, namespace)
                executeQuery().use { rows ->
                    val values = LinkedHashMap<String, ByteArray>()
                    while (rows.next()) values[rows.getString(1)] = rows.getBytes(2).copyOf()
                    values
                }
            }
        }

        override fun schemaVersion(schemaName: String): Int {
            requireName(schemaName, "schemaName")
            return statement(dialect.selectSchemaForUpdate(), "Could not read schema version") {
                setString(1, schemaName); executeQuery().use { if (it.next()) it.getInt(1) else 0 }
            }
        }

        override fun schemaVersion(schemaName: String, version: Int) {
            requireName(schemaName, "schemaName")
            statement(dialect.upsertSchema(), "Could not write schema version") {
                setString(1, schemaName); setInt(2, version); executeUpdate()
            }
        }

        private fun <T> statement(sql: String, message: String, operation: PreparedStatement.() -> T): T =
            try { connection.prepareStatement(sql).use(operation) }
            catch (error: SQLException) { throw StorageException(message, error) }

        private fun requireName(value: String?, label: String) {
            require(!value.isNullOrEmpty()) { "$label must not be empty" }
        }
    }

    private companion object {
        const val VALIDATION_TIMEOUT_SECONDS = 5
        fun closeQuietly(connection: Connection) { try { connection.close() } catch (_: SQLException) { } }
    }
}
