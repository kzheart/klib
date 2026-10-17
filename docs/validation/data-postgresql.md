# PostgreSQL 实库回归

`check` 中的 PostgreSQL 集成用例使用真实 pgJDBC。

## 运行方式

- CI 和发布门禁自动启动隔离的 PostgreSQL 服务。
- 独立本地执行时，设置以下环境变量后运行 `:klib-data-postgresql:test :klib-data-pool:test`；两者都禁用实库结果缓存：
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
- 类型化 KV 编解码、缺失值与同一 KV 事务回滚
- 受管 SQL 的 UUID／UTC Instant／Decimal／二进制／SQL NULL 映射、同连接事务、查询超时、池复用与物理连接关闭
- LISTEN 初始订阅竞态、标识符转义、真实后端断开和重订阅、重连耗尽、队列溢出、关闭排空与线程清理

测试账号仅使用隔离库；LISTEN 用例会按唯一 ApplicationName 找到并终止自己的后端以验证重连，需要该测试角色具备对应权限。
本地临时 PostgreSQL 应只监听 loopback，使用独立数据目录、端口与 socket 目录；无论测试成功、失败或取消都停服清理，不复用现有业务服务。

模块用法见 [Data](../modules/data.md)。
