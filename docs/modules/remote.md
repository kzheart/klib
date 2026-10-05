# Remote 模块

把**当前插件明确选择的**日志、异常现场（Incident）和手动 Incident 投递到 Remote Server，用于线上排障。

| 模块名 | 制品 | 自动带入 |
| --- | --- | --- |
| `remote` | `me.kzheart.klib:klib-remote` | `core` |

- Java 8 模块。
- 和 Guard 的授权、实例绑定、风控、制品签名及其身份数据**完全独立**：两者不调用彼此、不共享 Key，也不把 Remote 安装标识当作授权证明。
- 不采集心跳、`latest.log`、根 Logger、其他插件 Logger 或平台级日志。
- 没有版本查询，也没有 `Redactor` 或平台自动脱敏。**事件正文是否包含敏感内容由插件开发者负责**，详见 [Remote 安全边界](../remote-security.md)。

## 快速开始

推荐通过 Gradle 插件在构建期把端点、公开 Key 和能力上限写入插件 JAR：

```kotlin
klib {
    main("com.example.market.MarketPlugin")
    modules {
        remote()
    }

    remote {
        endpoint("https://remote.example.test")
        publicKey("rpk_test_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG")
        exceptions.set(true)
        logs.set(true)
        manualIncidents.set(true)
    }
}
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

- 示例端点和 Key 仅用于展示格式，实际接入时替换为 Remote 服务提供的值。
- `KlibRemoteAccess` 由 Gradle 插件生成在 `main(...)` 类所在的包中，其他包使用时需要导入。常量和 DSL 的完整规则见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

**公开 Key：** `rpk_live_...` 和 `rpk_test_...` 是公开项目 Key。

- 只能读取 `/ingest/v1/settings`，并向 `/ingest/v1/batches` 写入事件，随 JAR 分发不是泄密。
- Key 不是用户、服务器、正版插件或事件来源的真实性证明。

**能力默认全关：**

- 所有能力默认关闭，客户端启动时也 fail-closed。
- 只有 `refreshPolicy()` 成功后，才按 **构建能力 ∩ 远端 Key/商品策略** 采集。
- 远端策略只能进一步收紧，永远不能开启构建时未允许的能力。

### 建立客户端、交付器与 Logger

- 在初始化流程中建立客户端、异步交付器和当前插件专属 Logger，并安排释放。
- 使用 `KPlugin` 时入口是无参 `setup()`（需要 Scope 时用 `context().scope()`），不能覆盖其 final 的 `onEnable/onDisable`。
- 安装标识首次 `get()` 和交付队列初始化会访问磁盘：放在受控的后台初始化流程中运行，完成后再把资源交给活动 Scope。
- 初始化期间如果插件已经关闭，应立即释放刚创建的 delivery 和 logger。
- **不要在主线程阻塞等待网络或磁盘操作。**

下面是构造代码片段（KPlugin 实例上下文）：

```java
import java.nio.file.Path;
import me.kzheart.klib.remote.InstallationId;
import me.kzheart.klib.remote.RemoteCapabilities;
import me.kzheart.klib.remote.RemoteClient;
import me.kzheart.klib.remote.RemoteDelivery;
import me.kzheart.klib.remote.RemoteEnvironment;
import me.kzheart.klib.remote.RemoteLogger;

RemoteCapabilities capabilities = RemoteCapabilities.builder()
        .exceptions(KlibRemoteAccess.EXCEPTIONS_ENABLED)
        .logs(KlibRemoteAccess.LOGS_ENABLED)
        .manualIncidents(KlibRemoteAccess.MANUAL_INCIDENTS_ENABLED)
        .build();

RemoteClient client = RemoteClient.http(
        KlibRemoteAccess.ENDPOINT,
        KlibRemoteAccess.PUBLIC_KEY,
        capabilities,
        InstallationId.forProduct("example.market", getDataFolder().toPath()),
        new RemoteEnvironment("2.4.0", "Paper 1.21.4", "17", "Linux"));

Path queue = getDataFolder().toPath().resolve("remote-queue");
RemoteDelivery delivery = RemoteDelivery.builder(client, queue).build();
RemoteLogger remote = RemoteLogger.builder("example.market", delivery)
        .policy(client::policy)
        .build();
```

端点校验：

| 入口 | 允许的端点 |
| --- | --- |
| `RemoteClient.http` | 只接受 HTTPS |
| `insecureLoopback` | 只允许 `http://127.0.0.1`、`localhost` 或 `::1`，用于本地测试 |

端点不能含 userinfo、query 或 fragment；校验失败也不会回显端点内容。

## 身份、环境与策略

**安装标识：** `InstallationId.forProduct(productId, dataDirectory)` 创建惰性供应器，首次 `get()` 时在 `dataDirectory/.klib-remote/` 读取或生成并持久化 16 字节安全随机值。

- 不读取 IP、MAC、主机名、硬件、端口或路径内容。
- 同一商品的数据目录跨重启稳定；不同商品不同；删除数据目录后会变。
- 它是匿名、商品作用域的安装标识，**不是授权或反滥用身份**。

**环境摘要：** 每个批次附带 `RemoteEnvironment(pluginVersion, minecraft, java, os)` 四个字段。不要把用户、密钥、完整配置或服务器路径放进去。

**`RemoteClient` 公开 v1 入口：**

| 方法 | 行为 |
| --- | --- |
| `refreshPolicy()` | 请求 settings，解析 `RemoteSettings`，再与 `RemoteCapabilities` 取交集。失败抛 `IOException`，但保留上次已生效策略 |
| `policy()` | 首次成功前为 paused 的 fail-closed 策略 |
| `settings()` | 最后一次成功的 settings；尚未成功时为 `null` |
| `sendBatch(...)` | 同步网络 I/O，通常只用于受控工具；Minecraft 业务路径应使用 `RemoteDelivery` |

- 通常无需在业务线程调用 `refreshPolicy()`：`RemoteDelivery` 会在后台立即刷新，之后默认每 60 秒刷新一次，可用 `settingsRefreshInterval(Duration)` 调整。
- 服务端 `accepting_events=false` 时，客户端把有效策略置为 paused。日志最低等级和采样率仍保留在 `RemotePolicy` 中，但不发送事件。
- 交付器首次成功取得 settings 前**绝不发送**；后续刷新失败则保留最近一次成功设置并按退避重试。

### settings 严格解析

<details>
<summary>展开：settings 的 v1 schema 校验与 Limits 预算 getter</summary>

- settings 以严格 v1 schema 解析：顶层、policy、limits、retention 的全部字段必须存在，且类型、层级与范围正确。
- 未知字段、重复字段、尾随 JSON 或把字段放错层级，都作为协议错误 fail-closed。
- `Limits` 公开 Key、IP、installation、ASN 与商品突发五个每分钟预算 getter，便于交付器和诊断界面解释服务端限制。

</details>

## 日志与 Incident

`RemoteLogger` 是插件自己的结构化入口：`info`、`warn`、`error` 以及 `log(Level, message, context)`。

- 始终尝试把日志放入内存日志窗口；是否独立投递由 logs 策略、最低等级和采样率决定。
- `bridge(Logger)` 只桥接所传入的**同名 Logger**，返回可释放的 `Disposable`。
- 不会监听根 Logger、子 Logger、`latest.log` 或其他插件。

```java
import java.util.logging.Level;
import me.kzheart.klib.remote.RemoteLogContext;

RemoteLogger remote = RemoteLogger.builder("example.market", delivery)
        .policy(client::policy)
        .build();

remote.log(Level.INFO, "listing refreshed",
        RemoteLogContext.builder()
                .context("listing_count", 42)
                .mdc("request_id", "request-example")
                .tag("listing")
                .build());

try {
    reloadListings();
} catch (Exception error) {
    remote.captureIncident("listing-reload", error);
}
```

`reloadListings` 是业务方法。

**`RemoteLogContext` 限制：**

| 项 | 上限 |
| --- | --- |
| context | 32 项 |
| MDC | 32 项 |
| 键 / 值 | 64 / 1024 UTF-8 字节 |
| 标签 | 16 个，各 64 字节 |

它**不会清理内容**。不要传入玩家聊天、命令、Token 或配置正文，除非你明确允许把它们上传。

### 自动与手动 Incident

| 方法 | 由哪个能力控制 | fingerprint |
| --- | --- | --- |
| `captureIncident(name, throwable)` | `exceptions` | 异常类型、cause 链和规范化堆栈 |
| `captureManualIncident(name, attributes)` | `manualIncidents` | `manual:<name>`（没有 Throwable） |

每一次捕获都是一个 **Incident**；服务端把同 fingerprint 的多次现场聚合为一个 **Issue**。

### Incident 内容与预算

| 内容 | 默认上限 |
| --- | --- |
| 异常快照：每层 Throwable 的类型、message、完整到预算的 stack、cause 和 suppressed | 32 层、每层 256 帧、每层 32 个 suppressed，整棵 Throwable 图 256 个不同节点 |
| `breadcrumb(category, message, context)` 记录的有界事实 | 64 条 / 64 KiB |
| 当前插件 Logger 近期的有界日志窗口（即使日志流关闭仍可进入 Incident） | 128 条 / 256 KiB |
| `DiagnosticContributor` 结果 | 最多 16 个；一次 Incident 共用 100 ms 采集时间；每个结果最多 64 项 / 64 KiB |
| 当前 `RemoteOperation`（如存在）、其父操作和祖先 | — |

- 共享的 cause 或 suppressed 节点按对象身份去重，防止循环和分支扩张绕过总预算。
- 每层 operation 带可选 `duration_ms`：SDK 用本地单调时钟计算结束时间减开始时间，尚未结束为 `null`。
- `RemoteOperation.wrapCurrent(...)` 可包装 `Runnable`、`Callable`、`Supplier` 跨异步边界；`RemoteScheduler` 为 `KScheduler` 自动包装。异步激活作用域不会提前结束被传播 operation 的计时。
- Contributor 的名称、采集和结果字符串化都在同一个有界 executor 与全局 deadline 内执行；超时、拒绝和异常编码为状态，不会阻断业务。

**JSON 快照全有或全无：** `RemoteEvent` payload、日志 context/MDC 和 Breadcrumb context 在入队前建立不可变 JSON 快照。

- 最大深度 32、总节点 4096、单个容器 256 项。
- 循环、非法类型或任一上限超限时，**整条事件被拒绝**，不会截断成看似有效但缺上下文的事件。

### 声明确定性原因

<details>
<summary>展开：通过 payload.confirmed_cause 显式提交确认原因</summary>

- 确定性校验器或解析器可以在自定义 Incident 的 `payload.confirmed_cause` 中显式提交 `target_type`、`target_id`、`summary`。
- 这是唯一会进入 control `confirmed_cause` 档的 marker。
- 标准 `captureIncident` 不会把 Throwable cause、message 或 operation outcome 自动升级为确认原因。
- 需要该能力的自定义采集，用 `RemoteEvent.of("incident", fields)` 构造完整 v1 Incident，并仍由现有 delivery 提交。

</details>

### 排障上下文与 Contributor

`KlibDiagnosticContributor` 将核心模块的 `DiagnosticSource` 接到 Contributor。当前 Config、Scheduler、
Command 与 Data 实现提供轻量内存快照；它们的语义分别见
[Config](config.md#remote-排障快照)、[Core](core.md)、[Command](command.md) 与 [Data](data.md)。
Contributor 不得在采集时读文件、访问网络或等待外部服务。

## 异步交付队列与错误

`RemoteDelivery` 的 `submit`/`accept` 只进入内存邮箱：

- 调用线程不做磁盘或网络 I/O，也不会把交付、序列化或队列故障抛回业务。
- `submit` 返回的 `CompletionStage<Boolean>`：事件原子写入本地队列后为 `true`；关闭、容量淘汰、无效事件或本地写入失败为 `false`，**不会以异常完成**。

**默认值：**

| 项 | 默认 |
| --- | --- |
| 磁盘预算 | 16 MiB |
| 磁盘事件数 | 4096 条 |
| TTL | 24 小时 |
| 内存邮箱 | 256 条 |
| 每批 | 最多 100 条，正文合计 1 MiB |
| 单事件 | 64 KiB，超限整体拒绝而不截断 |

- Incident 比普通日志优先；容量不足时先淘汰最旧日志以保留 Incident。
- 队列保存完整原始事件正文、安装标识和环境快照，**属于敏感本地数据**；目录必须由插件私有化保护，删除它会丢失未交付事件。

**跟随服务端限制：** 每次后台 settings 刷新后：

- 交付器把本地配置与服务端下发的 `max_event_bytes`、`max_batch_events`、`max_decompressed_bytes` 取较小值。
- 组批后对**完整 batch（含安装与环境 envelope）**实际 gzip，确认未超过 `max_compressed_bytes`。
- 服务端收紧限制后，已在队列中但超限的单个事件整条移除并计入 `droppedEvents()`，不会截断，也不会永久热重试；过大的候选 batch 会缩小后再尝试。

**队列目录：** 同一个队列目录绑定端点和公开 Key 的 SHA-256 身份，**换端点或 Key 必须使用新目录**。

<details>
<summary>展开：队列目录的权限、身份与损坏处理</summary>

- 整条父目录链不允许 group/other 或非 owner 写入。
- 文件系统必须提供稳定目录身份和可验证的 owner-only POSIX 权限或 ACL，否则构造时 fail-closed。
- 目录被替换，或父目录权限在运行期间变得可写，都会停止后续读写删除。
- 队列另有单进程锁和原子写入。
- 损坏项移入受限的 `quarantine`，不会因为声明长度分配无界内存。

</details>

### 重试与 HTTP 状态

失败采用带抖动的指数退避，默认 1 秒至 60 秒。

| 情况 | 行为 |
| --- | --- |
| `429` | 尊重 `Retry-After`（可超过退避上限） |
| `403` | 视为暂停，至少停 5 分钟再试 |
| `401`（包括 settings 刷新） | 终止交付器并保留本地队列，之后提交返回 `false` |
| 完整收据中的 `accepted`、`duplicate`、`rejected` | 都从队列移除 |
| 收据不完整、无效或网络失败 | 保留整个批次，用同一 `event_id` 重试 |

收据使用严格 JSON schema：

- 重复字段、未知字段、类型错误、尾随内容、索引、计数或 `event_id` 不一致，都视为协议失败并保留队列。
- `accepted` / `duplicate` 不得携带 `error`；`rejected` 必须携带非空 `error`。

## 线程与生命周期

- 把 `RemoteLogger`、桥接返回的 `Disposable` 和 `RemoteDelivery` 纳入插件生命周期，停服时调用 `dispose()`。
- `RemoteLogger.dispose()` 会停止 Contributor 线程。
- `RemoteDelivery.dispose()` 不等待在途网络请求。
- `RemoteClient` 没有关闭方法。
- 安装标识首次 `get()` 和队列初始化访问磁盘；`sendBatch(...)` 是同步网络 I/O。都不要放在主线程阻塞等待。
- `submit`/`accept` 在调用线程只进内存邮箱，不做磁盘或网络 I/O。
- Contributor 不得在采集时读文件、访问网络或等待外部服务。

## 迁移与协议

- 本模块没有旧诊断协议的兼容路由、兼容 shim 或导入器。
- 旧版未校验诊断数据不会导入，也不得在 Remote 中视为可信数据。
- 协议细节见 [Remote 协议 v1](../remote-protocol.md)。
- Remote 服务端实现和部署配置不属于本公共 Java 客户端仓库。

### 服务端控制面

<details>
<summary>展开：control 面能看到什么，以及 linked / confirmed_cause / nearby 的含义</summary>

服务端 control 面提供：

- Issue 四维环境分布、日志前后文，以及明确的 Incident/Issue 关联；
- Incident 三档因果关系；
- 安装筛选与最近 Issue、按日 rejected 趋势；
- 公开接入 origin、写入安全闸状态、limits / build 只读查询、本地会话恢复。

关联规则：

- 日志关联只接受 Incident 日志窗口中显式携带的 `(key_id,event_id)`。
- Incident 的 `linked`、`confirmed_cause`、`nearby` 分别代表显式 operation 边、客户端显式原因声明和单纯时间接近。
- 不按时间或文本自动推断根因。

完整路由与保留语义由对应 Remote 服务端版本的私有运维文档定义。

</details>

## 常见坑

- 公开 Key 不证明真实性：不要把 Remote 安装标识或 Key 当作授权、正版或反滥用依据。
- 远端策略只能收紧：构建时没打开的能力，服务端无法开启。
- `RemoteLogContext`、Breadcrumb 和 payload 不做脱敏，敏感内容需要插件自己排除。
- 超限事件整条拒绝或移除，不会截断。
- 换端点或公开 Key 时，必须换新的队列目录。

## 相关页面

- [快速开始](../../README.md)：插件完整入口。
- [Config](config.md#remote-排障快照)、[Core](core.md)、[Command](command.md)、[Data](data.md)：可接入的 `DiagnosticSource`。
- [Remote 安全边界](../remote-security.md)、[Remote 协议 v1](../remote-protocol.md)。
- [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)：`KlibRemoteAccess` 常量与 `remote { }` DSL。
