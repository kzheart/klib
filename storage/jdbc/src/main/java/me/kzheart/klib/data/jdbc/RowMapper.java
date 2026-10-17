package me.kzheart.klib.data.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 显式映射当前行；不得调用 next()，不得保留 ResultSet。SQL NULL 由业务显式处理。 */
@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet row) throws SQLException;
}
