# Kotlin 实现层迁移验证

## 基线与范围

迁移基线：`a8b7de6d9b47b8d8847654b677967a1e0c714182`。仅统计 `klib-*/src/main`：Java 500 文件 / 38,050 有效行；Kotlin 11 文件 / 488 有效行。有效行排除空行、独立行注释及块注释；这是审计口径，不是 GitHub Linguist 百分比。测试、生成产物和构建脚本不用于提高语言占比。

Kotlin 运行库和协程为内置能力；Java 与 Kotlin 使用同一实现。Java 入口不暴露必须手写的 `Continuation`。

## 实施阶段

1. 冻结基线全部生产 class、Java/Kotlin 测试消费者 class 与导出 JVM ABI，记录源码 SHA、JDK 和工具链摘要
2. 迁移 core 生命周期/任务状态机，提取两个 scheduler 共享的取消/绑定竞态处理；迁移 language、storage 与 hook 实现
3. 迁移 command/config、UI/item、script/remote 实现；版本适配保持薄层，公共状态机不按版本复制
4. 逐文件登记保留 Java 的真实 SAM/注解/加载/序列化等边界；未迁移实现明确记录为未完成，不能视为必要保留
5. 独立回归：新 Java/Kotlin 消费者编译，旧编译消费者直接链接新实现，自动 JVM ABI 比对，反射/注解/泛型/默认方法/异常契约与序列化验证
6. Java 8/17/21/25 测试、Java 8 API/classfile 边界、真实 PostgreSQL 和跨 Minecraft 客户端运行；更新已验证模块文档、Wiki 和变更日志

NBTAPI 移除作为新增目标单独验证：先核对实际依赖及标签 API，再实现共享类型化标签与最薄版本桥接。参考 TabooLib 官方 ItemTag/NMS 的设计并核对许可，不引入 TabooLib 运行依赖。PDC 不替代任意 NBT 或新版组件语义；替代实现及旧数据回读验证后再移除依赖。

## 兼容门槛

- `scripts/check-jvm-abi.py` 从原始 classfile 提取导出描述符、泛型 Signature、checked Exceptions、父类/接口与访问标志
- 导出类/成员不能移除；静态成员不能变实例成员；可扩展类型不能意外变 final；Java 8 classfile major ≤ 52
- 需要保持 Java SAM、泛型单抽象方法和 Java 默认方法的公开契约可以保持 Java，但实现不以此为由停留在 Java
- `@JvmStatic`、`@JvmOverloads`、`@JvmName` 与必要方差桥接仅服务已存在的自然 Java 契约
- 旧基线通过结果不是新实现通过结果；测试结果必须绑定最终提交及产物摘要

## 测试设计

SFDIPOT 逐维覆盖结构、功能、数据、接口、平台、操作和时间。重点包括空值/空集合/边界长度、数值溢出、非法生命周期迁移、重复关闭/取消、任务附着与完成竞态，以及存储 CRUD/事务回滚。

至少三项探索章程：

1. 生命周期与回调竞态：关闭、重建、取消和提交穿插，检查旧代结果不泄漏与资源逆序清理
2. 双语言/二进制消费：Java lambda、Kotlin 扩展、旧编译子类、反射签名、注解与异常捕获交叉验证
3. 持久化与版本边界：JSON/JDBC CRUD、标签旧数据回读、旧/新服务端共享 API 等价与不支持能力的明确失败

以 HICCUPPS（历史、图像/印象、可比产品、用户期望、产品、目的、标准）审查一致性。每一轮报告保留未覆盖矩阵，区分通过、失败、未运行和受限运行时，不能将 Guard/隔离运行时边界宣称完整支持。

## 模块整合计划（迁移实现稳定后统一移动）

保留现有 Gradle 项目名和 Maven 坐标，使用 `settings.gradle.kts` 的 `projectDir` 将物理结构分为：

- `libraries/{core,command,config,lang,item,ui,hook}`：插件应用层的稳定能力
- `storage/{data,jdbc,json,sqlite,mysql,postgresql}`：统一存储契约和可选驱动
- `platform/{compat,versions/{v1_12,v1_20,v1_21,v26}}`：共享兼容契约/协议与薄版本入口
- `runtime/{script,remote,guard-api}`：脚本执行、远程传输以及隔离加载契约

依赖方向保持应用模块到 core、存储驱动到 JDBC/data、版本入口到 compat；core 不依赖 NMS、版本入口或具体数据库驱动。script/remote 消费稳定核心接口，不让核心反向依赖运行引擎。Guard API 独立保留无 Kotlin 加载依赖的 Java 边界，避免隔离加载器启动之前解析 Kotlin 运行时。

目录归组不代替职责整合：现代侧边栏 1.20.4 起的重复 packet discovery/send/score 实现已合入 compat 内部一个深模块，只将可空参数与 Optional 的实际协议差异留在布局解析；v1_20/v1_21/v26 是薄选择入口。旧版 team/prefix 协议仍独立，避免以万能反射框架混合不同语义。各数据库保留独立坐标，防止 JSON 或 SQLite 用户被迫获取其他 JDBC 驱动；共享事务与资源处理已下沉 JDBC。各应用模块仍可按需打包，不能为减少目录数量强制捆绑不相关依赖。

统一移动时同步 CI、文档、AGENTS、发布检查、源码边界扫描与 QA 脚本。最终验收必须从远端新提交干净 clone 编译、发布并运行双语言/旧二进制消费者，再绑定同一产物运行跨 MC 测试；工作区增量通过不替代这一步。
