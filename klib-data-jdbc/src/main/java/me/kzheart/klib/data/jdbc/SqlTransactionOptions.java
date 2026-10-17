package me.kzheart.klib.data.jdbc;

import java.sql.Connection;

/** 不可变事务选项；默认保留数据源的隔离级别与 readOnly 设置。 */
public final class SqlTransactionOptions {
    private final Integer isolation;
    private final Boolean readOnly;

    private SqlTransactionOptions(Integer isolation, Boolean readOnly) {
        this.isolation = isolation;
        this.readOnly = readOnly;
    }

    public static SqlTransactionOptions defaults() {
        return new SqlTransactionOptions(null, null);
    }

    public SqlTransactionOptions withIsolation(int value) {
        if (value != Connection.TRANSACTION_READ_UNCOMMITTED
                && value != Connection.TRANSACTION_READ_COMMITTED
                && value != Connection.TRANSACTION_REPEATABLE_READ
                && value != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalArgumentException("Unsupported JDBC transaction isolation: " + value);
        }
        return new SqlTransactionOptions(value, readOnly);
    }

    /** 驱动不支持时失败；readOnly 是 JDBC 提示，不是写入权限控制。 */
    public SqlTransactionOptions withReadOnly(boolean value) {
        return new SqlTransactionOptions(isolation, value);
    }

    Integer isolation() { return isolation; }
    Boolean readOnly() { return readOnly; }
}
