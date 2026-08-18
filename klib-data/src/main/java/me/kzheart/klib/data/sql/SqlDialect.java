package me.kzheart.klib.data.sql;

/** SQLite 与 MySQL 语法差异的唯一边界。 */
public enum SqlDialect {
    SQLITE(
            "CREATE TABLE IF NOT EXISTS klib_kv (namespace TEXT NOT NULL, entry_key TEXT NOT NULL, entry_value BLOB NOT NULL, PRIMARY KEY (namespace, entry_key))",
            "CREATE TABLE IF NOT EXISTS klib_schema (schema_name TEXT PRIMARY KEY, schema_version INTEGER NOT NULL)",
            "INSERT INTO klib_kv(namespace, entry_key, entry_value) VALUES (?, ?, ?) ON CONFLICT(namespace, entry_key) DO UPDATE SET entry_value = excluded.entry_value",
            "INSERT INTO klib_schema(schema_name, schema_version) VALUES (?, ?) ON CONFLICT(schema_name) DO UPDATE SET schema_version = excluded.schema_version",
            "SELECT schema_version FROM klib_schema WHERE schema_name = ?",
            new String[]{"PRAGMA busy_timeout = 5000", "PRAGMA journal_mode = WAL"}
    ),
    // entry_key 使用 VARCHAR(191)，使 utf8mb4 复合主键保持在 MySQL 的 767 字节索引限制内；
    // 旧版本创建的表仍保留 VARCHAR(512) 及原字符集，因为 CREATE TABLE IF NOT EXISTS
    // 不会修改现有表。
    MYSQL(
            "CREATE TABLE IF NOT EXISTS klib_kv (namespace VARCHAR(191) NOT NULL, entry_key VARCHAR(191) NOT NULL, entry_value MEDIUMBLOB NOT NULL, PRIMARY KEY (namespace, entry_key)) DEFAULT CHARSET=utf8mb4",
            "CREATE TABLE IF NOT EXISTS klib_schema (schema_name VARCHAR(191) PRIMARY KEY, schema_version INTEGER NOT NULL) DEFAULT CHARSET=utf8mb4",
            "INSERT INTO klib_kv(namespace, entry_key, entry_value) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE entry_value = VALUES(entry_value)",
            "INSERT INTO klib_schema(schema_name, schema_version) VALUES (?, ?) ON DUPLICATE KEY UPDATE schema_version = VALUES(schema_version)",
            "SELECT schema_version FROM klib_schema WHERE schema_name = ? FOR UPDATE",
            new String[0]
    );

    private final String createKv;
    private final String createSchema;
    private final String upsertKv;
    private final String upsertSchema;
    private final String selectSchemaForUpdate;
    private final String[] connectionInit;

    SqlDialect(
            String createKv,
            String createSchema,
            String upsertKv,
            String upsertSchema,
            String selectSchemaForUpdate,
            String[] connectionInit
    ) {
        this.createKv = createKv;
        this.createSchema = createSchema;
        this.upsertKv = upsertKv;
        this.upsertSchema = upsertSchema;
        this.selectSchemaForUpdate = selectSchemaForUpdate;
        this.connectionInit = connectionInit;
    }

    String createKv() {
        return createKv;
    }

    String createSchema() {
        return createSchema;
    }

    String upsertKv() {
        return upsertKv;
    }

    String upsertSchema() {
        return upsertSchema;
    }

    /** 在事务内执行锁定读；不支持行锁时执行普通读取。 */
    String selectSchemaForUpdate() {
        return selectSchemaForUpdate;
    }

    /** 每次新建连接时执行的语句。 */
    String[] connectionInit() {
        return connectionInit.clone();
    }

    /** 面向服主日志的后端名称。 */
    String displayName() {
        switch (this) {
            case MYSQL:
                return "MySQL";
            case SQLITE:
            default:
                return "SQLite";
        }
    }

    /**
     * 为网络后端补齐连接超时参数。
     *
     * <p>不使用 {@link java.sql.DriverManager#setLoginTimeout(int)}：那是整个 JVM 的全局开关，
     * 会连带影响同一服务端里其他插件的 JDBC 连接。URL 参数只作用于本提供器自己的连接。
     * 已在 URL 中显式写明 {@code connectTimeout} 时保持调用方设置不变。
     */
    String applyConnectTimeout(String jdbcUrl, int connectTimeoutMillis) {
        if (this != MYSQL || jdbcUrl == null || jdbcUrl.contains("connectTimeout=")) {
            return jdbcUrl;
        }
        // 仅识别真实的 MySQL/MariaDB 驱动 URL；测试常用 H2 的 MODE=MySQL 模拟，其参数以分号分隔，追加 ? 参数会破坏 URL。
        if (!jdbcUrl.startsWith("jdbc:mysql:") && !jdbcUrl.startsWith("jdbc:mariadb:")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.indexOf('?') >= 0 ? "&" : "?") + "connectTimeout=" + connectTimeoutMillis;
    }
}
