# PostgreSQL 实库回归

`check` 中的 PostgreSQL 集成用例使用真实 pgJDBC。

## 运行方式

- CI 和发布门禁自动启动隔离的 PostgreSQL 服务。
- 独立本地执行时，设置以下环境变量后运行 `:klib-data-postgresql:test --rerun-tasks`：
  - `KLIB_POSTGRESQL_TEST_URL`
  - `KLIB_POSTGRESQL_TEST_USER`
  - `KLIB_POSTGRESQL_TEST_PASSWORD`
- 未设置 URL 的普通本地检查会跳过实库用例，**不能**据此声明数据库连接已验证。

## 覆盖范围

- 二进制与 Unicode 值
- 覆盖写
- 删除
- 迁移幂等
- 数据和版本号一起回滚
- 关闭再打开

模块用法见 [Data](../modules/data.md)。
