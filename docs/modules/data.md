# klib-data

面向字节的异步键值存储，带事务、结构迁移和玩家数据缓存。基础模块不选存储实现；阻塞的文件或 JDBC I/O 由你显式选择的提供器放到它自己的执行器里。

| 模块名 | 制品 |
| --- | --- |
| `data` | `me.kzheart.klib:klib-data`，后端见下表 |

## 快速开始

```kotlin
klib {
    modules {
        data {
            json()
        }
    }
}
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

- `data()` 只加入存储契约、迁移和缓存，**不含** Gson 或数据库驱动。
- 生产存储必须在 `data { }` 中显式选择 `json()`、`sqlite()`、`mysql()` 或 `postgresql()`，可以同时选多个。
- 直接依赖制品时，只依赖实际使用的那个实现即可。每个实现会传递带上公共模块和自身需要的运行时。

| 模块 | 内容 | 第三方运行时 |
| --- | --- | --- |
| `klib-data` | 契约、迁移、玩家数据缓存 | 无 |
| `klib-data-json` | JSON 文件提供器 | 无；Gson 由宿主提供 |
| `klib-data-jdbc` | JDBC 会话、事务与方言引擎 | 无 |
| `klib-data-sqlite` | SQLite 提供器 | 无；SQLite JDBC 由宿主提供 |
| `klib-data-mysql` | MySQL 提供器 | MySQL Connector/J 及其传递依赖 |
| `klib-data-postgresql` | PostgreSQL 提供器 | pgJDBC 及其传递依赖 |

宿主依赖说明：

- JSON 和 SQLite 使用 Bukkit/Paper 宿主提供的 Gson 和 SQLite JDBC，**不会**复制进插件制品。
- SQLite JDBC 含多平台 Native 库，Klib 只编译和发布提供器代码，**绝不**把驱动复制进插件或 Guard 商品。
- 没有宿主的独立测试程序，要自己在运行时加入 Gson / SQLite JDBC。
- MySQL Connector/J、pgJDBC 不属于宿主能力，只在显式选择对应后端时加入。
- 使用 Gradle 插件时 PostgreSQL 驱动自动重定位；手动打包要同时处理 `org.postgresql` 类和 JDBC 服务描述文件。

最小示例：提供器和会话都是要释放的资源，交给 `Scope` 持有。生产环境用带 `KLogger` 的重载，否则数据库故障对服主完全静默。

```java
import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.StorageProvider;
import me.kzheart.klib.data.StorageSession;
import me.kzheart.klib.data.sqlite.SQLiteStorageProvider;
import me.kzheart.klib.scope.Scope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public final class PlayerNotes {
    private final CompletionStage<StorageSession> sessionStage;

    public PlayerNotes(Scope scope, Path file) {
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

| 会话方法 | 返回 |
| --- | --- |
| `get(...)` | `Optional<byte[]>` 的 `CompletionStage` |
| `put(...)`、`delete(...)`、`entries(...)` | `CompletionStage` |

命名空间隔离同一存储里的不同数据集。稳定的命名空间和键格式由业务层自己定义。

## 选择存储后端

四个生产入口实现同一个 `StorageProvider` 契约：

| 提供器 | 构造 | 适合 |
| --- | --- | --- |
| `JsonStorageProvider` | `(Path)` | 单文件、小规模数据、本地开发 |
| `me.kzheart.klib.data.sqlite.SQLiteStorageProvider` | `(Path)` / `(Path, KLogger)` | 单服插件的关系型持久化 |
| `me.kzheart.klib.data.mysql.MySqlStorageProvider` | `(jdbcUrl, username, password)` / `(..., KLogger)` | 多实例访问的远端数据库 |
| `me.kzheart.klib.data.postgresql.PostgreSqlStorageProvider` | `(jdbcUrl, username, password)` / `(..., KLogger)` | PostgreSQL 远端数据库 |

PostgreSQL：

- URL 形如 `jdbc:postgresql://host:5432/database`。
- 字节值存为 `BYTEA`；事务和迁移与其他后端共用契约。
- 账号需要创建和读写 `klib_kv`、`klib_schema` 的权限。

网络后端（MySQL、PostgreSQL）：

- 命名空间、键和结构名最长 191 个字符。
- 连接失败、事务失败都通过异步结果报告；失败的事务**不会自动重放**。
- Klib 的键值表不是外部插件的历史业务表。要兼容旧表，由插件自己的 Repository 持有 SQL，不要把旧数据直接当成 Klib 字节存储解释。

### JSON 后端

事务先写临时文件；文件系统支持时用原子移动，否则退回普通替换。

- 这只是为了避免常规写入失败留下半成品。
- **不会**额外强制把文件或目录元数据刷入物理存储，也不承诺突然断电或操作系统崩溃时的持久性。

单文件资源预算固定：

| 项 | 上限 |
| --- | --- |
| 文件大小 | 8 MiB |
| JSON 嵌套 | 16 层 |
| 命名空间 | 128 个 |
| 键值条目 | 4096 个 |
| 命名空间、结构名长度 | 128 个 UTF-8 字节 |
| 键长度 | 512 个 UTF-8 字节 |
| 单个值 | 1 MiB |
| 所有值解码后合计 | 4 MiB |
| 结构版本记录 | 256 条 |

打开超限或格式无效的已有文件、提交会让状态超出预算的事务，都以 `StorageException` 失败，并保留事务前状态。

## 接入日志

`SQLiteStorageProvider`、`MySqlStorageProvider`、`PostgreSqlStorageProvider` 和 `PlayerDataCache` 都有额外接收 `me.kzheart.klib.KLogger` 的重载。拿 logger 的方式：

| 环境 | 写法 |
| --- | --- |
| `KPlugin` 子类 | `logger()` |
| 任意 `Scope` 内 | `scope.requireCapability(KLogger.class)` |
| 不用 `KPlugin` | `new KLogger(javaPlugin.getLogger())` |

传入 logger 后，下列事件写到服务端控制台：

| 事件 | 级别 | 内容 |
| --- | --- | --- |
| 首次连接成功 | INFO | 后端类型与连接耗时 |
| 连接失败 | SEVERE | 后端类型、耗时、异常堆栈，并提示检查地址、端口、账号密码与防火墙 |
| 连接断开并自动重连 | WARNING / INFO | 触发重连与重连结果 |
| 存储操作最终失败 | SEVERE | 异常堆栈，并说明数据未写入 |
| `flushDirty()` 批次失败 | SEVERE | 本批玩家数量、异常堆栈，并说明数据保持为脏、下次刷新会重试 |
| 关闭时仍有数据未落盘 | WARNING / SEVERE | 未写入存储的玩家数量与原因 |

不传 logger 的旧构造器行为不变：完全静默，异常照旧通过返回的 `CompletionStage` 传播。

## 连接定位与超时

JDBC 提供器是**单连接 + 单个串行执行线程**：

- 一次 `StorageProvider.open()` 打开一条数据库连接，全部读写在同一条 `klib-storage` 线程上排队。
- 好处是并发写入不会互相干扰；代价是存储吞吐等于单条连接的吞吐。
- 适合单服插件的玩家数据、商店、任务等常规规模。高并发批量分析、跨插件共享的数据库压力，要在业务侧控制写入频率，或改用外部连接池方案。
- 模块目前**不内置连接池**。

连接超时：

| 后端 | 默认 | 说明 |
| --- | --- | --- |
| MySQL | URL 自动补 `connectTimeout=10000`（10 秒） | 避免防火墙直接丢包时挂到操作系统 TCP 超时（可达 130 秒）且没有任何输出 |
| PostgreSQL | 10 秒 | 显式 `connectTimeout` 参数以**秒**为单位 |
| SQLite | 无 | 本地文件访问，不涉及网络超时 |

- URL 里已显式写了 `connectTimeout` 时，保留调用方的设置。
- 不使用 `DriverManager.setLoginTimeout(...)`：它是整个 JVM 的全局开关，会连带影响同一服务端里其他插件的 JDBC 连接。

连接失败抛出的 `StorageException` 消息带后端类型和实际耗时，用来区分：

- 账号密码错误：毫秒级失败；
- 地址或防火墙不通：数秒后超时。

## 在事务中组合读写

需要「检查后再写入」时用 `StorageSession.transaction(...)`，**不要**把多个异步调用拼成非原子流程：

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

- 回调接收同步的 `TransactionContext`，操作由提供器安排在存储执行器中。
- 回调要短小、确定：**不要**在里面等待另一个 `CompletionStage`，也不要访问 Bukkit 世界或玩家对象。

把结果反馈给玩家时，必须显式切回主线程：

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

`Schema` 和 `MigrationRunner` 用连续版本描述存储迁移。每一项迁移的「读取当前版本、执行迁移、更新版本」在同一事务中：

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

- 版本号必须唯一。
- 等迁移完成再开始业务读写，不要让新旧结构同时被业务代码访问。

## 玩家数据缓存

`PlayerDataCache<T>` 建在存储仓库之上，提供单飞加载、脏版本跟踪、分批刷新和退出保存。常见组合是 `StorageSession` + `DataCodec<T>` + `KeyValuePlayerDataRepository<T>`：

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

等 `cacheStage` 完成后再注册依赖缓存的业务入口。

| 时机 | 调用 |
| --- | --- |
| 玩家登录 | `login(uuid)` |
| 业务修改 | `modify(uuid, old -> replacement)` |
| 玩家退出 | `quit(uuid)` |
| 周期保存 | `flushDirty()` |
| 只读已加载的值 | `findLoaded(...)`，只返回已加载且未退出的玩家 |

`T` 最好是不可变对象，修改函数返回新值。未加载时的 `UnloadedPolicy`：

| 策略 | 行为 |
| --- | --- |
| `FAIL_FAST` | 拒绝修改 |
| `LOAD_ASYNC` | 先加载已有值再修改 |
| `CREATE_DEFAULT` | 只在仓库确实返回空值时创建默认值，不会静默覆盖已存数据 |

## Remote 排障快照

<details>
<summary>展开：存储 provider 的诊断快照包含什么、怎么接入 Remote</summary>

内置 JSON 和 JDBC（SQLite、MySQL、PostgreSQL）provider 都提供轻量 `DiagnosticSource`：

- 快照只报告后端类型、当前会话数、生命周期状态。
- JSON provider 额外报告数据文件名、是否已加载、工作线程是否存在。
- **不会**读取数据文件、发起连接，也不暴露 JDBC URL、用户名、密码、namespace、key 和 value。

选择 `remote` 模块后，可以把 provider 显式包装成 `KlibDiagnosticContributor` 加入 Incident。Data 模块不依赖 Remote，也不会自动上传存储内容。完整边界见 [Remote](remote.md#排障上下文与-contributor)。

</details>

## 线程与生命周期

- 所有存储操作都是异步的，返回 JDK `CompletionStage`。
- `thenApply`、`thenAccept`、`thenCompose` 等非 `Async` 回调，以及事务回调，都运行在存储线程，**不会自动切回主线程**，不能在里面直接调用要求主线程的 Bukkit API。
- 向玩家反馈时，用 `Scope.sync(Runnable)`、`Scope.syncExecutor()` 或 `AsyncTasks.thenSync(stage, scope, action)` 显式切回主线程，详见 [Core 的桥接 CompletionStage](core.md#桥接-completionstage)。旧写法 `Scope.after(Ticks.of(0), ...)` 语义等价，新代码用 `sync`。
- 把 provider、成功打开的 session 和 `PlayerDataCache` 安装进同一个业务 `Scope`。关闭作用域时，缓存先排空脏数据，随后会话和提供器按逆序释放。
- `PlayerDataCache.dispose()` 最多阻塞等待 30 秒。超时或保存失败时，如果传了 `KLogger`，会记录仍未落盘的玩家数量。

## 常见坑

- 不传 `KLogger` 的构造器完全静默，生产环境数据库故障服主看不到。
- 停服阶段**不要**再提交新的写入，也不要用忽略返回阶段的「即发即忘」保存。
- MySQL URL、用户名和密码来自配置或环境，**不要**写进源码或日志。
- 多步异步调用拼起来的「检查后写入」不是原子的，用 `transaction(...)`。
- JDBC 后端只有一条连接，吞吐有上限；没有连接池。
- JSON 后端有固定预算（见上表），超出时整个事务失败。

## 相关页面

- [Core](core.md)：作用域、`AsyncTasks` 与主线程桥接
- [Remote](remote.md)：把存储诊断附加到 Incident
- [构建与打包](../../README.md)：`data { }` 后端选择与打包
