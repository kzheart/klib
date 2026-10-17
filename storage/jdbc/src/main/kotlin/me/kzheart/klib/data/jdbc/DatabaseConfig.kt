package me.kzheart.klib.data.jdbc

import java.time.Duration
import java.util.Properties
import javax.sql.DataSource
import me.kzheart.klib.KLogger

/** 构建期校验；不连接、不持有凭据的可打印表示，不推导业务表结构。 */
class DatabaseConfig private constructor(builder: Builder) {
    internal val factory = builder.factory
    internal val workers = builder.workers
    internal val queueCapacity = builder.queueCapacity
    internal val statementTimeoutSeconds = builder.statementTimeoutSeconds
    internal val closeTimeout = builder.closeTimeout
    internal val migrations = builder.migrations
    internal val targetVersion = builder.targetVersion
    internal val logger = builder.logger

    class Builder internal constructor(internal val factory: JdbcSourceFactory) {
        internal var workers = 2
        internal var queueCapacity = 128
        internal var statementTimeoutSeconds = 30
        internal var closeTimeout = Duration.ofSeconds(30)
        internal var migrations: SqlMigrationEngine? = null
        internal var targetVersion = 0
        internal var logger: KLogger? = null
        fun workers(value: Int): Builder = apply { require(value > 0); workers = value }
        fun queueCapacity(value: Int): Builder = apply { require(value > 0); queueCapacity = value }
        fun statementTimeout(value: Duration): Builder = apply {
            require(!value.isNegative && !value.isZero)
            val seconds = Math.addExact(value.seconds, if (value.nano > 0) 1L else 0L)
            require(seconds <= Int.MAX_VALUE); statementTimeoutSeconds = seconds.toInt()
        }
        fun closeTimeout(value: Duration): Builder = apply { require(value.toMillis() > 0); closeTimeout = value }
        fun migrations(engine: SqlMigrationEngine, target: Int): Builder = apply {
            require(target >= 0); migrations = engine; targetVersion = target
        }
        fun logger(value: KLogger): Builder = apply { logger = value }
        fun build(): DatabaseConfig = DatabaseConfig(this)
    }
    companion object {
        @JvmStatic fun builder(factory: JdbcSourceFactory): Builder = Builder(factory)
        @JvmStatic fun external(source: DataSource): Builder = Builder { JdbcSource.external(source) }
        /** 网络超时通过驱动 properties / URL 配置；未知驱动不猜测超时参数或修改 JVM 全局配置。 */
        @JvmStatic @JvmOverloads fun driver(driverClass: String, loader: ClassLoader, url: String, properties: Properties,
                                             connectTimeout: Duration = Duration.ofSeconds(10)): Builder {
            require(connectTimeout.toMillis() in 1..Int.MAX_VALUE.toLong()) { "connectTimeout must fit positive milliseconds" }
            val configured = when {
                url.startsWith("jdbc:postgresql:") -> SqlDialect.POSTGRESQL.applyConnectTimeout(url, connectTimeout.toMillis().toInt())
                url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:") -> SqlDialect.MYSQL.applyConnectTimeout(url, connectTimeout.toMillis().toInt())
                else -> url
            }
            val source = JdbcDataSources.driver(driverClass, loader, configured, properties)
            return external(source)
        }
    }
}
