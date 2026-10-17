package me.kzheart.klib.data.jdbc;

import java.sql.SQLException;

/** 显式映射当前行；SqlRow 只在本次回调及其线程内有效。 */
@FunctionalInterface
public interface SqlRowMapper<T> {
    T map(SqlRow row) throws SQLException;
}
