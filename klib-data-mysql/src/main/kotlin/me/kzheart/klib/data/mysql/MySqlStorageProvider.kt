package me.kzheart.klib.data.mysql

import me.kzheart.klib.KLogger
import me.kzheart.klib.data.jdbc.AbstractJdbcStorageProvider
import me.kzheart.klib.data.jdbc.SqlDialect
import javax.sql.DataSource

/** 调用方拥有外部数据源；提供器只管理自己创建的会话。 */
class MySqlStorageProvider : AbstractJdbcStorageProvider {
    constructor(dataSource: DataSource, logger: KLogger?) : super(dataSource, SqlDialect.MYSQL, logger)
    constructor(jdbcUrl: String?, username: String?, password: String?) : this(jdbcUrl, username, password, null)
    constructor(jdbcUrl: String?, username: String?, password: String?, logger: KLogger?) :
        super(requireUrl(jdbcUrl), username, password, SqlDialect.MYSQL, logger)

    private companion object {
        fun requireUrl(jdbcUrl: String?): String {
            require(!jdbcUrl.isNullOrBlank()) { "jdbcUrl must not be blank" }
            return jdbcUrl
        }
    }
}
