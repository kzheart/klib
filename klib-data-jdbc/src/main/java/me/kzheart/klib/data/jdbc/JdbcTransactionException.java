package me.kzheart.klib.data.jdbc;

/** 事务失败或清理失败；结果用于避免把提交回包丢失误当作可以重试的失败。 */
public final class JdbcTransactionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Outcome {
        /** 尚未开始事务。 */
        NOT_STARTED,
        /** 回调失败且 JDBC rollback 已成功返回。 */
        ROLLED_BACK,
        /** commit 已成功返回，但连接复原或释放失败。 */
        COMMITTED,
        /** commit 抛错或 rollback 失败，不能确认业务写入结果。 */
        UNKNOWN
    }

    private final Outcome outcome;

    JdbcTransactionException(Outcome outcome, Throwable cause) {
        super("JDBC transaction failed (outcome=" + outcome + ")", cause);
        this.outcome = outcome;
    }

    public Outcome outcome() {
        return outcome;
    }
}
