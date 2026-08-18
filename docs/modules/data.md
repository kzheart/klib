# klib-data

`klib-data` 提供面向字节的异步键值存储、事务、结构迁移和玩家数据缓存。它把阻塞文件或 JDBC I/O 放到存储提供器自己的执行器中，适合插件配置以外的持久业务数据。

## 接入模块

使用 klib Gradle 插件：

```kotlin
klib {
    modules {
        data()
    }
}
```

该方式会自动加入 klib 依赖，并把 Gson、SQLite JDBC 和 MySQL Connector/J 等运行时依赖放入最终产物。

直接依赖时可按实际后端选择驱动：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-data:<klib-version>")
    runtimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
    runtimeOnly("com.mysql:mysql-connector-j:8.4.0")
}
```

只使用 JSON 后端时不需要 JDBC 驱动。直接依赖的调用方负责打包 `klib-data`、Gson 以及所选数据库驱动。

## 选择存储后端

三个生产入口实现同一个 `StorageProvider` 契约：

- `JsonStorageProvider(Path)`：单文件、小规模数据和本地开发；事务先写临时文件，文件系统支持时使用原子移动，否则退回普通替换。该流程用于避免常规写入失败留下半成品，不会额外强制文件或目录元数据刷入物理存储，也不承诺突然断电或操作系统崩溃时的持久性。
- `SQLiteStorageProvider(Path)` / `SQLiteStorageProvider(Path, KLogger)`：单服插件的关系型持久化。
- `MySqlStorageProvider(jdbcUrl, username, password)` / `MySqlStorageProvider(jdbcUrl, username, password, KLogger)`：多实例访问的远端数据库。

JSON 后端为单文件本地存储设置固定资源预算：文件最多 8 MiB、JSON 嵌套最多 16 层、最多 128 个
命名空间和 4096 个键值条目；命名空间及结构名最多 128 个 UTF-8 字节，键最多 512 个 UTF-8
字节，单值最多 1 MiB、所有值解码后合计最多 4 MiB，结构版本记录最多 256 条。打开超限或格式无效的
已有文件，以及提交会使状态超过预算的事务，都会以 `StorageException` 失败并保留事务前状态。

提供器和会话都是需要释放的资源，应交给 `Scope` 持有。生产环境请使用带 `KLogger` 的重载，否则数据库故障对服主完全静默：

```java
import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.StorageProvider;
import me.kzheart.klib.data.StorageSession;
import me.kzheart.klib.data.sql.SQLiteStorageProvider;
import me.kzheart.klib.scope.Scope;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

public final class PlayerNotes {
    private final CompletionStage<StorageSession> sessionStage;

    public PlayerNotes(Scope scope, java.nio.file.Path file) {
        KLogger logger = scope.requireCapability(KLogger.class);
        StorageProvider provider = scope.install(new SQLiteStorageProvider(file, logger));
        sessionStage = provider.open().thenApply(scope::install);
    }

    public CompletionStage<Void> save(String playerId, String note) {
        byte[] value = note.getBytes(StandardCharsets.UTF_8);
        return sessionStage.thenCompose(opened ->
                opened.put("player-notes", playerId, value));
    }
}
```

`get(...)` 返回 `Optional<byte[]>`，`put(...)`、`delete(...)` 和 `entries(...)` 都返回 `CompletionStage`。命名空间用于隔离同一存储中的不同数据集；业务层应自己定义稳定的命名空间和键格式。

## 接入日志

`SQLiteStorageProvider`、`MySqlStorageProvider` 和 `PlayerDataCache` 都提供一个额外接收 `me.kzheart.klib.KLogger` 的重载。`KPlugin` 子类可以直接用 `logger()`，任意 `Scope` 内可用 `scope.requireCapability(KLogger.class)`，独立于 `KPlugin` 使用时可以用 `new KLogger(javaPlugin.getLogger())` 包装。

传入 logger 后，下列事件会写到服务端控制台：

| 事件 | 级别 | 内容 |
| --- | --- | --- |
| 首次连接成功 | INFO | 后端类型与连接耗时 |
| 连接失败 | SEVERE | 后端类型、耗时、异常堆栈，并提示检查地址、端口、账号密码与防火墙 |
| 连接断开并自动重连 | WARNING / INFO | 触发重连与重连结果 |
| 存储操作最终失败 | SEVERE | 异常堆栈，并说明数据未写入 |
| `flushDirty()` 批次失败 | SEVERE | 本批玩家数量、异常堆栈，并说明数据保持为脏、下次刷新会重试 |
| 关闭时仍有数据未落盘 | WARNING / SEVERE | 未写入存储的玩家数量与原因 |

不传 logger 的旧构造器行为不变，仍然完全静默；异常照旧通过返回的 `CompletionStage` 传播。

## 连接定位与超时

JDBC 提供器使用**单连接 + 单个串行执行线程**：一个 `StorageProvider.open()` 打开一条数据库连接，全部读写在同一条 `klib-storage` 线程上排队执行。这样可以避免并发写入互相干扰，但也意味着存储吞吐等于单条连接的吞吐。它适合单服插件的玩家数据、商店、任务等常规规模；如果需要高并发批量分析或跨插件共享的数据库压力，应在业务侧自行控制写入频率，或改用外部连接池方案。模块目前不内置连接池。

MySQL 连接会在 JDBC URL 上自动补一个 `connectTimeout=10000`（10 秒），避免防火墙直接丢包时挂到操作系统的 TCP 超时（可达 130 秒）而没有任何输出。URL 中已显式写明 `connectTimeout` 时保持调用方的设置。这里不使用 `DriverManager.setLoginTimeout(...)`，因为它是整个 JVM 的全局开关，会连带影响同一服务端里其他插件的 JDBC 连接。SQLite 是本地文件访问，不涉及网络超时。

连接失败时抛出的 `StorageException` 消息中包含后端类型和实际耗时，便于区分“账号密码错误”（毫秒级失败）与“地址或防火墙不通”（数秒后超时）。

## 在事务中组合读写

需要“检查后再写入”时，应使用 `StorageSession.transaction(...)`，不要把多个异步调用拼成一个非原子流程：

```java
CompletionStage<Boolean> claimed = sessionStage.thenCompose(opened ->
        opened.transaction(context -> {
            if (context.get("rewards", rewardId).isPresent()) {
                return Boolean.FALSE;
            }
            context.put("rewards", rewardId, payload);
            return Boolean.TRUE;
        }));
```

事务回调接收同步的 `TransactionContext`，其操作由提供器安排在存储执行器中。回调应短小、确定，不要在其中等待另一个 `CompletionStage`，也不要访问 Bukkit 世界或玩家对象。

要把结果反馈给玩家，必须显式切回主线程：

```java
import me.kzheart.klib.scheduler.AsyncTasks;

AsyncTasks.thenSync(claimed, scope,
        granted -> {
            // 主线程：可以安全访问玩家
            player.sendMessage(granted ? "奖励已领取" : "奖励已领取过");
        },
        error -> logger.error("领取奖励失败", error));
```

## 管理结构版本

`Schema` 和 `MigrationRunner` 用连续版本描述存储迁移。每一项迁移的“读取当前版本、执行迁移、更新版本”位于同一事务中：

```java
import me.kzheart.klib.data.Migration;
import me.kzheart.klib.data.MigrationRunner;
import me.kzheart.klib.data.Schema;

import java.util.Arrays;

Schema schema = new Schema("shop", Arrays.asList(
        new Migration(1, context -> {
            context.put(
                    "shop-settings",
                    "currency",
                    "money".getBytes(StandardCharsets.UTF_8));
        }),
        new Migration(2, context -> {
            context.put(
                    "shop-settings",
                    "format",
                    "0.00".getBytes(StandardCharsets.UTF_8));
        })
));

CompletionStage<Integer> version = sessionStage.thenCompose(opened ->
        MigrationRunner.apply(opened, schema));
```

版本号必须唯一。启动业务读写前等待迁移完成；不要让新旧结构同时被业务代码访问。

## Remote 排障快照

内置 JSON、SQLite 和 MySQL provider 提供轻量 `DiagnosticSource`。快照只报告后端类型、当前会话数、
生命周期状态；JSON provider 额外报告数据文件名、是否已加载和工作线程是否存在。它不会读取数据文件、
发起连接或暴露 JDBC URL、用户名、密码、namespace、key 和 value。

选择 `remote` 模块后，可将 provider 显式包装为 `KlibDiagnosticContributor` 加入 Incident。Data 模块不
依赖 Remote，也不会自动上传存储内容。完整边界见 [Remote](remote.md#排障上下文与-contributor)。

## 玩家数据缓存

`PlayerDataCache<T>` 在存储仓库之上提供单飞加载、脏版本跟踪、分批刷新和退出保存。常见组合是 `StorageSession`、`DataCodec<T>` 和 `KeyValuePlayerDataRepository<T>`：

```java
CompletionStage<PlayerDataCache<PlayerProfile>> cacheStage =
        sessionStage.thenApply(opened -> {
            DataCodec<PlayerProfile> codec = new PlayerProfileCodec();
            PlayerDataRepository<PlayerProfile> repository =
                    new KeyValuePlayerDataRepository<PlayerProfile>(
                            opened,
                            "player-profile",
                            codec,
                            PlayerProfile::empty);

            return scope.install(new PlayerDataCache<PlayerProfile>(
                    repository,
                    PlayerProfile::empty,
                    UnloadedPolicy.LOAD_ASYNC,
                    50,
                    logger));
        });
```

等待 `cacheStage` 完成后再注册依赖缓存的业务入口。登录时调用 `login(uuid)`，业务修改使用 `modify(uuid, old -> replacement)`，玩家退出调用 `quit(uuid)`，周期保存调用 `flushDirty()`。`findLoaded(...)` 只读取已经加载且未退出的值。

缓存中的 `T` 最好是不可变对象，让修改函数返回新值。`FAIL_FAST` 在未加载时拒绝修改；`LOAD_ASYNC` 会先加载已有值；`CREATE_DEFAULT` 只在仓库确实返回空值时创建默认值，同样不会静默覆盖已存数据。

## 生命周期与线程边界

- 所有存储操作都是异步的。存储方法返回 JDK `CompletionStage`，其 `thenApply`、`thenAccept`、`thenCompose` 等非 `Async` 回调以及事务回调运行在存储线程，**不会自动切回主线程**，不能在其中直接调用要求主线程的 Bukkit API。
- 需要向玩家反馈时，用 `Scope.sync(Runnable)`、`Scope.syncExecutor()` 或 `AsyncTasks.thenSync(stage, scope, action)` 显式切回主线程，详见 [Core 的桥接 CompletionStage](core.md#桥接-completionstage)。旧写法 `Scope.after(Ticks.of(0), ...)` 语义等价，新代码请使用 `sync`。
- 把 provider、成功打开的 session 和 `PlayerDataCache` 安装进同一业务 `Scope`。关闭作用域时缓存会排空脏数据，随后会话和提供器按逆序释放。
- `PlayerDataCache.dispose()` 最多阻塞等待 30 秒。不要在停服阶段再提交新的写入，也不要用忽略返回阶段的“即发即忘”保存。超时或保存失败时，若传入了 `KLogger`，会记录仍未落盘的玩家数量。
- MySQL URL、用户名和密码应来自配置或环境，不要写进源码或日志。

JSON 存储的异步接线方式见本页“快速开始”和“线程边界”。
