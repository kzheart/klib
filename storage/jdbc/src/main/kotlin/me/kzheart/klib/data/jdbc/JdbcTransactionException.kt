package me.kzheart.klib.data.jdbc

/** 事务或清理失败；提交回包丢失不能被误判为可以重试的失败。 */
class JdbcTransactionException(private val outcome: Outcome, cause: Throwable) :
    RuntimeException("JDBC transaction failed (outcome=$outcome)", cause) {
    enum class Outcome {
        /** 尚未开始事务。 */ NOT_STARTED,
        /** 回调失败且 rollback 已返回。 */ ROLLED_BACK,
        /** commit 已返回，但连接复原或释放失败。 */ COMMITTED,
        /** commit 抛错或 rollback 失败，结果不能确认。 */ UNKNOWN
    }
    fun outcome(): Outcome = outcome
    private companion object { private const val serialVersionUID = 1L }
}
