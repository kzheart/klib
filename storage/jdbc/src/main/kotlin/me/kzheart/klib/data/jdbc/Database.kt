package me.kzheart.klib.data.jdbc

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import me.kzheart.klib.KLogger
import me.kzheart.klib.data.StorageException
import me.kzheart.klib.scope.Disposable

/** SQL 执行与自有数据源组成一个资源；执行器停止接收并排空后才关闭自有池。 */
class Database internal constructor(private val source: DatabaseSource, private val config: DatabaseConfig,
                                   private val logger: KLogger) : Disposable, AutoCloseable {
    internal val executor = JdbcExecutor(source, config.workers, config.queueCapacity, config.statementTimeoutSeconds)
    private var closing = false
    private val closed = CompletableFuture<Void>()
    fun <T> readAsync(work: SqlWork<T>): CompletionStage<T> = observe(executor.work(work))
    fun <T> transactionAsync(work: SqlWork<T>): CompletionStage<T> = transactionAsync(SqlTransactionOptions.defaults(), work)
    fun <T> transactionAsync(options: SqlTransactionOptions, work: SqlWork<T>): CompletionStage<T> = observe(executor.transaction(options, work))
    private fun <T> observe(result: CompletionStage<T>): CompletionStage<T> {
        result.whenComplete { _, failure -> if (failure != null) logger.warn("Database operation failed; inspect returned stage (no automatic retry)") }
        return result
    }
    @Synchronized fun closeAsync(): CompletionStage<Void> {
        if (!closing) {
            closing = true
            executor.closeAsync().whenComplete { _, failure ->
                var error = failure
                try { source.dispose() } catch (cleanup: Throwable) { if (error == null) error = cleanup else error.addSuppressed(cleanup) }
                if (error == null) closed.complete(null) else closed.completeExceptionally(error)
            }
        }
        return closed.thenApply { null }
    }
    override fun dispose() {
        val closing = closeAsync()
        if (executor.isWorkerThread()) throw StorageException("Cannot wait for database close inside its SQL callback; use closeAsync")
        try { closing.toCompletableFuture().get(config.closeTimeout.toMillis(), TimeUnit.MILLISECONDS) }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StorageException("Interrupted while draining database; resources may still be active", interrupted)
        } catch (failure: Exception) { throw StorageException("Database close did not complete; resources may still be active", failure) }
    }
    override fun close() = dispose()
}

/** 首次连接在既有 SQL 执行器内打开，关闭只在该执行器排空后发生。 */
internal class DatabaseSource(private val factory: JdbcSourceFactory) : DataSource, Disposable {
    private var opened: JdbcSource? = null
    private var closed = false
    @Synchronized private fun source(): DataSource {
        check(!closed) { "Database source is closed" }
        return (opened ?: factory.open().also { opened = it }).dataSource()
    }
    @Synchronized override fun dispose() { if (!closed) { closed = true; opened?.dispose() } }
    override fun getConnection(): Connection = source().connection
    override fun getConnection(user: String?, password: String?): Connection = source().getConnection(user, password)
    override fun getLogWriter(): PrintWriter? = source().logWriter
    override fun setLogWriter(writer: PrintWriter?) { source().logWriter = writer }
    override fun getLoginTimeout(): Int = source().loginTimeout
    override fun setLoginTimeout(seconds: Int) { source().loginTimeout = seconds }
    override fun getParentLogger(): Logger = source().parentLogger
    override fun isWrapperFor(type: Class<*>?): Boolean = source().isWrapperFor(type)
    override fun <T> unwrap(type: Class<T>?): T = source().unwrap(type)
}
