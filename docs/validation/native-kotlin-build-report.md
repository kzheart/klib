# 原生 Kotlin 构建验证记录

日期：2026-10-16。此记录覆盖库工作树；最终可运行重定位 JAR 与真实客户端验证须另看整体验收记录，不能由本页的单元测试结果推导。

## 已执行

| 范围 | 结果 | 证据与执行方法 |
| --- | --- | --- |
| JDK 21 全量 | PASS，830 测试、0 失败、0 跳过 | `clean check publishToMavenLocal centralDryRunBundle`；各模块 `build/test-results/test/TEST-*.xml` |
| Java 8 真运行 | PASS，同一组 830 测试、0 失败、0 跳过 | `check -PtestJavaVersion=8`，使用 Temurin 8u504；不是仅检查字节码 |
| 公开产物与依赖 | PASS | `checkJava8Compatibility` 与 `scripts/check-java8-api.sh`；859 个生产 class 均为 major 52 |
| Maven POM/Gradle metadata/许可证/校验和 | PASS | `verifyMavenStaging`、`verifyCentralChecksums`、`centralDryRunBundle`；只生成本地 unsigned bundle |
| Kotlin consumer 文档用法 | PASS | `checkKotlinConsumerContracts` 的独立编译器编译 command/menu/item/config/data 组合；各模块另有实际 Kotlin consumer 测试 |
| 错误标签类型 | PASS，按预期拒绝 | `WrongTagType.log` 含 `type mismatch` |
| 错误参数类型 | PASS，按预期拒绝 | `WrongArgumentType.log` 含 `type mismatch` |
| 越级 DSL 接收者 | PASS，按预期拒绝 | `OuterDslReceiver.log` 含 `implicit receiver` |
| 事务内挂起 | PASS，按预期拒绝 | `SuspendingTransaction.log` 含 `suspension functions` |
| Config | PASS，95 项 | 真实构造默认值、缺失/显式 null、集合元素、继承字段、注解、失败原子回滚、默认 YAML 与循环引用 |
| Core | PASS，102 项 | 新增 16 个协程生命周期/竞态及 2 个 Kotlin consumer；含 50 轮 close/rebuild 与提交竞争 |
| 会话所有权 | PASS | `withSession` 成功/业务错误/取消均关闭会话；关闭异常作为 suppressed 保留原错误 |
| 数据操作 | PASS | JSON/SQLite/MySQL 模拟兼容测试及 PostgreSQL 实库；Java8/JDK21 均执行 |
| PostgreSQL 17.11 | PASS，3 项 | 本次新建隔离 loopback 数据库；内部/外部 DataSource 的二进制 CRUD、删除后读取、事务/迁移回滚、重开持久性 |
| Item 类加载器隔离 | PASS | 相同二进制类名在两个真实 ClassLoader 中的缓存 Method 所属类不同 |
| 文档相对链接 | PASS | README 与 docs 全目录相对目标存在；Wiki 对应页面在独立 checkout 同步，未推送 |

Java8 测试需要 JDK21 执行 Gradle、JDK8 执行 Test。通过 `-Porg.gradle.java.installations.paths=<jdk8-home>` 指向本机已安装 JDK8；生产编译仍使用 Java/Kotlin 的 Java8 API release 边界。

PostgreSQL 验证使用 `KLIB_POSTGRESQL_TEST_URL`、`KLIB_POSTGRESQL_TEST_USER`、`KLIB_POSTGRESQL_TEST_PASSWORD`，仅指向新建测试库。没有这些变量时，原实库测试会跳过；本次最终两轮均已配置，不继承首次跳过的成绩。

## 本阶段发现并修复

- 反射缓存按类名作键会混淆不同 ClassLoader 中同名类。改用 ClassValue，验证依据为 Product 一致性与 User expectations 隔离预期。
- ignored 字段参与重复键判定，继承 serialVersionUID/transient 同名会误拒绝。实际映射字段与忽略名称分开，保留真正重复字段的拒绝；依据为 History 与 Claims。
- Java8 动态代理更早初始化 Bukkit GameMode，暴露测试 fixture 缺 Guava。补齐 test-only 宿主依赖，未将 Guava 加入生产 POM；依据为 Platform/Product 一致性。
- Kotlin coroutine 获取会话后、恢复前取消可能泄漏资源。通过 continuation 的取消回调释放获取结果，覆盖取消前/后两类竞争。

## 仍不能由此证明

| 未覆盖范围 | 状态与原因 |
| --- | --- |
| 最终重定位 Kotlin/reflect/coroutines 成品 | 由独立 Gradle 插件与实服测试验证；本页的普通 Maven classpath 通过不等于重定位通过 |
| 每个 Minecraft 版本的玩家交互/截图 | 由 MCT/实服矩阵单独记录；无真实客户端证据时必须标 BLOCKED 或 NOT_RUN |
| 任意第三方反射库、任意 Kotlin compiler/runtime 组合 | 未做无限组合验证，不承诺任意隔离兼容 |
| Guard 全功能 Kotlin runtime | 当前公开 Guard 校验对条目数与 multi-release 有额外限制；不得把 Bukkit 成功推导为 Guard 成功 |
| 外部生产 MySQL/PostgreSQL | 未访问生产；本次只使用隔离测试环境 |

测试策略、边界分区与探索章程见 [测试计划](native-kotlin-test-plan.md)。
