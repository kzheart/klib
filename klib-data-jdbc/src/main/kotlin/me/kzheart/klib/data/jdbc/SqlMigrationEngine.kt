package me.kzheart.klib.data.jdbc

import javax.sql.DataSource

/** 外部迁移引擎适配边界。实现负责版本、checksum、并发锁、失败恢复和数据库 DDL 语义。 */
interface SqlMigrationEngine {
    /** 返回已成功应用的非负整数版本；未初始化为 0。 */
    @Throws(Exception::class)
    fun currentVersion(dataSource: DataSource): Int

    /** 在引擎锁内重新核验版本并完成迁移，拒绝降级，不关闭调用方数据源。 */
    @Throws(Exception::class)
    fun migrate(dataSource: DataSource, targetVersion: Int)
}
