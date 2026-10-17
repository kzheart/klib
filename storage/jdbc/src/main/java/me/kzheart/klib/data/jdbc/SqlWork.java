package me.kzheart.klib.data.jdbc;

/** 同步 SQL 工作；不得挂起、等待同一执行器，或让 JDBC 资源逃出回调。 */
@FunctionalInterface
public interface SqlWork<T> {
    T execute(SqlClient client) throws Exception;
}
