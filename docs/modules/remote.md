# klib-remote

模块名：`remote`
制品：`me.kzheart.klib:klib-remote`

`klib-remote` 将**当前插件明确选择的**日志、异常现场（Incident）和手动 Incident 投递到
Remote Server。它是 Java 8 模块，和 Guard 的授权、实例绑定、风控、制品签名及其身份数据完全独立：
两者不调用彼此、不共享 Key，也不把 Remote 安装标识当作授权证明。

Remote 不采集心跳、`latest.log`、根 Logger、其他插件 Logger 或平台级日志；没有版本查询，也没有
`Redactor` 或平台自动脱敏。事件正文是否包含敏感内容由插件开发者负责，详见
[Remote 安全边界](../remote-security.md)。

## 接入

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

`rpk_live_...` 和 `rpk_test_...` 是公开项目 Key：它们仅能读取 `/ingest/v1/settings` 并向
`/ingest/v1/batches` 写入事件，随 JAR 分发不是泄密。Key 不是用户、服务器、正版插件或事件来源的
真实性证明。生成的 `KlibRemoteAccess` 常量和 DSL 的完整规则见
[Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

所有能力默认关闭。客户端启动时也 fail-closed；只有 `refreshPolicy()` 成功后，才按
**构建能力 ∩ 远端 Key/商品策略**采集。远端策略只能进一步收紧，永远不能开启构建时未允许的能力。

## 快速开始

在 `onEnable` 中建立客户端、异步交付器和当前插件专属 Logger；在 `onDisable` 或所属 `Scope` 释放
它们。网络和磁盘 I/O 不应放在 Bukkit 主线程。

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

`RemoteClient.http` 只接受 HTTPS。`insecureLoopback` 只允许 `http://127.0.0.1`、`localhost` 或
`::1`，用于本地测试；端点不能含 userinfo、query 或 fragment，校验失败也不会回显端点内容。
直接使用坐标时，仍须显式添加 `klib-core`：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-remote:<klib-version>")
}
```

## 身份、环境与策略

`InstallationId.forProduct(productId, dataDirectory)` 在 `dataDirectory/.klib-remote/` 生成并持久化
16 字节安全随机值。它不读取 IP、MAC、主机名、硬件、端口或路径内容；同一商品的数据目录跨重启稳定，
不同商品不同，删除数据目录后会变。它是匿名、商品作用域的安装标识，不是授权或反滥用身份。

每个批次附带 `RemoteEnvironment(pluginVersion, minecraft, java, os)` 四个摘要字段。不要把用户、密钥、
完整配置或服务器路径放入其中。

`RemoteClient` 的公开 v1 入口为：

- `refreshPolicy()`：请求 settings，解析 `RemoteSettings`，再与 `RemoteCapabilities` 取交集；失败会抛出
  `IOException`，但保留上次已生效策略。通常无需在业务线程调用：`RemoteDelivery` 会在后台立即刷新，
  之后默认每 60 秒刷新一次；可用 `settingsRefreshInterval(Duration)` 调整。
- `policy()`：首次成功前为 paused 的 fail-closed 策略。
- `settings()`：最后一次成功 settings；尚未成功时为 `null`。
- `sendBatch(...)`：同步网络 I/O，通常只用于受控工具；Minecraft 业务路径应使用 `RemoteDelivery`。

settings 以严格 v1 schema 解析：顶层、policy、limits、retention 的全部字段必须存在且类型、层级与范围
正确；未知字段、重复字段、尾随 JSON 或把字段放错层级都会作为协议错误 fail-closed。`Limits` 同时公开
Key、IP、installation、ASN 与商品突发五个每分钟预算 getter，便于交付器和诊断界面解释服务端限制。

服务端 `accepting_events=false` 时，客户端把有效策略置为 paused。日志最低等级和采样率仍会保留在
`RemotePolicy` 中，但不发送事件。交付器首次成功取得 settings 前绝不发送；后续 settings 刷新失败则保留
最近一次成功设置并按退避重试。

## 日志与 Incident

`RemoteLogger` 是插件自己的结构化入口：`info`、`warn`、`error` 以及 `log(Level, message, context)`。
它会始终尝试将日志放入内存日志窗口；是否独立投递由 logs 策略、最低等级和采样率决定。`bridge(Logger)`
只桥接所传入的**同名 Logger**，并返回可释放的 `Disposable`；不会监听根 Logger、子 Logger、
`latest.log` 或其他插件。

```java
RemoteLogger remote = RemoteLogger.builder("example.market", delivery)
        .policy(client::policy)
        .build();

remote.log(java.util.logging.Level.INFO, "listing refreshed",
        me.kzheart.klib.remote.RemoteLogContext.builder()
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

`RemoteLogContext` 限制 context 和 MDC 各 32 项（键 64 UTF-8 字节、值 1024 字节），标签最多 16 个
（各 64 字节）。它不会清理内容；不要传入玩家聊天、命令、Token 或配置正文，除非你明确允许把它们上传。

自动 `captureIncident(name, throwable)` 与手动
`captureManualIncident(name, attributes)` 分别由 `exceptions` 与 `manualIncidents` 控制。前者的
fingerprint 由异常类型、cause 链和规范化堆栈计算；服务端将同 fingerprint 的一次次现场聚合为一个
**Issue**，每一次捕获都是一个 **Incident**。手动 Incident 没有 Throwable，fingerprint 为 `manual:<name>`。

异常快照包含每层 Throwable 的类型、message、完整到预算的 stack、cause 和 suppressed；默认上限为
32 层、每层 256 帧、每层 32 个 suppressed，以及整棵 Throwable 图 256 个不同节点。共享的 cause 或
suppressed 节点按对象身份去重，防止循环和分支扩张绕过总预算。一个 Incident 还含：

- 当前 `RemoteOperation`（如存在）、其父操作和祖先；每一层带可选 `duration_ms`，由 SDK 用本地单调
  时钟计算结束时间减开始时间，尚未结束为 `null`。`RemoteOperation.wrapCurrent(...)` 可包装 `Runnable`、
  `Callable`、`Supplier` 跨异步边界，`RemoteScheduler` 则为 `KScheduler` 自动包装；异步激活作用域不会
  提前结束被传播 operation 的计时。
- 用 `breadcrumb(category, message, context)` 记录的有界事实；默认 64 条 / 64 KiB。
- 当前插件 Logger 近期的有界日志窗口；默认 128 条 / 256 KiB，即使日志流关闭仍可进入 Incident。
- `DiagnosticContributor` 的结果；默认最多 16 个、一次 Incident 共用 100 ms 采集时间、每个结果最多
  64 项 / 64 KiB。Contributor 的名称、采集和结果字符串化都在同一个有界 executor 与全局 deadline
  内执行；超时、拒绝和异常会编码为状态，不会阻断业务。

`RemoteEvent` payload、日志 context/MDC 和 Breadcrumb context 在入队前建立全有或全无的不可变 JSON
快照：最大深度 32、总节点 4096、单个容器 256 项。循环、非法类型或任一上限超限时整条事件被拒绝，
不会截断成看似有效但缺上下文的事件。

确定性校验器或解析器可以在自定义 Incident 的 `payload.confirmed_cause` 中显式提交
`target_type`、`target_id`、`summary`。这是唯一会进入 control `confirmed_cause` 档的 marker；标准
`captureIncident` 不会把 Throwable cause、message 或 operation outcome 自动升级为确认原因。需要该能力的
自定义采集应使用 `RemoteEvent.of("incident", fields)` 构造完整 v1 Incident，并仍由现有 delivery 提交。

### 排障上下文与 Contributor

`KlibDiagnosticContributor` 将核心模块的 `DiagnosticSource` 接到 Contributor。当前 Config、Scheduler、
Command 与 Data 实现提供轻量内存快照；它们的语义分别见
[Config](config.md#remote-排障快照)、[Core](core.md)、[Command](command.md) 与 [Data](data.md)。
Contributor 不得在采集时读文件、访问网络或等待外部服务。

## 异步交付队列与错误

`RemoteDelivery` 的 `submit`/`accept` 只进入内存邮箱，调用线程不做磁盘或网络 I/O，也不会把交付、
序列化或队列故障抛回业务。`submit` 的 `CompletionStage<Boolean>` 在事件原子写入本地队列后给出 `true`；
关闭、容量淘汰、无效事件或本地写入失败给出 `false`，不会以异常完成。

默认值：16 MiB 磁盘预算、4096 条磁盘事件、24 小时 TTL、256 条内存邮箱、每批最多 100 条且正文合计
1 MiB。单事件默认 64 KiB，超限会整体拒绝而不截断。Incident 比普通日志优先；容量不足时会先淘汰最旧
日志以保留 Incident。队列保存完整原始事件正文、安装标识和环境快照，属于敏感本地数据；目录必须由插件
私有化保护，删除它会丢失未交付事件。

每次后台 settings 刷新后，交付器将本地配置与服务端下发的 `max_event_bytes`、`max_batch_events` 和
`max_decompressed_bytes` 取较小值；组批后再对**完整 batch（含安装与环境 envelope）**实际 gzip，确认未超过
`max_compressed_bytes`。服务端收紧限制后，已在队列中但超限的单个事件会整条移除并计入 `droppedEvents()`，
不会截断，也不会永久热重试；过大的候选 batch 会缩小后再尝试。

同一个队列目录绑定端点和公开 Key 的 SHA-256 身份；换端点或 Key 必须使用新目录。队列要求整条父目录链
不允许 group/other 或非 owner 写入，并要求文件系统提供稳定目录身份和可验证的 owner-only POSIX 权限
或 ACL，否则构造时 fail-closed；目录被替换或父目录权限在运行期间变得可写都会停止后续读写删除。队列
另有单进程锁和原子写入；损坏项移入受限的 `quarantine`，不会因为
声明长度分配无界内存。

失败采用带抖动的指数退避（默认 1 秒至 60 秒）。`429` 尊重 `Retry-After`（可超过上限）；`403` 视为
暂停，至少停 5 分钟再试；`401`（包括 settings 刷新）终止交付器并保留本地队列，之后提交返回 `false`。完整收据中
`accepted`、`duplicate`、`rejected` 都会从队列移除；收据不完整、无效或网络失败时保留整个批次用同一
`event_id` 重试。收据使用严格 JSON schema：重复字段、未知字段、类型错误、尾随内容、索引、计数或
`event_id` 不一致都视为协议失败并保留队列。`accepted` / `duplicate` 不得携带 `error`，`rejected`
必须携带非空 `error`。`dispose()` 不等待在途网络请求。

## 生命周期与迁移

把 `RemoteLogger`、桥接返回的 `Disposable` 和 `RemoteDelivery` 纳入插件生命周期，停服时调用
`dispose()`；`RemoteLogger.dispose()` 会停止 Contributor 线程。`RemoteClient` 没有关闭方法。

本模块没有旧诊断协议的兼容路由、兼容 shim 或导入器。旧版未校验诊断数据不会导入，也不得在 Remote
中视为可信数据。协议细节见 [Remote 协议 v1](../remote-protocol.md)。Remote 服务端实现和部署配置
不属于本公共 Java 客户端仓库。

服务端 control 面提供 Issue 四维环境分布、日志前后文及明确的 Incident/Issue 关联、Incident 三档因果
关系、安装筛选与最近 Issue、按日 rejected 趋势、公开接入 origin、写入安全闸状态、limits / build 只读
查询和本地会话恢复。日志关联只接受 Incident 日志窗口中显式携带的 `(key_id,event_id)`；Incident
`linked`、`confirmed_cause`、`nearby` 分别代表显式 operation 边、客户端显式原因声明和单纯时间接近，
不按时间或文本自动推断根因。完整路由与保留语义由对应 Remote 服务端版本的私有运维文档定义。
