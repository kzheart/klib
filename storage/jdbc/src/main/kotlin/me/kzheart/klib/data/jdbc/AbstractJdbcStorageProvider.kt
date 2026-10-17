package me.kzheart.klib.data.jdbc

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import me.kzheart.klib.KLogger
import me.kzheart.klib.data.StorageException
import me.kzheart.klib.data.StorageProvider
import me.kzheart.klib.data.StorageSession
import me.kzheart.klib.diagnostic.DiagnosticSource

/** 生命周期组合负责会话集合，方言负责 SQL；外部数据源的所有权始终归调用方。 */
abstract class AbstractJdbcStorageProvider : StorageProvider, DiagnosticSource {
    private val jdbcUrl: String?
    private val username: String?
    private val password: String?
    private val dialect: SqlDialect
    private val logger: KLogger?
    private val dataSource: DataSource?
    private val sessions = Collections.synchronizedSet(HashSet<JdbcStorageSession>())
    private val disposed = AtomicBoolean()

    protected constructor(jdbcUrl: String?, username: String?, password: String?, dialect: SqlDialect) :
        this(jdbcUrl, username, password, dialect, null)

    protected constructor(jdbcUrl: String?, username: String?, password: String?, dialect: SqlDialect, logger: KLogger?) {
        this.jdbcUrl = dialect.applyConnectTimeout(jdbcUrl, CONNECT_TIMEOUT_MILLIS)
        this.username = username
        this.password = password
        this.dialect = dialect
        this.logger = logger
        this.dataSource = null
    }

    protected constructor(dataSource: DataSource, dialect: SqlDialect, logger: KLogger?) {
        this.dataSource = dataSource
        this.dialect = dialect
        this.logger = logger
        this.jdbcUrl = null
        this.username = null
        this.password = null
    }

    override fun open(): CompletionStage<StorageSession> {
        if (disposed.get()) return CompletableFuture<StorageSession>().also {
            it.completeExceptionally(IllegalStateException("provider is disposed"))
        }
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "klib-storage").apply { isDaemon = true } }
        return CompletableFuture.supplyAsync({
            val startedAt = System.currentTimeMillis()
            try {
                val session = JdbcStorageSession(::createConnection, dialect, executor, { sessions.remove(it) }, logger)
                sessions.add(session)
                if (disposed.get()) {
                    // 发布后再次检查，关闭期间刚发布的连接也必须释放。
                    sessions.remove(session)
                    session.dispose()
                    throw IllegalStateException("provider is disposed")
                }
                logger?.success("已连接 ${dialect.displayName()} 存储后端，耗时 ${System.currentTimeMillis() - startedAt} 毫秒")
                session as StorageSession
            } catch (error: SQLException) {
                executor.shutdownNow()
                val message = "无法连接 ${dialect.displayName()} 存储后端（耗时 ${System.currentTimeMillis() - startedAt} 毫秒），请检查数据库地址、端口、账号密码与防火墙设置"
                logger?.error(message, error)
                throw StorageException(message, error)
            }
        }, executor)
    }

    private fun createConnection(): Connection {
        val connection = dataSource?.connection ?: if (username == null) DriverManager.getConnection(jdbcUrl)
            else DriverManager.getConnection(jdbcUrl, username, password)
        try {
            dialect.connectionInit().forEach { sql -> connection.createStatement().use { it.execute(sql) } }
            return connection
        } catch (error: SQLException) {
            try { connection.close() } catch (closeError: SQLException) { error.addSuppressed(closeError) }
            throw error
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val snapshot = synchronized(sessions) { sessions.toList().also { sessions.clear() } }
        snapshot.forEach { it.dispose() }
    }

    override fun diagnosticName(): String = "storage"
    override fun diagnosticSnapshot(): Map<String, *> = linkedMapOf(
        "backend" to dialect.displayName(), "sessions" to sessions.size, "closed" to disposed.get())

    companion object { const val CONNECT_TIMEOUT_MILLIS = 10_000 }
}
