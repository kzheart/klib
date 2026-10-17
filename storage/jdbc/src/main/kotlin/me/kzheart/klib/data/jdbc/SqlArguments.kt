package me.kzheart.klib.data.jdbc

import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** 显式 SQL 类型用于 NULL 和驱动不能推导的参数；无 SQL 拼接。 */
class SqlValue private constructor(internal val value: Any?, internal val sqlType: Int) {
    companion object {
        @JvmStatic fun typed(value: Any?, sqlType: Int): SqlValue = SqlValue(if (value is ByteArray) value.clone() else value, sqlType)
        @JvmStatic fun nullOf(sqlType: Int): SqlValue = SqlValue(null, sqlType)
    }
}

class SqlArguments private constructor() {
    companion object {
        @JvmStatic fun of(vararg values: Any?): SqlParameters {
            val snapshot = values.map { if (it is ByteArray) it.clone() else it }
            return SqlParameters { statement -> snapshot.forEachIndexed { index, value -> bind(statement, index + 1, value) } }
        }
        private fun bind(statement: PreparedStatement, index: Int, value: Any?) {
            when (value) {
                null -> statement.setNull(index, Types.NULL)
                is SqlValue -> if (value.value == null) statement.setNull(index, value.sqlType) else statement.setObject(index, value.value, value.sqlType)
                is ByteArray -> statement.setBytes(index, value.clone())
                is BigDecimal -> statement.setBigDecimal(index, value)
                is Instant -> statement.setTimestamp(index, Timestamp.from(value), Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                is UUID -> {
                    // PostgreSQL 原生 UUID 需要 OTHER；文本存储后端使用标准字符串。
                    if (statement.connection.metaData.databaseProductName == "PostgreSQL") statement.setObject(index, value)
                    else statement.setString(index, value.toString())
                }
                is String -> statement.setString(index, value)
                is Int -> statement.setInt(index, value)
                is Long -> statement.setLong(index, value)
                is Boolean -> statement.setBoolean(index, value)
                is Double -> statement.setDouble(index, value)
                is Float -> statement.setFloat(index, value)
                is Short -> statement.setShort(index, value)
                is Byte -> statement.setByte(index, value)
                else -> throw IllegalArgumentException("Unsupported SQL argument type: ${value.javaClass.name}; use SqlValue.typed or SqlParameters")
            }
        }
    }
}
