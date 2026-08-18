# 更新日志

## 0.2.0 - 2026-08-18

### 破坏性变更

- Klib 普通模块、Gradle 插件和 Guard API 改为独立版本源，不再假定三者版本相同。
- 推荐的 Gradle 插件接入删除字符串式模块选择，统一使用可由 IDE 补全的类型安全 DSL，例如
  `modules { command(); data() }`。

### 新功能

- 普通模块升级到 `0.2.0`，增加 Maven Central POM、源码包、Javadoc 包、校验和与 PGP 签名接线。
- 新增 Apache-2.0 的最小 `klib-guard-api:0.1.0` 编译期模块，只暴露商品生命周期契约。
- 公共库、Gradle 插件和 Guard runtime 拆分为独立仓库，公共库不再依赖私有源码或构建缓存。

## 0.1.1 - 2026-08-16

### 破坏性变更

- `klib-remote` 从旧诊断、心跳和版本查询接口切换为 Remote v1；旧 API 不再保留。

### 新功能

- 首次正式发布面向 Bukkit/Paper 的 Java 8 Klib 公共模块，覆盖生命周期与调度、配置、语言、命令、
  物品、数据存储、UI、Kether、外部插件 Hook 和 Minecraft 版本能力查询。
- 新增 Remote v1 Java 客户端：支持结构化日志、Incident、操作链、远端 fail-closed 策略和有界离线队列。

### 修复

- 修复 Windows 首次写入默认配置和提交 JSON 存储事务时，目录物理刷盘可能触发
  `AccessDeniedException` 的问题；继续保留临时文件写入与原子替换。
