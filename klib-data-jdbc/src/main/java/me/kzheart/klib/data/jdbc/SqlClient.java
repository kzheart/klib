package me.kzheart.klib.data.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 一个工作回调内、线程受限的 JDBC 访问入口。 */
public final class SqlClient {
    private final Connection connection;
    private final Thread owner = Thread.currentThread();
    private volatile boolean active = true;

    SqlClient(Connection connection) {
        this.connection = connection;
    }

    /**
     * 供成熟 mapper、batch、generated keys、savepoint 使用的同一连接。
     * 调用方必须关闭自己创建的 statement/result set；不得关闭连接、改变连接状态、
     * 提交整个事务，或在回调外使用连接。
     */
    public Connection connection() {
        requireActive();
        return connection;
    }

    public int update(String sql, SqlParameters parameters) throws SQLException {
        requireActive();
        Objects.requireNonNull(parameters, "parameters");
        try (PreparedStatement statement = connection.prepareStatement(requireSql(sql))) {
            parameters.bind(statement);
            return statement.executeUpdate();
        }
    }

    /** 收集有限结果；大结果用 connection() 在回调内部显式流式消费。 */
    public <T> List<T> query(String sql, SqlParameters parameters, RowMapper<T> mapper) throws SQLException {
        requireActive();
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(mapper, "mapper");
        try (PreparedStatement statement = connection.prepareStatement(requireSql(sql))) {
            parameters.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<T>();
                while (rows.next()) {
                    result.add(Objects.requireNonNull(mapper.map(rows), "mapper result"));
                }
                return Collections.unmodifiableList(result);
            }
        }
    }

    /** 零行返回 empty；超过一行失败，不静默截断，也不把 null 映射成缺失行。 */
    public <T> Optional<T> queryOne(String sql, SqlParameters parameters, RowMapper<T> mapper)
            throws SQLException {
        requireActive();
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(mapper, "mapper");
        try (PreparedStatement statement = connection.prepareStatement(requireSql(sql))) {
            parameters.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                T result = Objects.requireNonNull(mapper.map(rows), "mapper result");
                if (rows.next()) {
                    throw new SQLException("Expected at most one row");
                }
                return Optional.of(result);
            }
        }
    }

    void finish() {
        active = false;
    }

    private void requireActive() {
        if (!active || owner != Thread.currentThread()) {
            throw new IllegalStateException("SQL client is only valid on its callback thread");
        }
    }

    private static String requireSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        return sql;
    }
}
