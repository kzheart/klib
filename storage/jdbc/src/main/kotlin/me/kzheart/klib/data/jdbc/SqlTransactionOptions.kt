package me.kzheart.klib.data.jdbc

import java.sql.Connection

/** 不可变事务选项；默认保留数据源的隔离级别与 readOnly 设置。 */
class SqlTransactionOptions private constructor(private val isolation: Int?, private val readOnly: Boolean?) {
    fun withIsolation(value: Int): SqlTransactionOptions {
        require(value == Connection.TRANSACTION_READ_UNCOMMITTED || value == Connection.TRANSACTION_READ_COMMITTED ||
            value == Connection.TRANSACTION_REPEATABLE_READ || value == Connection.TRANSACTION_SERIALIZABLE) {
            "Unsupported JDBC transaction isolation: $value"
        }
        return SqlTransactionOptions(value, readOnly)
    }
    /** readOnly 是 JDBC 提示，不是写入权限控制；驱动不支持时失败。 */
    fun withReadOnly(value: Boolean): SqlTransactionOptions = SqlTransactionOptions(isolation, value)
    fun isolation(): Int? = isolation
    fun readOnly(): Boolean? = readOnly
    companion object {
        @JvmStatic fun defaults(): SqlTransactionOptions = SqlTransactionOptions(null, null)
    }
}
