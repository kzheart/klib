package me.kzheart.klib.data.jdbc

import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** 必填读取拒绝 SQL NULL，不把 0、false 或空字符串伪造为缺失值。 */
class SqlRow internal constructor(private val rows: ResultSet) {
    private val owner = Thread.currentThread()
    private var active = true
    private fun checkActive() { check(active && Thread.currentThread() === owner) { "SQL row is only valid inside its mapper callback" } }
    private fun <T : Any> required(column: String, value: T?): T = value ?: throw SQLException("Required SQL column '$column' is NULL")
    fun string(column: String): String = required(column, stringOrNull(column))
    fun stringOrNull(column: String): String? { checkActive(); return rows.getString(column) }
    @JvmName("intValue") fun int(column: String): Int = required(column, intOrNull(column))
    fun intOrNull(column: String): Int? { checkActive(); val n = rows.getInt(column); return if (rows.wasNull()) null else n }
    @JvmName("longValue") fun long(column: String): Long = required(column, longOrNull(column))
    fun longOrNull(column: String): Long? { checkActive(); val n = rows.getLong(column); return if (rows.wasNull()) null else n }
    @JvmName("booleanValue") fun boolean(column: String): Boolean = required(column, booleanOrNull(column))
    fun booleanOrNull(column: String): Boolean? { checkActive(); val n = rows.getBoolean(column); return if (rows.wasNull()) null else n }
    fun decimal(column: String): BigDecimal = required(column, decimalOrNull(column))
    fun decimalOrNull(column: String): BigDecimal? { checkActive(); return rows.getBigDecimal(column) }
    fun bytes(column: String): ByteArray = required(column, bytesOrNull(column))
    fun bytesOrNull(column: String): ByteArray? { checkActive(); return rows.getBytes(column)?.clone() }
    fun uuid(column: String): UUID = required(column, uuidOrNull(column))
    fun uuidOrNull(column: String): UUID? { checkActive(); return rows.getString(column)?.let(UUID::fromString) }
    fun instant(column: String): Instant = required(column, instantOrNull(column))
    fun instantOrNull(column: String): Instant? {
        checkActive()
        return rows.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone("UTC")))?.toInstant()
    }
    internal fun finish() { active = false }
}

class SqlRows private constructor() {
    companion object {
        @JvmStatic fun <T> mapper(mapper: SqlRowMapper<T>): RowMapper<T> = RowMapper { result ->
            val row = SqlRow(result)
            try { mapper.map(row) } finally { row.finish() }
        }
    }
}
