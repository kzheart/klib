# Kotlin 实现层迁移验证

## 基线与范围

迁移基线：`a8b7de6d9b47b8d8847654b677967a1e0c714182`。基线按历史平铺目录 `klib-*/src/main` 统计：Java 500 文件 / 38,050 有效行；Kotlin 11 文件 / 488 有效行。

有效行排除空行、独立行注释及块注释；这是审计口径，不是 GitHub Linguist 百分比。测试、生成产物和构建脚本不用于提高语言占比。当前源码位置从 `gradle/module-layout.properties` 读取，不能继续用旧平铺通配符统计。

Kotlin 运行库和协程为内置能力；Java 与 Kotlin 使用同一实现。Java 入口不暴露必须手写的 `Continuation`。

## 实施阶段

1. 冻结基线全部生产 class、Java/Kotlin 测试消费者 class 与导出 JVM ABI，记录源码 SHA、JDK 和工具链摘要
2. 迁移 core 生命周期/任务状态机，提取两个 scheduler 共享的取消/绑定竞态处理；迁移 language、storage 与 hook 实现
3. 迁移 command/config、UI/item、script/remote 实现；版本适配保持薄层，公共状态机不按版本复制
4. 逐文件登记保留 Java 的真实 SAM/注解/加载/序列化等边界；未迁移实现明确记录为未完成，不能视为必要保留
5. 独立回归：新 Java/Kotlin 消费者编译，旧编译消费者直接链接新实现，自动 JVM ABI 比对，反射/注解/泛型/默认方法/异常契约与序列化验证
6. Java 8/17/21/25 测试、Java 8 API/classfile 边界、真实 PostgreSQL 和跨 Minecraft 客户端运行；更新已验证模块文档、Wiki 和变更日志

NBTAPI 已从运行依赖移除，原生标签实现已接入类型化标签回退；正式消费包已按下表验收。原生实现参考 root/custom_data 的分层设计，独立编写，不引入外部 NBT 引擎运行依赖。

公开边界为 `NbtTag` / `NbtCompound` / `NbtList` / `NativeItemTags`；`NbtBinary` 是内部唯一编解码核心。覆盖 12 种 NBT 数据类型，旧槽位是 `LEGACY_ROOT`，组件槽位只代表 `minecraft:custom_data`。

PDC 优先，旧值迁移在 PDC 写回成功后才删除原生旧键。原生写入完整替换当前槽位，调用方须 read/edit/write 保留其余标签；完整物品通过独立 readItem/createItem 入口处理；不执行 DataFixer。

## 基础迁移冻结证据

生产实现冻结于本地 `bf0a557091cd924839eabd5cafcf62f360c738c5`，远端对应提交 `88a18829e544db0d8ca3e8529c4906430b88d01b`，两者源码树相同：`3db88dd074e29bb69458bb179ef003a997ac0952`。以下表格只对应这份基础迁移产物；标签扩展的独立验收另列。

基础迁移冻结代码：Java 200 文件 / 2,895 有效行，Kotlin 344 文件 / 20,240 有效行；Kotlin 占生产有效行 87.49%。保留 Java 用于公开 SPI、平台类型与既有 JVM 契约；逐文件技术审计独立保存。

| 范围 | 最终证据 |
| --- | --- |
| 四 JDK 新测试 | 真实 JDK 8 / 17 / 21 / 25 各 1,053 项，均无失败、错误或跳过，包含真实 PostgreSQL |
| 旧二进制消费者 | 每个 JDK 各执行 943 项不可变旧编译测试，全部通过；与新测试有重叠，不能相加为独立案例 |
| JVM ABI / Java 8 | 原导出类 474、当前 928，0 不兼容；1,119 个生产 classfile 全部满足 Java 8 边界 |
| 源码、反射、序列化 | 9 个 Kotlin、2 个 Java 源码消费者通过；505 条旧反射记录保持；13 个异常序列化 ID/字段布局保持，旧序列化数据在 JDK 8/21 回读通过 |
| 干净构建与发布 | 独立无本地产物 clone 执行 clean check、发布和 Central dry-run，1,053 项通过；21 个运行 JAR 与冻结仓库逐字节一致；公开远端源码树复核一致 |
| 发布依赖 | 21 个 POM 均显式携带 Kotlin stdlib，无外部 NBT 引擎依赖；正式插件包无外部 NBT 实现类 |
| 真实服务端联合消费者 | 九服共 604 项：原生标签 486、不可变旧 Java 消费者 109、新 Kotlin 消费者 9 |
| 正式 POM 消费 PostgreSQL | JDK 8/21 各 43 项通过，实际解析依赖 SHA 与冻结仓库一致 |
| 真实客户端菜单 | 八个 MCT 客户端合计 624 步 / 232 项断言；另原版客户端菜单 23 项通过 |
| 真实客户端侧边栏 | 八个 MCT 客户端合计 176 步 / 104 项断言；另原版客户端 19 项通过；双插件加载器、静态实例和 scope 隔离另有服务端断言 |
| ItemTag 实际背包使用 | 九版各 257 项，共 2,313 项公开 Java/Kotlin API 断言；真实客户端移槽、克隆、序列化、显式写回、删除不复活与恢复全通过 |

正式主探针 SHA-256 为 `d93af7635255dd98937f9a92346ff0837914f1508ff29691b78a9242e01c0773`，侧边栏探针为 `565aea0c484e42082b67c92f289bdf06cc704509c535d48268437bc92b1709e4`。基础 ItemTag 使用探针为 `0ea79c6fda07b413c5dd24a49196f4d4c62ea919c6b0295d4f8d74835ce56465`。历史诊断及修复前包的结果单独保留，未计入上表。一次客户端 GLX 初始化失败按相同参数重试成功，失败原件保留为环境事件。

旧 949 项中仅排除 6 项已删除私有 NBTAPI 实现断言：`LegacyNbtPlatformSupportTest` 的 `classPresenceDoesNotMakeAnUnknownServerRevisionSupported`、`supportedOldAndPdcEraRevisionsRetainLegacyAccessAndMigration`、`missingDirectApplyOrVersionDetectionApiIsUnavailable`、`incompatiblePlatformLinkageIsUnavailable`、`unexpectedDetectionFailureIsNotSilentlyHidden`，以及 `TagKeyLegacyNbtTest.supportedNbtApiExposesBoxedNumericSetters`。前五项直接调用已退役的私有外部后端能力检测，后一项要求外部依赖类存在；公开 ABI、旧真实标签数据、其他同类测试仍必须通过。新原生断言覆盖实际能力、迁移与数据保留，不声称重现旧后端内部故障注入。

真实 MC 矩阵主要覆盖 core、command、config、lang、UI、item、compat；script、remote、hook 已有四 JDK 单测和旧二进制回归，未宣称在真实 MC 执行所有集成路径。未覆盖全部 MC 补丁、全部物品类型、完整 components 编辑、跨版本 DataFixer、真实 MySQL 服务、第三方 hook 全组合与私有 Guard runtime。八个 MCT 目标加一个原版目标的逐版原始记录、测试章程和未覆盖矩阵随验收材料保留。

新增的 ItemTag 标签扩展（类型便利接口、路径、文本 codec、完整物品模式）已完成实现，正在独立实服验收；上表绑定原冻结实现，不能作为这些新增能力的通过证据。属性隐藏标志按独立纯 Bukkit 基线对照：部分服务端在首次 `setItemMeta` 时不保留缺少对应数据的 `HIDE_ATTRIBUTES`，原始失败和 67 状态对照保留；正式背包探针另要求 `HIDE_ENCHANTS` 必须保留，避免空集合假通过。

## 标签扩展验证

包含类型便利接口、深路径、可变同型列表、SNBT / 类型后缀 JSON / 二进制编解码及完整物品模式的实现，已通过真实 JDK 8 / 17 / 21 / 25 各 1,103 项测试，以及各 943 项不可变旧编译测试。各轮均无失败、错误或跳过；新旧测试有重叠，不相加为独立案例。

扩展验收早期一次旧 JDK 17 远程交付测试在 5 秒时序边界失败；原参数三次独立重试、完整矩阵及最终矩阵通过，原始失败保留，未增大超时或排除该测试。完整物品诊断曾发现宿主将零数量规范化，现已增加显式校验；修后九服 718 项诊断通过。最终消费包的扩展实际背包验收另行绑定产物，基础迁移数据不作替代。

## 运行时和保留 Java 的边界

- 构建使用 Kotlin/JVM 2.4.21、Java 8 字节码与 `-Xjdk-release=8`；Core 的协程为公开依赖，Config 依赖同版本 kotlin-reflect。
- Gradle 插件默认启用 Kotlin/JVM，标准库是显式内嵌根，reflect/协程随所选模块的依赖闭包处理；普通 `runtimeClasspath` 不会全部内嵌。
- 部署 JAR 的 Kotlin 类、metadata、module 索引、builtins 与运行时命名空间一起重定位；公开 Maven API 仍保留公共类型名。
- `KotlinReflectRelocator` 仅验证官方 kotlin-reflect 2.4.21 的字节形状；任意 reflect 版本、第三方反射 payload 或跨插件交换私有 Kotlin 类型不在保证范围内。
- Guard API 保留 Java 生命周期入口，状态管理与回滚清理在 Kotlin 实现中；它依赖 Core，不能声称其依赖闭包没有 Kotlin。跨父加载器公开 API 与商品私有 Kotlin 类型有明确拒绝边界；完整 reflect/协程组合仍受 Guard 条目上限和 multi-release 规则限制。
- 保留的 Java 包含公开 SAM/SPI/注解、平台类型和默认方法、开放继承/受保护字段、异常与序列化契约，以及已复现编译器问题的最小边界。实现可在 Kotlin 内共享，不能用保留边界掩盖未迁移的执行引擎。
- 不以删除所有 Java 为目标，也不通过重写测试、生成翻译副本或添加无意义包装来提高 Kotlin 比例。

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

## 模块目录整合

21 个模块已归组，现有 Gradle 项目名和 Maven 坐标保持不变。`gradle/module-layout.properties` 作为目录映射的单一来源，由 `settings.gradle.kts` 的 `projectDir` 应用。职责与依赖方向见[模块布局](../architecture/module-layout.md)：

- `libraries/{core,command,config,lang,item,ui,hook}`：插件应用层的稳定能力
- `storage/{data,jdbc,json,sqlite,mysql,postgresql}`：统一存储契约和可选驱动
- `platform/{compat,versions/{v1_12,v1_20,v1_21,v26}}`：共享兼容契约/协议与薄版本入口
- `runtime/{script,remote,guard-api}`：脚本执行、远程传输以及隔离加载契约

依赖方向保持应用模块到 core、存储驱动到 JDBC/data、版本入口到 compat；core 不依赖 NMS、版本入口或具体数据库驱动。script/remote 消费稳定核心接口，不让核心反向依赖运行引擎。Guard API 保留 Java 声明边界，运行依赖与隔离限制见上节。

目录归组不代替职责整合：现代侧边栏 1.20.4 起的重复 packet discovery/send/score 实现已合入 compat 内部一个深模块，只将可空参数与 Optional 的实际协议差异留在布局解析；v1_20/v1_21/v26 是薄选择入口。旧版 team/prefix 协议仍独立，避免以万能反射框架混合不同语义。各数据库保留独立坐标，防止 JSON 或 SQLite 用户被迫获取其他 JDBC 驱动；共享事务与资源处理已下沉 JDBC。各应用模块仍可按需打包，不能为减少目录数量强制捆绑不相关依赖。

目录调整须同时覆盖 CI、文档、AGENTS、发布检查、源码边界扫描与 QA 脚本。最终验收必须从远端新提交干净 clone 编译、发布并运行双语言/旧二进制消费者，再绑定同一产物运行跨 MC 测试；工作区增量通过不替代这一步。
