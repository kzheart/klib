package me.kzheart.klib.data.jdbc

/** SQLite、MySQL 与 PostgreSQL 语法差异的唯一边界。 */
enum class SqlDialect(
    private val createKv: String,
    private val createSchema: String,
    private val upsertKv: String,
    private val upsertSchema: String,
    private val selectSchemaForUpdate: String,
    private val connectionInit: Array<String>
) {
    SQLITE(
            "CREATE TABLE IF NOT EXISTS klib_kv (namespace TEXT NOT NULL, entry_key TEXT NOT NULL, entry_value BLOB NOT NULL, PRIMARY KEY (namespace, entry_key))",
            "CREATE TABLE IF NOT EXISTS klib_schema (schema_name TEXT PRIMARY KEY, schema_version INTEGER NOT NULL)",
            "INSERT INTO klib_kv(namespace, entry_key, entry_value) VALUES (?, ?, ?) ON CONFLICT(namespace, entry_key) DO UPDATE SET entry_value = excluded.entry_value",
            "INSERT INTO klib_schema(schema_name, schema_version) VALUES (?, ?) ON CONFLICT(schema_name) DO UPDATE SET schema_version = excluded.schema_version",
            "SELECT schema_version FROM klib_schema WHERE schema_name = ?",
            arrayOf("PRAGMA busy_timeout = 5000", "PRAGMA journal_mode = WAL")
    ),
    // entry_key 使用 VARCHAR(191)，使 utf8mb4 复合主键保持在 MySQL 的 767 字节索引限制内；
    // 旧版本创建的表仍保留 VARCHAR(512) 及原字符集，因为 CREATE TABLE IF NOT EXISTS
    // 不会修改现有表。
    POSTGRESQL(
            "CREATE TABLE IF NOT EXISTS klib_kv (namespace VARCHAR(191) NOT NULL, entry_key VARCHAR(191) NOT NULL, entry_value BYTEA NOT NULL, PRIMARY KEY (namespace, entry_key))",
            "CREATE TABLE IF NOT EXISTS klib_schema (schema_name VARCHAR(191) PRIMARY KEY, schema_version INTEGER NOT NULL)",
            "INSERT INTO klib_kv(namespace, entry_key, entry_value) VALUES (?, ?, ?) ON CONFLICT(namespace, entry_key) DO UPDATE SET entry_value = excluded.entry_value",
            "INSERT INTO klib_schema(schema_name, schema_version) VALUES (?, ?) ON CONFLICT(schema_name) DO UPDATE SET schema_version = excluded.schema_version",
            "SELECT schema_version FROM klib_schema WHERE schema_name = ? FOR UPDATE",
            emptyArray()
    ),
    MYSQL(
            "CREATE TABLE IF NOT EXISTS klib_kv (namespace VARCHAR(191) NOT NULL, entry_key VARCHAR(191) NOT NULL, entry_value MEDIUMBLOB NOT NULL, PRIMARY KEY (namespace, entry_key)) DEFAULT CHARSET=utf8mb4",
            "CREATE TABLE IF NOT EXISTS klib_schema (schema_name VARCHAR(191) PRIMARY KEY, schema_version INTEGER NOT NULL) DEFAULT CHARSET=utf8mb4",
            "INSERT INTO klib_kv(namespace, entry_key, entry_value) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE entry_value = VALUES(entry_value)",
            "INSERT INTO klib_schema(schema_name, schema_version) VALUES (?, ?) ON DUPLICATE KEY UPDATE schema_version = VALUES(schema_version)",
            "SELECT schema_version FROM klib_schema WHERE schema_name = ? FOR UPDATE",
            emptyArray()
    );


    fun createKv(): String = createKv
    fun createSchema(): String = createSchema
    fun upsertKv(): String = upsertKv
    fun upsertSchema(): String = upsertSchema
    fun selectSchemaForUpdate(): String = selectSchemaForUpdate
    fun connectionInit(): Array<String> = connectionInit.copyOf()
    fun displayName(): String = when (this) {
        POSTGRESQL -> "PostgreSQL"
        MYSQL -> "MySQL"
        SQLITE -> "SQLite"
    }

    /** 超时参数只影响当前连接，不改 DriverManager 的 JVM 全局配置。 */
    fun applyConnectTimeout(jdbcUrl: String?, connectTimeoutMillis: Int): String? {
        if (jdbcUrl == null || Regex(".*[?&]connectTimeout=.*").matches(jdbcUrl)) return jdbcUrl
        val timeout = when {
            this == POSTGRESQL && jdbcUrl.startsWith("jdbc:postgresql:") ->
                maxOf(1L, (connectTimeoutMillis + 999L) / 1000L)
            this == MYSQL && (jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:mariadb:")) ->
                connectTimeoutMillis.toLong()
            else -> return jdbcUrl
        }
        return jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "connectTimeout=" + timeout
    }
}
