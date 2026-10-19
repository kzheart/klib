package me.kzheart.klib.data.pool

import java.sql.Connection
import java.time.Duration
import java.util.Properties
import me.kzheart.klib.data.jdbc.*
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.scope.observeOperation

/** Connection settings for a shared PostgreSQL business database. Credentials are never rendered. */
class PostgreSqlDatabaseOptions(
    val url: String,
    private val user: String,
    private val password: String,
    private val applicationName: String,
    val workers: Int = 4,
    val queueCapacity: Int = 256,
    val connectionTimeout: Duration = Duration.ofSeconds(5),
    val socketTimeout: Duration = Duration.ofSeconds(30),
    val statementTimeout: Duration = Duration.ofSeconds(15),
    val lockTimeout: Duration = Duration.ofSeconds(5),
    val idleTransactionTimeout: Duration = Duration.ofSeconds(60),
    val closeTimeout: Duration = Duration.ofSeconds(20),
) {
    init {
        require(url.startsWith("jdbc:postgresql:")) { "A PostgreSQL JDBC URL is required" }
        require(workers > 0 && queueCapacity > 0) { "Workers and queue capacity must be positive" }
        require(connectionTimeout.toMillis() > 0 && socketTimeout.seconds > 0 && closeTimeout.toMillis() > 0)
        require(listOf(statementTimeout, lockTimeout, idleTransactionTimeout).all { !it.isNegative && it.toMillis() <= Int.MAX_VALUE })
    }

    /** Fresh properties for a pooled or dedicated LISTEN connection; never share mutable credentials. */
    fun properties(): Properties = Properties().apply {
        setProperty("user", user)
        setProperty("password", password)
        setProperty("ApplicationName", applicationName)
        setProperty("connectTimeout", maxOf(1, connectionTimeout.seconds).toString())
        setProperty("socketTimeout", socketTimeout.seconds.toString())
        setProperty("tcpKeepAlive", "true")
    }
}

/**
 * Managed pool, transaction-local timeouts and serialized schema initialization. Scope owns shutdown;
 * business code still owns its schema, table names, transaction facts and notification lifecycle.
 */
class PostgreSqlDatabase(scope: Scope, private val options: PostgreSqlDatabaseOptions, loader: ClassLoader) {
    private val opened = Databases.open(scope, DatabaseConfig.builder(JdbcPools.dbcp(
        JdbcDataSources.driver("org.postgresql.Driver", loader, options.url, options.properties()),
        JdbcPoolOptions(options.workers, options.connectionTimeout, Duration.ofSeconds(5)),
    )).workers(options.workers).queueCapacity(options.queueCapacity)
        // JDBC query timeout must permit the longer schema transaction; 0 in PostgreSQL means unlimited.
        .statementTimeout(if (options.statementTimeout.isZero) Duration.ofSeconds(Int.MAX_VALUE.toLong())
            else maxOf(options.statementTimeout, Duration.ofSeconds(60)))
        .closeTimeout(options.closeTimeout).build())

    suspend fun <T> read(work: SqlWork<T>): T = opened.observeOperation().readAsync(work).observeOperation()

    suspend fun <T> transaction(work: SqlWork<T>): T = transaction(false, work)

    /** Advisory transaction lock is released by PostgreSQL on commit or rollback, including failure. */
    suspend fun <T> migrate(lockKey: String, work: SqlWork<T>): T = transaction(true, SqlWork { sql ->
        sql.query("SELECT pg_advisory_xact_lock(hashtext(?))", lockKey) { true }
        work.execute(sql)
    })

    private suspend fun <T> transaction(migration: Boolean, work: SqlWork<T>): T =
        opened.observeOperation().transactionAsync(WRITE, SqlWork { sql ->
            sql.execute("SET LOCAL statement_timeout = ${if (migration) 60000 else options.statementTimeout.toMillis()}")
            sql.execute("SET LOCAL lock_timeout = ${if (migration) 15000 else options.lockTimeout.toMillis()}")
            sql.execute("SET LOCAL idle_in_transaction_session_timeout = ${options.idleTransactionTimeout.toMillis()}")
            work.execute(sql)
        }).observeOperation()

    private companion object {
        val WRITE: SqlTransactionOptions = SqlTransactionOptions.defaults().withIsolation(Connection.TRANSACTION_READ_COMMITTED)
    }
}
