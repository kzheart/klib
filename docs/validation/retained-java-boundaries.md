# 保留 Java 边界逐文件审计

## 快照与口径

审计快照：2026-10-17 07:33 UTC；迁移基线 `a8b7de6d9b47b8d8847654b677967a1e0c714182`。生产源码按 [模块映射](../../gradle/module-layout.properties) 的各模块 `src/main` 扫描，排除测试、生成物、构建脚本与外部工程。

当前共 **200 个 Java 文件、4,125 个物理行**，含 **22 个注解、25 个 package-info.java**。物理行包含空行/注释，不能与[迁移报告](kotlin-implementation-migration.md)的有效行口径混用。逐文件路径排序、以 LF 结尾的清单 SHA-256 为 `14bd5ee8fff6382003805e12de104c809e98b5d06395e8bdbf8eb61eadff19d8`。

本报告逐一核读当前 Java 声明/方法体并核对所指向的 Kotlin 文件；参考了迁移审查中的源码消费者、静态隐藏、泛型桥 Exceptions、nullable 返回清单。它是源码边界审计，不把旧产物或其他轮次测试视为当前干净构建已通过；本分工没有运行 Gradle。

| Gradle 模块 | 生产 Java 文件 |
| --- | ---: |
| `klib-core` | 26 |
| `klib-command` | 23 |
| `klib-config` | 12 |
| `klib-lang` | 7 |
| `klib-item` | 10 |
| `klib-ui` | 16 |
| `klib-hook` | 13 |
| `klib-data` | 9 |
| `klib-data-jdbc` | 5 |
| `klib-data-json` | 1 |
| `klib-data-sqlite` | 1 |
| `klib-data-mysql` | 1 |
| `klib-data-postgresql` | 0 |
| `klib-compat` | 4 |
| `klib-compat-v1_12` | 1 |
| `klib-compat-v1_20` | 1 |
| `klib-compat-v1_21` | 1 |
| `klib-compat-v26` | 1 |
| `klib-script` | 52 |
| `klib-remote` | 7 |
| `klib-guard-api` | 9 |
| 合计 | **200** |

## 为什么保留这些声明

1. 公开 SPI 的 Java 平台类型允许 Kotlin 实现者选择 nullable 或 non-null override。统一改成 `T` 或 `T?` 均会损失一侧源码兼容；泛型参数、容器元素也属于该边界。SAM、FunctionalInterface、default 方法和 checked Exceptions 是另需核对的契约，并非单独出现其中一个就证明必须 Java。
2. 真实 nullable 返回的 Java 门面保留 `T!`。即使其父接口仍是 Java，具体 Kotlin override 也会固定可空性，因此只保留父接口并不总够。不能用假 nonnull 返回、unchecked cast 或仅 JVM ABI 通过掩盖这一差异。
3. 公开可继承类型、protected 真字段、包可见构造/类型、非 final static 隐藏以及 javac 泛型桥的 Exceptions 属性，分别按源码事实登记；不把所有继承或 static 方法都当障碍。
4. 普通 final 类的 primitive/非空 getter 双入口已能用 Kotlin `@JvmSynthetic`、`@JvmName` 属性桥保留。`ExitStatus`、`KetherLexException` 已迁 Kotlin；四个纯 boolean capability SPI 和无参 SAM `Disposable` 也已迁出，不在本清单。`TaskHandle` 的可实现接口方法/属性契约须与 final 类方案区分。
5. 注解、包文档和父加载器纯常量是零执行逻辑的声明边界。保留注解的目标/默认值原形与常量的共享加载形状是设计选择，不声称 Kotlin 语言无法表达，也不声称本清单证明全局最少 Java 文件数。

下表每个生产 Java 文件只出现一次。第三列指实际 Kotlin 实现或调用方；纯 SPI 由使用者实现时明确标注，不把调用方误称成外部服务实现。

## 深模块与剩余复杂性

- `ScopeImpl/ScopeGeneration/ScopedFuture` 隐藏资源逆序释放、构建代与 future 终结；`ScopedTaskHandle` 收拢 scheduler 取消/附着竞态，Java 生命周期入口没有第二套状态机
- `Arguments/CommandSpecImpl/AnnotatedCommands` 集中解析、节点约束与反射计划；`ConfigTypePlan/ConfigNodeValue/ReloadListeners` 集中映射、树差异与重载发布，不让每个调用者自己管理这些规则
- `AbstractJdbcStorageProvider/JdbcExecutor` 共享数据库启动/事务/资源边界；版本适配共用 `ModernSidebarBridge` 的包布局发现与发送，数据库驱动和旧版独特协议仍独立，避免无关依赖捆绑
- `QuestLifecycle/FrameExecution/TokenScanner/ActionReader/BlockParser/ParserCombinators` 各自隐藏一个完整执行、扫描或组合问题；保留 Java 扩展字段/钩子并不等于保留一套旧 Java 引擎
- `TabooLibInteropState/GuardKetherInterop` 集中代次、句柄、撤销和跨加载器值约束；`RemoteDelivery/RemoteDiskQueue/RemoteClientState` 集中重试、磁盘边界和 wire 校验。Java nullable 门面只承担必须保留的类型入口
- 没有声称所有 Java 方法体已经消失：原 default 方法、小型值对象的快照/属性表、游标检查、错误格式化仍有少量 Java 行为。下表如实列出 `LocalizedException`、`ParsedAction`、`SimpleQuest` 等；没有为每几行逻辑制造另一层机械转发

### Guard 的准确范围

`klib-guard-api` 的九个公开 Java 源文件仍受[源边界检查](../../runtime/guard-api/build.gradle.kts)限制；同时仅允许一个 Kotlin 生命周期实现 `KlibCloudPluginLifecycle.kt`。该控制器现在承担 load 回滚、Scope/能力装配、disable 清理与异常顺序，Java 基类只保留公开扩展/同步边界。

此模块本身已含 Kotlin 实现，并且 API 依赖 core，绝不是“依赖闭包没有 Kotlin”。父加载器共享契约不向签名泄漏商品私有 Kotlin parser/action/frame；这不证明任意 Kotlin 运行时组合均可跨加载器部署。Guard runtime、Native、Collector 与门户 broker 的实现不在此公共仓库，也不在本报告验证范围。

## 逐文件清单

### klib-core（26）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [KPlugin.java](../../libraries/core/src/main/java/me/kzheart/klib/KPlugin.java) | 公开可继承插件基类的 `instance()` 是非 final static，Java 子类可隐藏；`setup(Scope)` 允许旧 Kotlin 子类选择参数空性，不能由 final 的 `@JvmStatic` 取代。Java 只保留监视器、扩展入口和懒建委派 | [KPluginLifecycle.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/KPluginLifecycle.kt) |
| [command/api/CommandArgument.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandArgument.java) | 公开只读类型键的 `name(): String!` 保留实现者返回空性的源契约；受支持的参数族仍由包内构造的 Arg 限定 | [Arguments.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/Arguments.kt)、[CommandContextImpl.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandContextImpl.kt) |
| [command/api/CommandCapability.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandCapability.java) | 宿主注册 SPI 的 Scope、Consumer 和返回句柄保留平台类型；`registerAnnotated` 保持原 Java default 的不支持行为 | [CommandCapabilityImpl.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandCapabilityImpl.kt) |
| [command/api/CommandContext.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandContext.java) | 异型 `get(CommandArgument<T>): T!` 和泛型 `Class<T>` 转换保留平台泛型；`find/get(name,type)` 的 default 入口保持旧实现可链接 | [CommandContextImpl.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandContextImpl.kt) |
| [command/api/CommandErrorHandler.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandErrorHandler.java) | `handle(CommandContext!, Throwable!)` 是公开 Java SAM，旧 Kotlin nullable/non-null override 均可成立；保留 FunctionalInterface 反射注解 | [CommandDispatcher.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandDispatcher.kt) |
| [command/api/CommandHandler.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandHandler.java) | `execute(CommandContext!)` 的公开 SAM 平台参数不能同时由单一 Kotlin `CommandContext` 或 `CommandContext?` 声明兼容 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt)、[CommandDispatcher.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandDispatcher.kt) |
| [command/api/CommandHelpStyle.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandHelpStyle.java) | `header/previous/next` 确实可返回 null，需保留 String! 返回而不能伪造非空；同文件保留 Preset、Builder 二进制名和 Java 流式入口 | [CommandHelpStyleValue.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/command/api/CommandHelpStyleValue.kt) |
| [command/api/CommandRegistration.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandRegistration.java) | 公开生命周期扩展接口的 `name(): String!` 保留 Kotlin 实现者对返回空性的选择 | [CommandRegistrationImpl.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandRegistrationImpl.kt) |
| [command/api/CommandSpec.java](../../libraries/core/src/main/java/me/kzheart/klib/command/api/CommandSpec.java) | 可由宿主实现的建树 API 包含 Consumer、Supplier 与泛型参数的平台类型，保留原 route/argument default 入口与 fluent 返回契约 | [CommandSpecImpl.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandSpecImpl.kt) |
| [component/OnStart.java](../../libraries/core/src/main/java/me/kzheart/klib/component/OnStart.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；无成员。这是零执行逻辑的注解元数据边界 | [Components.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/component/Components.kt) |
| [component/OnStop.java](../../libraries/core/src/main/java/me/kzheart/klib/component/OnStop.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；无成员。这是零执行逻辑的注解元数据边界 | [Components.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/component/Components.kt) |
| [config/annotation/ConfigFile.java](../../libraries/core/src/main/java/me/kzheart/klib/config/annotation/ConfigFile.java) | Java RUNTIME 注解保留 `TYPE` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [Configs.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/config/api/Configs.kt) |
| [config/api/ConfigDocument.java](../../libraries/core/src/main/java/me/kzheart/klib/config/api/ConfigDocument.java) | `value(): T!`、变更监听和 CompletionStage 平台泛型是公开实现契约；默认 reloadAsync 保留先 reload 再完成的 Java default | [YamlConfigDocument.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigDocument.kt) |
| [cooldown/package-info.java](../../libraries/core/src/main/java/me/kzheart/klib/cooldown/package-info.java) | `me.kzheart.klib.cooldown` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [core/package-info.java](../../libraries/core/src/main/java/me/kzheart/klib/core/package-info.java) | `me.kzheart.klib.core` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [diagnostic/DiagnosticSource.java](../../libraries/core/src/main/java/me/kzheart/klib/diagnostic/DiagnosticSource.java) | 公开诊断 SPI 的 `Map<String, ?>!` 与名称返回保留集合及值的平台空性；预算、快照和隔离不在接口中实现 | [KlibDiagnosticContributor.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/KlibDiagnosticContributor.kt)、[YamlConfigDocument.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigDocument.kt) |
| [random/package-info.java](../../libraries/core/src/main/java/me/kzheart/klib/random/package-info.java) | `me.kzheart.klib.random` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [reflect/Declarations.java](../../libraries/core/src/main/java/me/kzheart/klib/reflect/Declarations.java) | 反射 invoke 对 void 方法或显式 null 结果真实返回 null；Java `Object!` 保留旧 Kotlin 直接接收/调用，反射遍历与异常解包已下沉 | [DeclarationsSupport.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/reflect/DeclarationsSupport.kt) |
| [scheduler/AsyncTask.java](../../libraries/core/src/main/java/me/kzheart/klib/scheduler/AsyncTask.java) | 公开异步句柄的 Consumer<? super T> 与返回 AsyncTask<T> 保留平台泛型/空性；失败、回调调度不在接口中 | [AsyncTaskImpl.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scheduler/AsyncTaskImpl.kt) |
| [scheduler/Every.java](../../libraries/core/src/main/java/me/kzheart/klib/scheduler/Every.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；成员/default：`long ticks(); TaskThread thread() default TaskThread.SYNC;`。这是零执行逻辑的注解元数据边界 | [Tasks.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scheduler/Tasks.kt) |
| [scheduler/KScheduler.java](../../libraries/core/src/main/java/me/kzheart/klib/scheduler/KScheduler.java) | 公开调度器 SPI 的 Runnable、Supplier<T>、句柄和 Executor 均为平台类型；sync/syncExecutor 保留真正 default 入口及原调度约定 | [BukkitSchedulerAdapter.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scheduler/BukkitSchedulerAdapter.kt)、[ExecutorScheduler.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scheduler/ExecutorScheduler.kt) |
| [scheduler/SchedulerFactory.java](../../libraries/core/src/main/java/me/kzheart/klib/scheduler/SchedulerFactory.java) | `forScope(Scope!): KScheduler!` 为公开 Java SAM，保留 nullable/non-null 实现者及 FunctionalInterface | [BukkitSchedulerFactory.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scheduler/BukkitSchedulerFactory.kt) |
| [scheduler/TaskHandle.java](../../libraries/core/src/main/java/me/kzheart/klib/scheduler/TaskHandle.java) | 公开接口的 isCancelled/isDone 同时支持方法和 Kotlin synthetic property，并被第三方实现；普通 final 类的隐藏属性桥不能直接等同于接口上的可覆盖 getter。保留原接口声明及 default dispose，未把 primitive 本身当成必须 Java 的理由 | [ScopedTaskHandle.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scheduler/ScopedTaskHandle.kt)、[AsyncTaskImpl.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scheduler/AsyncTaskImpl.kt) |
| [scope/Scope.java](../../libraries/core/src/main/java/me/kzheart/klib/scope/Scope.java) | 公开 Scope SPI 的 capability、安装资源、Consumer/Function 和返回值含平台泛型；保留 default 能力分派。资源状态机和异步代次终结在 Kotlin，Java 默认体仍有少量参数检查/能力转发 | [ScopeImpl.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scope/ScopeImpl.kt)、[ScopeGeneration.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scope/ScopeGeneration.kt)、[ScopedFuture.kt](../../libraries/core/src/main/kotlin/me/kzheart/klib/scope/ScopedFuture.kt) |
| [scope/capability/ConfigCapability.java](../../libraries/core/src/main/java/me/kzheart/klib/scope/capability/ConfigCapability.java) | 泛型 `load(Scope!, Class<T>!, String!): ConfigDocument<T>!` 是可替换能力 SPI，保留 Kotlin 实现者空性与泛型签名 | [YamlConfigCapability.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigCapability.kt) |
| [scope/package-info.java](../../libraries/core/src/main/java/me/kzheart/klib/scope/package-info.java) | `me.kzheart.klib.scope` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-command（23）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [command/Arg.java](../../libraries/command/src/main/java/me/kzheart/klib/command/Arg.java) | 公开类型的构造器、parse/suggest 与状态辅助方法原为包可见，Kotlin internal 会改 JVM 可见性/名字；另保留 T! 和 checked ArgumentException。仅身份键/简单 usage 拼接留此，实际解析在 Kotlin | [Arguments.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/Arguments.kt)、[CommandSpecImpl.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandSpecImpl.kt)、[AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/ArgumentParser.java](../../libraries/command/src/main/java/me/kzheart/klib/command/ArgumentParser.java) | `parse(String!): T!` 公开泛型 SAM；null 真实表示解析失败，旧 nullable/non-null override 均需成立 | [Arguments.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/Arguments.kt) |
| [command/BukkitPlayerResolver.java](../../libraries/command/src/main/java/me/kzheart/klib/command/BukkitPlayerResolver.java) | 即使父接口是 Java，Kotlin concrete override 也会固定 Player? 返回；findExact 未命中确实为 null，因此具体类也保留平台返回门面 | [BukkitPlayerResolverSupport.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/BukkitPlayerResolverSupport.kt) |
| [command/CommandBridge.java](../../libraries/command/src/main/java/me/kzheart/klib/command/CommandBridge.java) | `register(String!, CommandSpec!, CommandDispatcher!): Disposable!` 是宿主公开注册 SPI，保留平台参数和返回，生命周期适配在 Kotlin | [BukkitCommandRegistrar.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/BukkitCommandRegistrar.kt)、[PaperLifecycleCommandBridge.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/PaperLifecycleCommandBridge.kt) |
| [command/CommandMessages.java](../../libraries/command/src/main/java/me/kzheart/klib/command/CommandMessages.java) | 消息解析 SAM 的 sender、Map<String, ?> 与 RichText 输出保留平台空性；默认消息与管线接入为 Kotlin | [DefaultCommandMessages.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/DefaultCommandMessages.kt)、[MessagePipelineCommandMessages.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/MessagePipelineCommandMessages.kt) |
| [command/CommandResult.java](../../libraries/command/src/main/java/me/kzheart/klib/command/CommandResult.java) | 成功结果的 `message()` 确实为 null；保留 RichText! 及 Status 的原嵌套名字。这里只是状态/可选消息值对象，无独立执行算法 | [CommandDispatcher.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandDispatcher.kt) |
| [command/ContextualArgumentParser.java](../../libraries/command/src/main/java/me/kzheart/klib/command/ContextualArgumentParser.java) | 带上下文 `parse(String!, CommandContext!): T!` 公开泛型 SAM，不能收紧第三方实现者空性 | [Arguments.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/Arguments.kt) |
| [command/ContextualSuggestionProvider.java](../../libraries/command/src/main/java/me/kzheart/klib/command/ContextualSuggestionProvider.java) | `suggest(SuggestionContext!): List<String!>!` 保留回调参数及列表/元素平台类型，过滤和匹配逻辑在 Kotlin | [Arguments.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/Arguments.kt) |
| [command/DispatcherAwareCommandHandler.java](../../libraries/command/src/main/java/me/kzheart/klib/command/DispatcherAwareCommandHandler.java) | 顶级接口原为包可见，保留不对外暴露的内部类型；继承 CommandHandler 并提供原 default execute，双参数分派由 Kotlin 调用 | [CommandBuiltins.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandBuiltins.kt)、[CommandDispatcher.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/CommandDispatcher.kt) |
| [command/PlayerResolver.java](../../libraries/command/src/main/java/me/kzheart/klib/command/PlayerResolver.java) | findExact 可返回 null，suggest 列表及参数为公开实现者的平台类型；保留两方法 SPI | [BukkitPlayerResolverSupport.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/BukkitPlayerResolverSupport.kt) |
| [command/RichTextSink.java](../../libraries/command/src/main/java/me/kzheart/klib/command/RichTextSink.java) | `send(CommandSender!, RichText!)` 的公开 SAM 保留 nullable/non-null override 及 FunctionalInterface；输出桥在 Kotlin | [PlainRichTextSink.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/PlainRichTextSink.kt)、[SpigotRichTextSink.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/SpigotRichTextSink.kt) |
| [command/SuggestionProvider.java](../../libraries/command/src/main/java/me/kzheart/klib/command/SuggestionProvider.java) | 公开 SAM 的 sender/prefix 及 List<String!>! 保留平台空性，避免改变既有补全器的声明 | [Arguments.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/Arguments.kt) |
| [command/annotation/Check.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Check.java) | Java RUNTIME 注解保留 `{TYPE, METHOD}` 的原声明目标及 Java 反射入口；成员/default：`String[] value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/CheckHandler.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/CheckHandler.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Command.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Command.java) | Java RUNTIME 注解保留 `TYPE` 的原声明目标及 Java 反射入口；成员/default：`String value(); String[] aliases() default {}; boolean help() default false;`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Description.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Description.java) | Java RUNTIME 注解保留 `{TYPE, METHOD}` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Greedy.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Greedy.java) | Java RUNTIME 注解保留 `PARAMETER` 的原声明目标及 Java 反射入口；无成员。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Param.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Param.java) | Java RUNTIME 注解保留 `PARAMETER` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Permission.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Permission.java) | Java RUNTIME 注解保留 `{TYPE, METHOD}` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Route.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Route.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；成员/default：`String[] value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Suggest.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Suggest.java) | Java RUNTIME 注解保留 `PARAMETER` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/annotation/Suggestions.java](../../libraries/command/src/main/java/me/kzheart/klib/command/annotation/Suggestions.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedCommands.kt](../../libraries/command/src/main/kotlin/me/kzheart/klib/command/AnnotatedCommands.kt) |
| [command/package-info.java](../../libraries/command/src/main/java/me/kzheart/klib/command/package-info.java) | `me.kzheart.klib.command` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-config（12）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [config/ConfigConverter.java](../../libraries/config/src/main/java/me/kzheart/klib/config/ConfigConverter.java) | `convert(ConfigNode!): T!` 泛型 SAM 保留可空 converter 结果及双向 override 空性；映射/校验统一在 Kotlin | [YamlConfigMapper.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigMapper.kt) |
| [config/ConfigDocumentProvider.java](../../libraries/config/src/main/java/me/kzheart/klib/config/ConfigDocumentProvider.java) | `open(Scope!, String!): ConfigSource!` 公开 SAM 保留平台类型，null source 仍由能力实现明确报错 | [YamlConfigCapability.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigCapability.kt) |
| [config/ConfigNode.java](../../libraries/config/src/main/java/me/kzheart/klib/config/ConfigNode.java) | `raw(): Object!` 确有缺失/null 节点；另保留包可见 YAML node 与内部导航入口。树状态、路径/映射/序列规则在 Kotlin；view 只保持同节点外壳身份 | [ConfigNodeValue.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/ConfigNodeValue.kt) |
| [config/ConfigSource.java](../../libraries/config/src/main/java/me/kzheart/klib/config/ConfigSource.java) | source/prepare/watch 是公开平台类型 SPI；load 保留先 prepare、commit、document 的 Java default 调用顺序 | [FileConfigSource.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/FileConfigSource.kt)、[InMemoryConfigSource.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/InMemoryConfigSource.kt) |
| [config/Migration.java](../../libraries/config/src/main/java/me/kzheart/klib/config/Migration.java) | `apply(YamlDocument!)` 为公开 SAM，保留参数平台空性和 FunctionalInterface；迁移排序/执行在 Kotlin | [MigrationRunner.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/MigrationRunner.kt)、[Migrations.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/Migrations.kt) |
| [config/PreparedConfig.java](../../libraries/config/src/main/java/me/kzheart/klib/config/PreparedConfig.java) | `document(): YamlDocument!` 与 revision 平台返回允许实现者原声明；commit 契约和 default revision 不改变 | [FileConfigSource.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/FileConfigSource.kt)、[InMemoryConfigSource.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/InMemoryConfigSource.kt) |
| [config/ReloadFailureSource.java](../../libraries/config/src/main/java/me/kzheart/klib/config/ReloadFailureSource.java) | 公开失败观测 SPI 的 Consumer<? super Throwable>、Disposable 与 Optional<Throwable> 平台类型保持；监听注册/派发共享 Kotlin 状态 | [ReloadListeners.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/ReloadListeners.kt)、[YamlConfigDocument.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigDocument.kt)、[Registry.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/Registry.kt) |
| [config/annotation/Comment.java](../../libraries/config/src/main/java/me/kzheart/klib/config/annotation/Comment.java) | Java RUNTIME 注解保留 `FIELD` 的原声明目标及 Java 反射入口；成员/default：`String[] value();`。这是零执行逻辑的注解元数据边界 | [ConfigTypePlan.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/ConfigTypePlan.kt) |
| [config/annotation/Key.java](../../libraries/config/src/main/java/me/kzheart/klib/config/annotation/Key.java) | Java RUNTIME 注解保留 `FIELD` 的原声明目标及 Java 反射入口；成员/default：`String value();`。这是零执行逻辑的注解元数据边界 | [ConfigTypePlan.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/ConfigTypePlan.kt)、[YamlConfigMapper.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigMapper.kt) |
| [config/annotation/Range.java](../../libraries/config/src/main/java/me/kzheart/klib/config/annotation/Range.java) | Java RUNTIME 注解保留 `FIELD` 的原声明目标及 Java 反射入口；成员/default：`double min() default -Double.MAX_VALUE; double max() default Double.MAX_VALUE;`。这是零执行逻辑的注解元数据边界 | [ConfigTypePlan.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/ConfigTypePlan.kt)、[YamlConfigMapper.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigMapper.kt) |
| [config/annotation/Validate.java](../../libraries/config/src/main/java/me/kzheart/klib/config/annotation/Validate.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；无成员。这是零执行逻辑的注解元数据边界 | [YamlConfigMapper.kt](../../libraries/config/src/main/kotlin/me/kzheart/klib/config/YamlConfigMapper.kt) |
| [config/package-info.java](../../libraries/config/src/main/java/me/kzheart/klib/config/package-info.java) | `me.kzheart.klib.config` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-lang（7）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [lang/BukkitComponentSender.java](../../libraries/lang/src/main/java/me/kzheart/klib/lang/BukkitComponentSender.java) | 原顶级类型是包可见，Kotlin internal 将成为 JVM public；保留宿主发送适配的包边界，反射发现/组件构造在 Kotlin | [ReflectionBukkitComponentSender.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/ReflectionBukkitComponentSender.kt) |
| [lang/MessageCatalog.java](../../libraries/lang/src/main/java/me/kzheart/klib/lang/MessageCatalog.java) | find/findLines/findRich/findAny 是可替换目录 SPI，保留 Optional 与嵌套 List 平台类型和 Java defaults；默认三种查找优先序仍是小型兼容体 | [MapMessageCatalog.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/MapMessageCatalog.kt)、[FallbackMessageCatalog.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/FallbackMessageCatalog.kt)、[ReloadableMessageCatalog.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/ReloadableMessageCatalog.kt) |
| [lang/MessageRouteSink.java](../../libraries/lang/src/main/java/me/kzheart/klib/lang/MessageRouteSink.java) | `send(MessageRoute!, RichText!)` 是公开宿主输出 SPI，旧 nullable/non-null 参数实现仍可编译 | [DefaultMessageRouter.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/DefaultMessageRouter.kt) |
| [lang/MessageRouter.java](../../libraries/lang/src/main/java/me/kzheart/klib/lang/MessageRouter.java) | 公开路由 SPI 的 recipient/route/message 平台参数与双重载 default 保留；实际路由策略在 Kotlin | [DefaultMessageRouter.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/DefaultMessageRouter.kt)、[BukkitMessageRouter.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/BukkitMessageRouter.kt) |
| [lang/PlaceholderApi.java](../../libraries/lang/src/main/java/me/kzheart/klib/lang/PlaceholderApi.java) | `expand(MessageRecipient!, String!): String!` 公开宿主回调保留平台空性；展开阶段的组合在 Kotlin | [MessagePipeline.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/MessagePipeline.kt) |
| [lang/RichTextSegment.java](../../libraries/lang/src/main/java/me/kzheart/klib/lang/RichTextSegment.java) | color/hover/click 确实可为 null，平台返回保留旧重复读取后使用的 Kotlin 源码；不可变值状态和复制由 Kotlin 承担 | [RichTextSegment.kt](../../libraries/lang/src/main/kotlin/me/kzheart/klib/lang/RichTextSegment.kt) |
| [lang/package-info.java](../../libraries/lang/src/main/java/me/kzheart/klib/lang/package-info.java) | `me.kzheart.klib.lang` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-item（10）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [item/AdaptiveItemTagBridge.java](../../libraries/item/src/main/java/me/kzheart/klib/item/AdaptiveItemTagBridge.java) | 具体类 get 对缺失标签返回 T!；仅保留 Java generic 平台门面和包可见测试构造，PDC/旧 NBT 选择与迁移集中于 Kotlin | [AdaptiveItemTagBridgeState.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/AdaptiveItemTagBridgeState.kt) |
| [item/ExternalItemProvider.java](../../libraries/item/src/main/java/me/kzheart/klib/item/ExternalItemProvider.java) | matches/create 为公开外部物品 SPI；创建结果可能 null，保留 ItemStack! 与输入平台类型 | [ExternalItems.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/ExternalItems.kt) |
| [item/ExternalItemSource.java](../../libraries/item/src/main/java/me/kzheart/klib/item/ExternalItemSource.java) | create/identify 真实允许 null，Player 上下文及 defaults 为公开扩展面；Java default 保留安全预览不回退、生成数量/克隆的原语义 | [ExternalItemSources.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/ExternalItemSources.kt) |
| [item/ItemBuilder.java](../../libraries/item/src/main/java/me/kzheart/klib/item/ItemBuilder.java) | 读取重载 `tag(TagKey<T>!): T!` 缺失时确为 null；保留包可见构造与 fluent 形状，物品编辑/复制全部在 Kotlin | [ItemBuilderState.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/ItemBuilderState.kt) |
| [item/ItemTagBridge.java](../../libraries/item/src/main/java/me/kzheart/klib/item/ItemTagBridge.java) | 公开泛型 SPI 的 ItemStack、TagKey、T 均为平台类型；旧 Kotlin 可空输入/可空泛型 override 必须仍成立 | [AdaptiveItemTagBridgeState.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/AdaptiveItemTagBridgeState.kt)、[PdcItemTagBridgeState.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/PdcItemTagBridgeState.kt)、[LegacyNbtItemTagBridgeState.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/LegacyNbtItemTagBridgeState.kt) |
| [item/Items.java](../../libraries/item/src/main/java/me/kzheart/klib/item/Items.java) | customModelData 未设置或宿主不支持时为 null，保持 Integer! 返回；静态入口只委派 Kotlin 物品/反射能力 | [ItemsSupport.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/ItemsSupport.kt) |
| [item/LegacyNbtItemTagBridge.java](../../libraries/item/src/main/java/me/kzheart/klib/item/LegacyNbtItemTagBridge.java) | 具体 get 的缺失值为 T!，Kotlin override 会暴露 T? 源码类型；Java 门面保留入口，NBT 存储/转换在 Kotlin | [LegacyNbtItemTagBridgeState.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/LegacyNbtItemTagBridgeState.kt) |
| [item/PdcItemTagBridge.java](../../libraries/item/src/main/java/me/kzheart/klib/item/PdcItemTagBridge.java) | 具体 get 的缺失值为 T!；保留 supports 与 static isServerSupported 形状，PDC 反射及类型映射在 Kotlin | [PdcItemTagBridgeState.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/PdcItemTagBridgeState.kt) |
| [item/TagKey.java](../../libraries/item/src/main/java/me/kzheart/klib/item/TagKey.java) | get/getOrDefault 的泛型平台返回须同时容纳真实 null 与旧非空接收；还保留静态工厂返回 TagKey<String/Integer/...> 的嵌套泛型平台空性。键校验、编码、默认值、存储选择在 Kotlin | [TagKey.kt](../../libraries/item/src/main/kotlin/me/kzheart/klib/item/TagKey.kt) |
| [item/package-info.java](../../libraries/item/src/main/java/me/kzheart/klib/item/package-info.java) | `me.kzheart.klib.item` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-ui（16）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [ui/ItemReturnTarget.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/ItemReturnTarget.java) | 公开物品归还 SAM 的 List<ItemStack!>! 输入/输出保留平台泛型和实现者空性；关闭/归还协调在 Kotlin | [MenuSession.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuSession.kt) |
| [ui/MenuAction.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/MenuAction.java) | `accept(MenuClick!)` 公开 SAM 保留平台参数、FunctionalInterface 与 static none；Java 只提供无操作值 | [MenuEntry.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuEntry.kt)、[MenuDsl.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuDsl.kt) |
| [ui/MenuErrorHandler.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/MenuErrorHandler.java) | 公开异常边界 SAM 的 Player、MenuModel、MenuClick、Throwable 均为平台类型，旧 nullable/non-null override 均需成立 | [MenuRenderer.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuRenderer.kt) |
| [ui/MenuHolder.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/MenuHolder.java) | getInventory 在 attach 前真实为 null；Java 具体类返回 Inventory!，并保留 renderer/viewer/renderedItems 的包可见字段。会话与刷新逻辑在 Kotlin | [MenuHolderState.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuHolderState.kt) |
| [ui/MenuInventoryFactory.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/MenuInventoryFactory.java) | create 的 InventoryHolder!/String!/Inventory! 是公开宿主 SAM；bukkit 静态工厂仍返回相同契约 | [MenuRenderer.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuRenderer.kt) |
| [ui/OverflowSink.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/OverflowSink.java) | 公开最终归还 SAM 的列表/ItemStack 平台类型保持，残留物品与兜底调用在 Kotlin | [MenuSession.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuSession.kt) |
| [ui/YamlItemResolver.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/YamlItemResolver.java) | `resolve(String!): MenuEntry!` 公开 SAM 保留平台类型；模板解析/结果检查在 Kotlin | [MenuCompiler.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/MenuCompiler.kt) |
| [ui/annotation/Button.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/annotation/Button.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；成员/default：`char value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedMenu.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/AnnotatedMenu.kt) |
| [ui/annotation/Click.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/annotation/Click.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；成员/default：`char value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedMenu.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/AnnotatedMenu.kt) |
| [ui/annotation/Entries.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/annotation/Entries.java) | Java RUNTIME 注解保留 `METHOD` 的原声明目标及 Java 反射入口；成员/default：`char value();`。这是零执行逻辑的注解元数据边界 | [AnnotatedMenu.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/AnnotatedMenu.kt) |
| [ui/annotation/Menu.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/annotation/Menu.java) | Java RUNTIME 注解保留 `TYPE` 的原声明目标及 Java 反射入口；成员/default：`String title(); String[] layout();`。这是零执行逻辑的注解元数据边界 | [AnnotatedMenu.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/AnnotatedMenu.kt) |
| [ui/chat/ChatPanelButton.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/chat/ChatPanelButton.java) | COMMAND/SUGGEST/COPY 按钮可无 id，hover/value 也可为 null；保留三处平台返回及包可见 Kind/路由入口，值与校验在 Kotlin | [ChatPanelButtonValue.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/chat/ChatPanelButtonValue.kt) |
| [ui/chat/ChatPanelSignInput.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/chat/ChatPanelSignInput.java) | 公开告示牌宿主 SPI 的 Player、String[]、Consumer<String[]> 平台类型保持，实际宿主反射/提交取消在 Kotlin | [PaperSignInput.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/chat/PaperSignInput.kt) |
| [ui/drop/InventoryAction.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/drop/InventoryAction.java) | TAKE 动作没有 item，需保留 ItemStack!；类型与包内值形状不变，输入校验/克隆/drag 快照在 Kotlin | [InventoryActionValue.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/drop/InventoryActionValue.kt) |
| [ui/package-info.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/package-info.java) | `me.kzheart.klib.ui` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [ui/prompt/PromptParser.java](../../libraries/ui/src/main/java/me/kzheart/klib/ui/prompt/PromptParser.java) | `parse(String!): Optional<T!>!` 公开泛型 SAM，明确保留旧 `PromptParser<String?>` 源码及 FunctionalInterface | [PromptSession.kt](../../libraries/ui/src/main/kotlin/me/kzheart/klib/ui/prompt/PromptSession.kt) |

### klib-hook（13）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [hook/Hook.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/Hook.java) | 泛型 `value(): T!` 与依赖名称/状态/详情为公开 Hook 实现契约，保留平台空性与 Disposable 继承 | [ResolvedHook.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/ResolvedHook.kt)、[Hooks.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/Hooks.kt) |
| [hook/cost/Cost.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/cost/Cost.java) | describe/test/take 的 Player、String、Charge 平台类型允许外部成本提供者原 nullable/non-null 声明；扣除/退款策略在 Kotlin | [Costs.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/Costs.kt)、[CostTypes.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/CostTypes.kt)、[CostPlan.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/CostPlan.kt) |
| [hook/cost/CostLine.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/cost/CostLine.java) | 正常检查的 error 真实为 null；保留 String! 返回与包可见构造。仅不可变值和展示字符串，无扣费状态机 | [CostPlan.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/CostPlan.kt) |
| [hook/cost/CostResult.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/cost/CostResult.java) | 成功/未满足时 failedCost 可为 null，保留平台返回；失败归因、消息与不可变结果列表在 Kotlin 值实现 | [CostResultValue.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/CostResultValue.kt) |
| [hook/cost/CostType.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/cost/CostType.java) | `parse(String!): Cost!` 公开 SAM 保留参数/返回平台空性及 FunctionalInterface | [CostTypes.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/CostTypes.kt) |
| [hook/cost/Reward.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/cost/Reward.java) | describe/grant 公开 SPI 保留 String!/Player! 的实现者空性；奖励执行策略在 Kotlin | [Rewards.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/Rewards.kt)、[RewardTypes.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/RewardTypes.kt)、[RewardPlan.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/RewardPlan.kt) |
| [hook/cost/RewardType.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/cost/RewardType.java) | `parse(String!): Reward!` 公开 SAM 保留平台参数/返回及 FunctionalInterface | [RewardTypes.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/cost/RewardTypes.kt) |
| [hook/cost/package-info.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/cost/package-info.java) | `me.kzheart.klib.hook.cost` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [hook/economy/Currency.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/economy/Currency.java) | 公开经济 SPI 的 Player、BigDecimal 与结果为平台类型；minorUnit 默认 null 表示任意精度，保留 default has/minorUnit | [CompositeCurrency.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/economy/CompositeCurrency.kt)、[VaultCurrency.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/economy/VaultCurrency.kt)、[PlayerPointsCurrency.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/economy/PlayerPointsCurrency.kt)、[XConomyCurrency.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/economy/XConomyCurrency.kt) |
| [hook/economy/CurrencyResult.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/economy/CurrencyResult.java) | cause 确实可为 null，需保留 Throwable!；保留 Uncompensated 嵌套二进制名和包可见构造。Java 仅值校验/列表快照，余额与补偿计划在 Kotlin | [CompositeCurrency.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/economy/CompositeCurrency.kt) |
| [hook/package-info.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/package-info.java) | `me.kzheart.klib.hook` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [hook/papi/PapiExpansion.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/papi/PapiExpansion.java) | resolve 未命中真实返回 null，保留 String!；包可见 Resolver 也保留旧 JVM 可见性，前缀选择和 TTL 缓存已下沉 | [PapiExpansionState.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/papi/PapiExpansionState.kt) |
| [hook/papi/PapiRegistrar.java](../../libraries/hook/src/main/java/me/kzheart/klib/hook/papi/PapiRegistrar.java) | register 的标识/Expansion/Disposable 是公开 SAM 平台类型，available 保持原 default true 与 FunctionalInterface | [BukkitPapiRegistrar.kt](../../libraries/hook/src/main/kotlin/me/kzheart/klib/hook/papi/BukkitPapiRegistrar.kt) |

### klib-data（9）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [data/KeyValueStore.java](../../storage/data/src/main/java/me/kzheart/klib/data/KeyValueStore.java) | get/put/entries 是公开存储 SPI，保留字节数组、Map 与 Optional 的平台空性；命名空间/持久化实现不在契约层 | [JdbcStorageSession.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/JdbcStorageSession.kt)、[JsonStorageProvider.kt](../../storage/json/src/main/kotlin/me/kzheart/klib/data/json/JsonStorageProvider.kt) |
| [data/Migration.java](../../storage/data/src/main/java/me/kzheart/klib/data/Migration.java) | 公开嵌套 MigrationAction 的 TransactionContext! 与 throws Exception 必须保留，且原 apply 为包可见；迁移校验/执行值状态在 Kotlin | [Migration.kt](../../storage/data/src/main/kotlin/me/kzheart/klib/data/Migration.kt) |
| [data/StorageProvider.java](../../storage/data/src/main/java/me/kzheart/klib/data/StorageProvider.java) | open 的 CompletionStage<StorageSession!>! 为公开可实现 SPI，保留平台泛型和 Disposable 继承 | [AbstractJdbcStorageProvider.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/AbstractJdbcStorageProvider.kt)、[JsonStorageProvider.kt](../../storage/json/src/main/kotlin/me/kzheart/klib/data/json/JsonStorageProvider.kt) |
| [data/StorageSession.java](../../storage/data/src/main/java/me/kzheart/klib/data/StorageSession.java) | 异步 get/entries/transaction 的嵌套泛型平台类型保留，避免收紧外部会话实现的回调或结果 | [JdbcStorageSession.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/JdbcStorageSession.kt)、[JsonStorageProvider.kt](../../storage/json/src/main/kotlin/me/kzheart/klib/data/json/JsonStorageProvider.kt) |
| [data/StorageTransaction.java](../../storage/data/src/main/java/me/kzheart/klib/data/StorageTransaction.java) | `execute(TransactionContext!): T! throws Exception` 公开泛型 SAM，保留 checked 异常和双向空性 | [JdbcStorageSession.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/JdbcStorageSession.kt)、[JsonStorageProvider.kt](../../storage/json/src/main/kotlin/me/kzheart/klib/data/json/JsonStorageProvider.kt) |
| [data/TransactionContext.java](../../storage/data/src/main/java/me/kzheart/klib/data/TransactionContext.java) | schema 名称及继承键值 SPI 的平台参数保留，compareAndSetSchemaVersion 是原 Java default；事务锁/提交回滚在后端 Kotlin | [JdbcStorageSession.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/JdbcStorageSession.kt)、[JsonStorageProvider.kt](../../storage/json/src/main/kotlin/me/kzheart/klib/data/json/JsonStorageProvider.kt) |
| [data/cache/DataCodec.java](../../storage/data/src/main/java/me/kzheart/klib/data/cache/DataCodec.java) | `encode(T!): byte[]!` 与 `decode(byte[]!): T!` 是公开双向 SPI，保留自定义值/字节空性 | [KeyValuePlayerDataRepository.kt](../../storage/data/src/main/kotlin/me/kzheart/klib/data/cache/KeyValuePlayerDataRepository.kt) |
| [data/cache/PlayerDataRepository.java](../../storage/data/src/main/java/me/kzheart/klib/data/cache/PlayerDataRepository.java) | load/save 的 UUID!、T!、CompletionStage<T!>! 是可替换缓存后端契约；加载合并与写回生命周期在 Kotlin | [KeyValuePlayerDataRepository.kt](../../storage/data/src/main/kotlin/me/kzheart/klib/data/cache/KeyValuePlayerDataRepository.kt)、[PlayerDataCache.kt](../../storage/data/src/main/kotlin/me/kzheart/klib/data/cache/PlayerDataCache.kt) |
| [data/package-info.java](../../storage/data/src/main/java/me/kzheart/klib/data/package-info.java) | `me.kzheart.klib.data` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-data-jdbc（5）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [data/jdbc/RowMapper.java](../../storage/jdbc/src/main/java/me/kzheart/klib/data/jdbc/RowMapper.java) | `map(ResultSet!): T! throws SQLException` 为公开 JDBC SAM，保留可空 SQL 映射结果、参数空性及 checked 声明 | [SqlClient.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/SqlClient.kt) |
| [data/jdbc/SqlMigrationEngine.java](../../storage/jdbc/src/main/java/me/kzheart/klib/data/jdbc/SqlMigrationEngine.java) | currentVersion/migrate 的 DataSource! 与 throws Exception 是外部迁移引擎 SPI；库只作版本门禁，不能虚构内置迁移引擎 | [JdbcExecutor.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/JdbcExecutor.kt) |
| [data/jdbc/SqlParameters.java](../../storage/jdbc/src/main/java/me/kzheart/klib/data/jdbc/SqlParameters.java) | `bind(PreparedStatement!) throws SQLException` 公开 SAM 保留平台输入/checked 异常，none 只是无操作参数绑定值 | [SqlClient.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/SqlClient.kt) |
| [data/jdbc/SqlWork.java](../../storage/jdbc/src/main/java/me/kzheart/klib/data/jdbc/SqlWork.java) | `execute(SqlClient!): T! throws Exception` 公开事务 SAM 保留平台泛型和 checked 异常 | [JdbcExecutor.kt](../../storage/jdbc/src/main/kotlin/me/kzheart/klib/data/jdbc/JdbcExecutor.kt) |
| [data/jdbc/package-info.java](../../storage/jdbc/src/main/java/me/kzheart/klib/data/jdbc/package-info.java) | `me.kzheart.klib.data.jdbc` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-data-json（1）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [data/json/package-info.java](../../storage/json/src/main/java/me/kzheart/klib/data/json/package-info.java) | `me.kzheart.klib.data.json` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-data-sqlite（1）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [data/sqlite/package-info.java](../../storage/sqlite/src/main/java/me/kzheart/klib/data/sqlite/package-info.java) | `me.kzheart.klib.data.sqlite` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-data-mysql（1）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [data/mysql/package-info.java](../../storage/mysql/src/main/java/me/kzheart/klib/data/mysql/package-info.java) | `me.kzheart.klib.data.mysql` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-data-postgresql（0）

本快照无生产 Java 文件。

### klib-compat（4）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [compat/CompatProvider.java](../../platform/compat/src/main/java/me/kzheart/klib/compat/CompatProvider.java) | capability 的 `<T> Optional<T!>!` 与 ServerVersion/String 平台类型是公开提供者契约；能力图与查找由共享 Kotlin 基类持有 | [AbstractCompatProvider.kt](../../platform/compat/src/main/kotlin/me/kzheart/klib/compat/AbstractCompatProvider.kt)、[CompatResolver.kt](../../platform/compat/src/main/kotlin/me/kzheart/klib/compat/CompatResolver.kt) |
| [compat/SidebarBridge.java](../../platform/compat/src/main/java/me/kzheart/klib/compat/SidebarBridge.java) | 公开数据包宿主 SPI 的 Player!/String! 允许既有 Kotlin nullable/non-null 实现；差异计算和资源生命周期不泄漏给各版本入口 | [ModernSidebarBridge.kt](../../platform/compat/src/main/kotlin/me/kzheart/klib/compat/internal/ModernSidebarBridge.kt)、[Sidebars.kt](../../platform/compat/src/main/kotlin/me/kzheart/klib/compat/sidebar/Sidebars.kt) |
| [compat/package-info.java](../../platform/compat/src/main/java/me/kzheart/klib/compat/package-info.java) | `me.kzheart.klib.compat` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [compat/sidebar/package-info.java](../../platform/compat/src/main/java/me/kzheart/klib/compat/sidebar/package-info.java) | `me.kzheart.klib.compat.sidebar` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-compat-v1_12（1）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [compat/v1_12/package-info.java](../../platform/versions/v1_12/src/main/java/me/kzheart/klib/compat/v1_12/package-info.java) | `me.kzheart.klib.compat.v1_12` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-compat-v1_20（1）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [compat/v1_20/package-info.java](../../platform/versions/v1_20/src/main/java/me/kzheart/klib/compat/v1_20/package-info.java) | `me.kzheart.klib.compat.v1_20` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-compat-v1_21（1）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [compat/v1_21/package-info.java](../../platform/versions/v1_21/src/main/java/me/kzheart/klib/compat/v1_21/package-info.java) | `me.kzheart.klib.compat.v1_21` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-compat-v26（1）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [compat/v26/package-info.java](../../platform/versions/v26/src/main/java/me/kzheart/klib/compat/v26/package-info.java) | `me.kzheart.klib.compat.v26` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-script（52）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [script/CombinedAction.java](../../runtime/script/src/main/java/me/kzheart/klib/script/CombinedAction.java) | execute 的 StatementArguments!/ScriptContext!/CompletionStage<Object!>! 为公开 SAM 平台契约，保留 FunctionalInterface | [Statements.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/Statements.kt) |
| [script/CommandSink.java](../../runtime/script/src/main/java/me/kzheart/klib/script/CommandSink.java) | dispatch/dispatchAsOperator 可返回 null，sender 为宿主 Object!；保留公开 SAM 与不支持 op 的 Java default | [BukkitScriptServices.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BukkitScriptServices.kt)、[BuiltInStatements.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BuiltInStatements.kt) |
| [script/DelayScheduler.java](../../runtime/script/src/main/java/me/kzheart/klib/script/DelayScheduler.java) | 公开延迟 SAM 的 Duration!/CompletionStage<Object!>! 保留实现者空性；调度器与取消策略由宿主/Kotlin 管理 | [BukkitScriptServices.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BukkitScriptServices.kt)、[FlowScriptActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/FlowScriptActions.kt) |
| [script/ForeignVarTableEntries.java](../../runtime/script/src/main/java/me/kzheart/klib/script/ForeignVarTableEntries.java) | 原包可见适配类型维持 JVM 可见性；Collection<Map.Entry<String,Object>> 经过已复现 K2 混合编译边界，values 原样返回。snapshot 仅保持 values 的顺序/null/Future 原值，不是另一套变量引擎 | [ScriptFrames.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/ScriptFrames.kt)、[TabooLibKetherProtocol.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/TabooLibKetherProtocol.kt) |
| [script/JavaScriptEvaluator.java](../../runtime/script/src/main/java/me/kzheart/klib/script/JavaScriptEvaluator.java) | eval 的 Map<String,Object!>! 与 Object! 求值结果保留平台泛型/null，公开 SAM 仍允许外部引擎 | [ScriptJavaScriptEngines.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/ScriptJavaScriptEngines.kt) |
| [script/KetherParserResolver.java](../../runtime/script/src/main/java/me/kzheart/klib/script/KetherParserResolver.java) | 原顶级接口是包可见；未知动作解析回退允许 nullable parser 返回，保留 Java 平台返回与包边界 | [CoreScriptRuntime.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/CoreScriptRuntime.kt)、[GuardKetherInterop.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/GuardKetherInterop.kt) |
| [script/MessageSink.java](../../runtime/script/src/main/java/me/kzheart/klib/script/MessageSink.java) | `send(Object!, String!)` 是公开宿主 SAM，保留 nullable/non-null override 和 FunctionalInterface | [BukkitScriptServices.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BukkitScriptServices.kt)、[StructuredScriptActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/StructuredScriptActions.kt) |
| [script/OpenContainerBridge.java](../../runtime/script/src/main/java/me/kzheart/klib/script/OpenContainerBridge.java) | ContainerDiscovery/RemoteStatementSource 是公开嵌套 Java SPI，平台泛型与二进制名不变；RemoteResolution.value 真可为 null。外层调用只有 Kotlin fallback 委派 | [ContainerFallback.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/ContainerFallback.kt) |
| [script/PlaceholderResolver.java](../../runtime/script/src/main/java/me/kzheart/klib/script/PlaceholderResolver.java) | `resolve(Object!, String!): String!` 是公开宿主 SAM，保留平台参数/返回与 FunctionalInterface | [NativeCheckActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/NativeCheckActions.kt) |
| [script/PlayerQuery.java](../../runtime/script/src/main/java/me/kzheart/klib/script/PlayerQuery.java) | property 真可为 null，sender/value 是宿主 Object!；公开 SPI、默认只读 write 和嵌套 Method 的二进制形状保留 | [BukkitScriptServices.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BukkitScriptServices.kt)、[NativeValueActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/NativeValueActions.kt)、[NativeCheckActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/NativeCheckActions.kt) |
| [script/QuestActionParser.java](../../runtime/script/src/main/java/me/kzheart/klib/script/QuestActionParser.java) | 旧执行级 parser 的 call/context/stage 保留公开 SAM 平台类型；与 kether.core 同名解析 SPI 分开核对 | [StatementRegistry.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/StatementRegistry.kt) |
| [script/ScriptContext.java](../../runtime/script/src/main/java/me/kzheart/klib/script/ScriptContext.java) | variableOrNull/removeVariable 真可返回 null，保留 Object! 与嵌套 Builder 形状；变量、帧派生、发送者解析和服务表在 Kotlin | [ScriptContext.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/ScriptContext.kt) |
| [script/ScriptEngine.java](../../runtime/script/src/main/java/me/kzheart/klib/script/ScriptEngine.java) | eval/evalCondition 是公开可替换引擎 SPI，String!/ScriptContext!/CompletionStage 平台泛型保留 | [KetherScriptEngine.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/KetherScriptEngine.kt) |
| [script/ScriptLogger.java](../../runtime/script/src/main/java/me/kzheart/klib/script/ScriptLogger.java) | `log(Level!, String!)` 公开宿主 SAM 保留平台参数/FunctionalInterface，日志级别处理由 Kotlin 调用端负责 | [BukkitScriptServices.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BukkitScriptServices.kt)、[FlowScriptActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/FlowScriptActions.kt) |
| [script/ScriptPlatform.java](../../runtime/script/src/main/java/me/kzheart/klib/script/ScriptPlatform.java) | console/player/location 等宿主对象返回与 String/List 输入保留平台类型；scoreboard 的 null 确有移除语义，原 default 不支持行为保留 | [BukkitScriptServices.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BukkitScriptServices.kt)、[GameScriptActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/GameScriptActions.kt) |
| [script/ScriptPropertyAccess.java](../../runtime/script/src/main/java/me/kzheart/klib/script/ScriptPropertyAccess.java) | read/write 的实例、键、值为公开平台 SPI；嵌套 Result.value 真可为 null，保留“支持 null”与“不支持”的原二进制/值语义 | [ScriptProperties.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/ScriptProperties.kt) |
| [script/ScriptSenderQuery.java](../../runtime/script/src/main/java/me/kzheart/klib/script/ScriptSenderQuery.java) | isPlayer/name/isOnline 的 Object! sender 与 String! 返回是公开实现契约，默认在线语义保留 | [BukkitScriptServices.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/BukkitScriptServices.kt) |
| [script/StatementArguments.java](../../runtime/script/src/main/java/me/kzheart/klib/script/StatementArguments.java) | value 变量/字面量求值可为 null，保留 Object! 返回及包可见构造；名称查找和求值在 Kotlin | [StatementArguments.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/StatementArguments.kt) |
| [script/StatementCall.java](../../runtime/script/src/main/java/me/kzheart/klib/script/StatementCall.java) | value 参数求值可为 null，保留 Object!；嵌套 NestedEvaluator 原包可见，解析参数/嵌套求值委派 Kotlin | [StatementCall.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/StatementCall.kt) |
| [script/StatementRegistration.java](../../runtime/script/src/main/java/me/kzheart/klib/script/StatementRegistration.java) | 公开生命周期 SPI 的 namespace/name 平台返回与 isRegistered 方法/属性接口调用契约保持；注册状态/撤销由 Kotlin 管理 | [StatementRegistry.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/StatementRegistry.kt) |
| [script/TabooLibKetherInterop.java](../../runtime/script/src/main/java/me/kzheart/klib/script/TabooLibKetherInterop.java) | 公开嵌套 ContainerDiscovery/OpenContainer 是平台类型 SPI，OpenResult.getValue 真可为 null；保留原实例 synchronized 监视器。发现、重放、冲突和撤销状态集中在 Kotlin | [TabooLibInteropState.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/TabooLibInteropState.kt) |
| [script/UnknownStatementResolver.java](../../runtime/script/src/main/java/me/kzheart/klib/script/UnknownStatementResolver.java) | `resolve(String!, ScriptContext!): CompletionStage<Object!>!` 公开 fallback SAM 保留平台空性与 FunctionalInterface | [ContainerFallback.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/ContainerFallback.kt)、[TabooLibInteropState.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/TabooLibInteropState.kt) |
| [script/kether/core/AbstractQuestContext.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/AbstractQuestContext.java) | 公开自引用泛型基类、protected 真字段、开放工厂和嵌套 Frame/VarTable 为扩展 ABI；参数与返回保留 nullable/non-null override。Java 保存字段/分派，终止、续接、帧关闭和变量作用域在 Kotlin | [QuestLifecycle.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/QuestLifecycle.kt)、[FrameExecution.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/FrameExecution.kt) |
| [script/kether/core/AbstractStringReader.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/AbstractStringReader.java) | protected content/index/mark 可被既有子类直接访问，且开放 nextToken/expect/failExpect 等 String 平台类型扩展点不能固定 override 空性；扫描共享 TokenScanner，简单游标/边界操作仍留 Java | [TokenScanner.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/TokenScanner.kt) |
| [script/kether/core/ArgType.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/ArgType.java) | `read(QuestReader!): T! throws LocalizedException` 公开泛型 SAM 保留平台空性、FunctionalInterface 和 Exceptions 元数据 | [ArgumentReaders.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ArgumentReaders.kt) |
| [script/kether/core/ArgTypes.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/ArgTypes.java) | static ArgType<Integer/Long/...> 的嵌套类型是 Java 平台类型；旧 `ArgType<Int?> = ArgTypes.INT` 需要这一声明边界。实际数值/时长/列表读取在 Kotlin | [ArgumentReaders.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ArgumentReaders.kt)、[CoerceType.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/CoerceType.kt) |
| [script/kether/core/BlockReader.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/BlockReader.java) | protected blocks/service/namespace/currentBlock 与开放解析工厂/处理钩子保留真字段和平台类型 override；currentBlock 还可为 null。块组装/定位/匿名命名在 Kotlin | [BlockParser.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/BlockParser.kt) |
| [script/kether/core/DefaultRegistry.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/DefaultRegistry.java) | 公开 BiFunction 的嵌套泛型平台空性不能固定为 Kotlin 单一空性；原 synchronized 锁住公开实例。集合、命名空间与 fallback 查找集中于 Kotlin | [RegistryState.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/RegistryState.kt) |
| [script/kether/core/DurationType.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/DurationType.java) | javac 生成的 `read(QuestReader): Object` 泛型桥带 Exceptions 属性；仅给 Kotlin 具体实现 @Throws 不会给合成桥补同样元数据。Java 只保留桥生成边界 | [ArgumentReaders.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ArgumentReaders.kt) |
| [script/kether/core/ListType.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/ListType.java) | 同 DurationType，需 javac 生成带 Exceptions 的 Object 返回泛型桥，并保持原不变 List<T> Signature 及包可见构造 | [ArgumentReaders.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ArgumentReaders.kt) |
| [script/kether/core/LocalizedException.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/LocalizedException.java) | 可继承类的 of/supply/batch 非 final static 允许 Java 隐藏，开放 getter/stream/then 保留平台类型；transient params、UID 与私有 Concat 序列化字段原形保留。格式化/聚合仍是 Java 小型错误模型 | 无独立 Kotlin 值实现；由 LoadError.kt 构造，完整序列化图兼容未由本清单验证 |
| [script/kether/core/ParsedAction.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/ParsedAction.java) | `get(ActionProperty<T>, T)` 的真实 nullable fallback 仍返回 T!，还保留 ActionProperty 嵌套泛型身份与可修改属性表语义。属性表/相等性仍是 Java 值行为，执行屏障与 future 联动在 Kotlin | [ActionExecution.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ActionExecution.kt) |
| [script/kether/core/Parser.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/Parser.java) | 公开嵌套 Action/Function3/4/5 为泛型 Java SAM，需保留各参数/返回的平台空性及 Instance 二进制名；组合、回溯、并行汇合与顺序 fold 在 Kotlin | [ParserCombinators.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ParserCombinators.kt) |
| [script/kether/core/PropertyAccessor.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/PropertyAccessor.java) | `read(Frame!, Object!, String!): Object!` 公开 SAM 保留宿主值真实 null 及 nullable/non-null override | [CoreScriptRuntime.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/CoreScriptRuntime.kt)、[ScriptProperties.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/ScriptProperties.kt) |
| [script/kether/core/Quest.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/Quest.java) | Quest/Block 公开 SPI 的 String、Map/List/Optional 平台泛型和 getter 形式保持，第三方 nullable/non-null getter override 与 Kotlin 属性源码仍成立 | [BlockParser.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/BlockParser.kt) |
| [script/kether/core/QuestAction.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestAction.java) | 公开抽象 process 的 Frame!/CompletableFuture<T!>! 保留实现者空性，非 final static noop 保留 Java 子类静态隐藏；仅无操作动作的小实现留在 Java | [ActionExecution.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ActionExecution.kt)、[CoreActions.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/CoreActions.kt) |
| [script/kether/core/QuestActionParser.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestActionParser.java) | 方法级泛型 `<T> resolve` 不是普通 Kotlin fun interface 可直接承载的 SAM，且 QuestReader/QuestAction 平台类型必须保留；静态 of 只作类型适配 | [ParserCombinators.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ParserCombinators.kt)、[ActionReader.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ActionReader.kt) |
| [script/kether/core/QuestContext.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestContext.java) | 公开 Frame/VarTable 多级 SPI 的平台泛型与异型 exitStatus get/set 保留；含 Map.Entry、Future 和真实 nullable getOrNull/parent。默认帧遍历/Map 快照仍是 Java 小型公共行为 | [QuestLifecycle.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/QuestLifecycle.kt)、[FrameExecution.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/FrameExecution.kt) |
| [script/kether/core/QuestFuture.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestFuture.java) | getFuture 在未 run 或 close 后真为 null，保留 CompletableFuture<T!>!；状态赋值/前置检查仍 Java 小对象，完成与取消传播在 Kotlin | [ActionExecution.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ActionExecution.kt) |
| [script/kether/core/QuestLoader.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestLoader.java) | 公开加载 SPI 的方法级泛型、参数/返回平台类型及 Path 重载 throws IOException 保留，默认重载只是补命名空间 | [BlockParser.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/BlockParser.kt) |
| [script/kether/core/QuestReader.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestReader.java) | 公开读取 SPI 的泛型 next/nextAction、String 平台类型和 nullable/non-null override 保留；getter/property 接口双源码仍支持。默认方法只组合类型读取 | [ActionReader.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ActionReader.kt)、[ArgumentReaders.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ArgumentReaders.kt)、[TokenScanner.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/TokenScanner.kt) |
| [script/kether/core/QuestRegistry.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestRegistry.java) | 公开注册 SPI 中 BiFunction 的多层平台泛型、Optional/Collection 返回与 setter 空性保持，不能仅通过 JVM descriptor 判兼容 | [RegistryState.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/RegistryState.kt) |
| [script/kether/core/QuestService.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/QuestService.java) | 公开服务 SPI 中集合/上下文平台泛型及 getPropertyAccessor 默认真 null 保留；同时保留 getter 方法/属性接口契约 | [QuestServiceState.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/QuestServiceState.kt) |
| [script/kether/core/ServiceHolder.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/ServiceHolder.java) | 全局钩子未安装时返回 null，Java QuestService<?>! 同时允许旧 Kotlin nullable/非空接收；只有 volatile 引用与 get/set，无可提取执行引擎 | [QuestServiceState.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/QuestServiceState.kt) |
| [script/kether/core/SimpleQuest.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/SimpleQuest.java) | 构造允许 null id，SimpleBlock 允许 null label，getId/getLabel 必须仍为 String!；不是因普通 getter 才保留。Java 仍保存快照/视图、blockOf 小查找和值行为 | [BlockParser.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/BlockParser.kt) |
| [script/kether/core/SimpleQuestLoader.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/SimpleQuestLoader.java) | 公开可继承加载器的 load/newBlockReader 允许 nullable/non-null override，保留 IOException 与 protected 工厂分派；文件读取/UTF-8 接入很薄，解析在 Kotlin | [BlockParser.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/BlockParser.kt) |
| [script/kether/core/SimpleQuestService.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/SimpleQuestService.java) | getPropertyAccessor 在未安装时真为 null，平台返回才是 Java 必要边界，不是普通 getter 双写法。可变服务表、执行登记/快照、executor 归属在 Kotlin | [QuestServiceState.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/QuestServiceState.kt) |
| [script/kether/core/SimpleReader.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/SimpleReader.java) | protected 字段、开放 beforeParse/wrap/nextAnonAction 和平台泛型 override 保留；TokenScanner 通过原 skip 钩子推进游标，不能绕过子类行为。动作解析在 Kotlin | [ActionReader.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/ActionReader.kt)、[TokenScanner.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/kether/core/TokenScanner.kt) |
| [script/kether/core/TokenBlock.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/TokenBlock.java) | 公开构造未拒绝 null token，getToken 真可返回 String!；两个 boolean getter 可纯 Kotlin 桥接，实际保留依据是 token 平台类型。只有三个字段及构造/访问 | 无独立 Kotlin 值实现；由 TokenScanner.kt、ActionReader.kt 使用 |
| [script/kether/core/package-info.java](../../runtime/script/src/main/java/me/kzheart/klib/script/kether/core/package-info.java) | `me.kzheart.klib.script.kether.core` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [script/package-info.java](../../runtime/script/src/main/java/me/kzheart/klib/script/package-info.java) | `me.kzheart.klib.script` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |
| [script/taboolib/common/OpenResult.java](../../runtime/script/src/main/java/me/kzheart/klib/script/taboolib/common/OpenResult.java) | getValue 真可为 null，需保留 Object! 及协议反射 getter 名；成功标志/值由 Kotlin 持有，不能只凭 getter 双入口声称 Java 必需 | [OpenResult.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/taboolib/common/OpenResult.kt) |

### klib-remote（7）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [remote/DiagnosticContributor.java](../../runtime/remote/src/main/java/me/kzheart/klib/remote/DiagnosticContributor.java) | 公开 contribute 的 Context!/Map<String, ?>! 与 throws Exception 保留，嵌套 Context 的 error/operation 可为 null；采集与预算隔离在 Kotlin | [KlibDiagnosticContributor.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/KlibDiagnosticContributor.kt)、[RemoteLogger.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/RemoteLogger.kt) |
| [remote/RemoteBatchResult.java](../../runtime/remote/src/main/java/me/kzheart/klib/remote/RemoteBatchResult.java) | 嵌套 EventResult.error 真可为 null，保留 String! 与原嵌套类型名；响应不可变快照/结果值实现由 Kotlin 承担 | [RemoteBatchResult.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/RemoteBatchResult.kt) |
| [remote/RemoteClient.java](../../runtime/remote/src/main/java/me/kzheart/klib/remote/RemoteClient.java) | settings 尚未刷新时真为 null，保留 RemoteSettings!；包可见传输入口和 throws IOException 保持，网络/编码/回执校验全部委派 Kotlin | [RemoteClient.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/RemoteClient.kt) |
| [remote/RemoteEvent.java](../../runtime/remote/src/main/java/me/kzheart/klib/remote/RemoteEvent.java) | 可继承类的非 final static of 允许同包子类静态隐藏，Kotlin @JvmStatic 会 final；包可见构造/immutableCopy 也保留原边界，深快照与保留字段生成在 Kotlin | [RemoteEvent.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/RemoteEvent.kt)、[JsonValueSnapshot.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/JsonValueSnapshot.kt) |
| [remote/RemoteOperation.java](../../runtime/remote/src/main/java/me/kzheart/klib/remote/RemoteOperation.java) | current 在无上下文时真为 null；嵌套 Context.parentId/phase 也可 null，保留三处平台返回及 Context 二进制形状。激活/恢复与线程状态在 Kotlin | [RemoteOperation.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/RemoteOperation.kt) |
| [remote/RpkTransport.java](../../runtime/remote/src/main/java/me/kzheart/klib/remote/RpkTransport.java) | 原接口包可见，保留 JVM 包封装；String! 返回、throws IOException 与 default queueIdentity 不改变，HTTP 实现在 Kotlin | [HttpRpkTransport.kt](../../runtime/remote/src/main/kotlin/me/kzheart/klib/remote/HttpRpkTransport.kt) |
| [remote/package-info.java](../../runtime/remote/src/main/java/me/kzheart/klib/remote/package-info.java) | `me.kzheart.klib.remote` 的 Java 包级 Javadoc 载体；不含运行实现，不计作未迁移执行逻辑 | 不适用；包文档 |

### klib-guard-api（9）

| Java 文件 | 保留边界与仍留在 Java 的行为 | Kotlin 实现或使用位置 |
| --- | --- | --- |
| [guard/KlibCloudPlugin.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/KlibCloudPlugin.java) | 公开 Java 生命周期基类保留 protected load/setup/disable 的平台空性与 checked 异常、final synchronized 入口及原实例监视器；延迟构造内部控制器，不在子类构造时主动创建。加载回滚、Scope 装配与关闭异常流程已移至 Kotlin | [KlibCloudPluginLifecycle.kt](../../runtime/guard-api/src/main/kotlin/me/kzheart/klib/guard/KlibCloudPluginLifecycle.kt) |
| [guard/PluginHost.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/PluginHost.java) | 父加载器提供的公开 host SPI，商品 ID、Bukkit host、文件/日志与 broker 为平台类型；不把商品私有 Kotlin/脚本实现类型放进签名 | 无库内 host 实现；由外部门户实现 |
| [guard/RemotePluginEntrypoint.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/RemotePluginEntrypoint.java) | 父加载器生命周期 SPI 保留 PluginHost! 参数和各方法 throws Exception，第三方入口 nullable/non-null override 不收紧 | KlibCloudPlugin.java 是公开基类；外部门户执行不在本仓库 |
| [guard/kether/KetherInteropBroker.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/kether/KetherInteropBroker.java) | 跨加载器 attach 只传商品标识、代次、ClassLoader 与父加载器接口，保留平台参数/返回；路由 broker 的实际实现不属于本仓库 | 无库内 broker 实现；script 的 GuardKetherInterop.kt 是调用方 |
| [guard/kether/KetherInteropEndpoint.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/kether/KetherInteropEndpoint.java) | 父加载器契约以 long action 句柄与 Object/CompletionStage 传值，不泄漏商品私有 parser/action/frame 类型；保留平台空性和明确生命周期 | [GuardKetherInterop.kt](../../runtime/script/src/main/kotlin/me/kzheart/klib/script/GuardKetherInterop.kt) |
| [guard/kether/KetherInteropPeer.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/kether/KetherInteropPeer.java) | 父加载器容器代理 SPI 的 name/call 和 Object... 保留平台类型；Java 声明本身不要求解析商品侧 Kotlin metadata | 无库内门户 peer 实现；script 的 GuardKetherInterop.kt 使用 |
| [guard/kether/KetherInteropProtocol.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/kether/KetherInteropProtocol.java) | 父加载器共享的纯 Java 编译期常量载体，无运行算法；保留与边界九文件清单一致的轻量 protocol 类，不宣称 Kotlin 语言无法表达这些常量 | 无 Kotlin 实现；常量契约 |
| [guard/kether/KetherInteropRegistration.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/kether/KetherInteropRegistration.java) | 父加载器 AutoCloseable 注册句柄保留平台 namespace/actions 与撤销、关闭契约，不把商品状态实现暴露给门户 | 无库内门户注册实现；script 的 GuardKetherInterop.kt 持有此句柄 |
| [guard/kether/KetherInteropResult.java](../../runtime/guard-api/src/main/java/me/kzheart/klib/guard/kether/KetherInteropResult.java) | 父加载器的轻量 boolean/Object 返回形状，getValue 真可为 null，保留 Object!、共享 FAILED 值与 Java getter 名；无业务算法 | 无 Kotlin 实现；跨加载器结果值契约 |

## 验证边界与复核方法

- 文件清单、数量、相对链接由模块映射重新核对；不是按旧顶层 `klib-*` 目录猜测
- [JVM ABI 比较器](../../scripts/check-jvm-abi.py)用于 descriptor、Signature、Exceptions、继承/可见性和 Java 8 classfile；nullable 平台类型、方法/属性双源码、SAM 和静态隐藏还须各自编译旧消费者
- 反射 default/注解默认值及异常 UID 检查不能替代完整跨版本 ObjectInputStream 对象图测试；K2 混合编译适配的合理性仅限已复现的 Map.Entry 边界
- 每次新增或移除 Java 门面都须重扫本表。当前清单已包含 nullable 返回修复新增的门面，并删除已迁 Kotlin 的 primitive SPI/值对象；后续最终验收结论以绑定最终源码与产物的[迁移验证报告](kotlin-implementation-migration.md)为准
