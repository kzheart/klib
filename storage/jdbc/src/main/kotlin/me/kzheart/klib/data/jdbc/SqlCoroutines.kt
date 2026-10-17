package me.kzheart.klib.data.jdbc

import kotlinx.coroutines.future.await

/** 仅等待现有执行器；回调是不可挂起函数，read 名称不提供写入权限隔离。 */
suspend fun <T> Database.read(block: SqlClient.() -> T): T = readAsync(SqlWork { it.block() }).await()
suspend fun <T> Database.transaction(block: SqlClient.() -> T): T = transactionAsync(SqlWork { it.block() }).await()
suspend fun <T> Database.transaction(options: SqlTransactionOptions, block: SqlClient.() -> T): T =
    transactionAsync(options, SqlWork { it.block() }).await()

fun SqlClient.execute(sql: String, vararg arguments: Any?): Int = update(sql, SqlArguments.of(*arguments))
fun <T : Any> SqlClient.query(sql: String, vararg arguments: Any?, mapper: (SqlRow) -> T): List<T> =
    query(sql, SqlArguments.of(*arguments), SqlRows.mapper { mapper(it) })
fun <T : Any> SqlClient.queryOne(sql: String, vararg arguments: Any?, mapper: (SqlRow) -> T): T? =
    queryOne(sql, SqlArguments.of(*arguments), SqlRows.mapper { mapper(it) }).orElse(null)
