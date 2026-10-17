package me.kzheart.klib.data.jdbc;

import javax.sql.DataSource;

/**
 * 外部迁移引擎适配边界。Klib 只作启动版本门禁，不解析 SQL 或维护历史表。
 * 实现负责持久化版本、checksum、并发锁、失败恢复和数据库 DDL 语义；
 * migrate 必须在引擎锁内重新核验版本，拒绝降级，不得关闭调用方的数据源。
 */
public interface SqlMigrationEngine {
    /** 返回已经成功应用的非负整数版本；未初始化为 0。 */
    int currentVersion(DataSource dataSource) throws Exception;

    /** 返回前必须完成到目标版本的迁移；错误应原样抛出，不能伪报成功。 */
    void migrate(DataSource dataSource, int targetVersion) throws Exception;
}
