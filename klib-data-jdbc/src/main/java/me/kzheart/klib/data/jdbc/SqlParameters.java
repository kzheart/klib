package me.kzheart.klib.data.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** 使用 JDBC 的一基参数索引和显式类型绑定，不拼接输入、不猜测 NULL 类型。 */
@FunctionalInterface
public interface SqlParameters {
    void bind(PreparedStatement statement) throws SQLException;

    static SqlParameters none() {
        return statement -> { };
    }
}
