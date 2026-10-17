package me.kzheart.klib.data.jdbc;

/** 只在数据库执行器的打开阶段调用；失败前自行回收尚未交付的资源。 */
@FunctionalInterface
public interface JdbcSourceFactory {
    JdbcSource open() throws Exception;
}
