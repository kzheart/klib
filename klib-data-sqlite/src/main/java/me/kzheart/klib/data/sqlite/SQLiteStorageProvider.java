package me.kzheart.klib.data.sqlite;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.jdbc.AbstractJdbcStorageProvider;
import me.kzheart.klib.data.jdbc.SqlDialect;

import java.nio.file.Path;

/** 基于 SQLite 的存储提供器。 */
public final class SQLiteStorageProvider extends AbstractJdbcStorageProvider {
    /** 不写出日志的构造方式；生产环境建议使用带 {@link KLogger} 的重载。 */
    public SQLiteStorageProvider(Path file) {
        this(file, null);
    }

    /**
     * 推荐的构造方式：把连接、重连和保存失败写入服务端控制台。
     *
     * @param logger 服主可见的日志通道，为 {@code null} 时保持静默
     */
    public SQLiteStorageProvider(Path file, KLogger logger) {
        super("jdbc:sqlite:" + requireFile(file).toAbsolutePath(), null, null, SqlDialect.SQLITE, logger);
    }

    private static Path requireFile(Path file) {
        if (file == null) {
            throw new NullPointerException("file");
        }
        return file;
    }
}
