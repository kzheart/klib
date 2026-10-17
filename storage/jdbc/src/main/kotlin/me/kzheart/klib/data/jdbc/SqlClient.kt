package me.kzheart.klib.data.jdbc

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Collections
import java.util.Optional

/** 工作回调内的线程受限 JDBC 入口，资源作用域由 use 明确关闭。 */
class SqlClient @JvmOverloads constructor(private val connection: Connection, private val statementTimeoutSeconds: Int = 0) {
    private val owner = Thread.currentThread()
    @Volatile private var active = true

    /** 同一连接供 mapper/batch/generated keys/savepoint 使用；调用方不能改变其事务和生命周期。 */
    fun connection(): Connection { requireActive(); return connection }

    @Throws(SQLException::class)
    fun update(sql: String?, parameters: SqlParameters): Int {
        requireActive()
        return prepare(sql).use { statement ->
            parameters.bind(statement)
            statement.executeUpdate()
        }
    }

    /** 收集有限结果；大结果应在回调内通过 connection() 流式消费。 */
    @Throws(SQLException::class)
    fun <T> query(sql: String?, parameters: SqlParameters, mapper: RowMapper<T>): List<T> {
        requireActive()
        return prepare(sql).use { statement ->
            parameters.bind(statement)
            statement.executeQuery().use { rows ->
                val result = ArrayList<T>()
                while (rows.next()) result.add(java.util.Objects.requireNonNull(mapper.map(rows), "mapper result"))
                Collections.unmodifiableList(result)
            }
        }
    }

    /** 超过一行失败，不静默截断，也不把 null 映射成缺失行。 */
    @Throws(SQLException::class)
    fun <T : Any> queryOne(sql: String?, parameters: SqlParameters, mapper: RowMapper<T>): Optional<T> {
        requireActive()
        return prepare(sql).use { statement ->
            parameters.bind(statement)
            statement.executeQuery().use { rows ->
                if (!rows.next()) Optional.empty() else {
                    val result = java.util.Objects.requireNonNull(mapper.map(rows), "mapper result")
                    if (rows.next()) throw SQLException("Expected at most one row")
                    Optional.of(result)
                }
            }
        }
    }

    fun finish() { active = false }
    private fun prepare(sql: String?): PreparedStatement {
        val statement = connection.prepareStatement(requireSql(sql))
        try {
            if (statementTimeoutSeconds > 0) statement.queryTimeout = statementTimeoutSeconds
            return statement
        } catch (failure: Throwable) {
            try { statement.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    private fun requireActive() {
        check(active && owner === Thread.currentThread()) { "SQL client is only valid on its callback thread" }
    }
    private fun requireSql(sql: String?): String {
        require(!sql.isNullOrBlank()) { "sql must not be blank" }
        return sql
    }
}
