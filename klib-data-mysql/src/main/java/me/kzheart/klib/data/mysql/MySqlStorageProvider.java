package me.kzheart.klib.data.mysql;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.jdbc.AbstractJdbcStorageProvider;
import me.kzheart.klib.data.jdbc.SqlDialect;

/** 基于 MySQL 的存储提供器。 */
public final class MySqlStorageProvider extends AbstractJdbcStorageProvider {
    /** 不写出日志的构造方式；生产环境建议使用带 {@link KLogger} 的重载。 */
    public MySqlStorageProvider(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, null);
    }

    /**
     * 推荐的构造方式：把连接、重连和保存失败写入服务端控制台。
     *
     * @param logger 服主可见的日志通道，为 {@code null} 时保持静默
     */
    public MySqlStorageProvider(String jdbcUrl, String username, String password, KLogger logger) {
        super(requireUrl(jdbcUrl), username, password, SqlDialect.MYSQL, logger);
    }

    private static String requireUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        return jdbcUrl;
    }
}
