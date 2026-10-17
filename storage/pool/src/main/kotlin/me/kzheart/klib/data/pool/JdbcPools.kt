package me.kzheart.klib.data.pool

import java.time.Duration
import javax.sql.DataSource
import me.kzheart.klib.data.jdbc.JdbcSource
import me.kzheart.klib.data.jdbc.JdbcSourceFactory
import me.kzheart.klib.scope.Disposable
import org.apache.commons.dbcp2.DataSourceConnectionFactory
import org.apache.commons.dbcp2.PoolableConnection
import org.apache.commons.dbcp2.PoolableConnectionFactory
import org.apache.commons.dbcp2.PoolingDataSource
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig

/** 可选 DBCP 适配，只创建并关闭自己的连接池；底层 DataSource 仍归调用方。 */
class JdbcPools private constructor() {
    companion object {
        @JvmStatic fun dbcp(source: DataSource, options: JdbcPoolOptions): JdbcSourceFactory = JdbcSourceFactory {
            val factory = PoolableConnectionFactory(DataSourceConnectionFactory(source), null)
            factory.setValidationQueryTimeout(options.validationTimeout)
            val settings = GenericObjectPoolConfig<PoolableConnection>().apply {
                maxTotal = options.maxConnections
                maxIdle = options.maxConnections
                minIdle = 0
                jmxEnabled = false
                testOnBorrow = true
                setMaxWait(options.acquireTimeout)
            }
            val pool = GenericObjectPool(factory, settings)
            try {
                factory.pool = pool
                JdbcSource.owned(PoolingDataSource(pool), Disposable { pool.close() })
            } catch (failure: Throwable) {
                try { pool.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}

class JdbcPoolOptions @JvmOverloads constructor(internal val maxConnections: Int = 4,
                                               internal val acquireTimeout: Duration = Duration.ofSeconds(10),
                                               internal val validationTimeout: Duration = Duration.ofSeconds(5)) {
    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(acquireTimeout.toMillis() > 0 && validationTimeout.seconds > 0) { "pool timeouts must be positive" }
    }
}
