package me.kzheart.klib.data.postgresql;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.jdbc.AbstractJdbcStorageProvider;
import me.kzheart.klib.data.jdbc.SqlDialect;
import javax.sql.DataSource;

/** 基于 PostgreSQL 的存储提供器。 */
public final class PostgreSqlStorageProvider extends AbstractJdbcStorageProvider {
    /** 使用调用方持有的数据源或连接池；资源关闭顺序为 session、provider、dataSource。 */
    public PostgreSqlStorageProvider(DataSource dataSource, KLogger logger) {
        super(dataSource, SqlDialect.POSTGRESQL, logger);
    }
    /** 不写出日志的构造方式；生产环境建议使用带 {@link KLogger} 的重载。 */
    public PostgreSqlStorageProvider(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, null);
    }

    /**
     * 推荐的构造方式：把连接、重连和保存失败写入服务端控制台。
     *
     * @param logger 服主可见的日志通道，为 {@code null} 时保持静默
     */
    public PostgreSqlStorageProvider(String jdbcUrl, String username, String password, KLogger logger) {
        super(requireUrl(jdbcUrl), username, password, SqlDialect.POSTGRESQL, logger);
    }

    private static String requireUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("jdbcUrl must use jdbc:postgresql:");
        }
        return jdbcUrl;
    }
}
