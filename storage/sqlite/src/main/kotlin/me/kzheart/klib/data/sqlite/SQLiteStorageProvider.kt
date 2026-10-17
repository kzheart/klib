package me.kzheart.klib.data.sqlite

import me.kzheart.klib.KLogger
import me.kzheart.klib.data.jdbc.AbstractJdbcStorageProvider
import me.kzheart.klib.data.jdbc.SqlDialect
import java.nio.file.Path

/** 基于 SQLite 的存储提供器。 */
class SQLiteStorageProvider @JvmOverloads constructor(file: Path, logger: KLogger? = null) :
    AbstractJdbcStorageProvider("jdbc:sqlite:${file.toAbsolutePath()}", null, null, SqlDialect.SQLITE, logger)
