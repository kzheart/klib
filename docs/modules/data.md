# klib-data

异步键值存储、玩家缓存，以及可选 JDBC SQL 执行边界。KV 与关系型 SQL 分别使用自己的事务和迁移入口；阻塞 I/O 在明确的存储执行器上运行。

| 模块名 | 制品 |
| --- | --- |
| `data` | `me.kzheart.klib:klib-data`，后端见下表 |

## 快速开始

```kotlin
klib { modules { data { json() } } }
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

- `data()` 只加入存储契约、迁移和缓存，**不含** Gson 或数据库驱动。
- 生产存储必须在 `data { }` 中显式选择 `json()`、`sqlite()`、`mysql()` 或 `postgresql()`，可以同时选多个。
- 直接依赖制品时，只依赖实际使用的那个实现即可。每个实现会传递带上公共模块和自身需要的运行时。

各模块均使用内置 Kotlin 运行时，`data` 还通过 Core 提供协程；下表只列额外的存储后端依赖。

| 模块 | 内容 | 额外后端运行时 |
| --- | --- | --- |
| `klib-data` | 字节与类型化 KV、迁移、玩家数据缓存 | 无 |
| `klib-data-json` | JSON 文件提供器 | 无；Gson 由宿主提供 |
| `klib-data-jdbc` | KV JDBC 会话、独立 SQL 执行器、显式行映射和外部迁移门禁 | 无 |
| `klib-data-pool` | 可选 DBCP 连接池薄适配 | Apache Commons DBCP 及其传递依赖 |
| `klib-data-sqlite` | SQLite 提供器 | 无；SQLite JDBC 由宿主提供 |
| `klib-data-mysql` | MySQL 提供器 | MySQL Connector/J 及其传递依赖 |
| `klib-data-postgresql` | PostgreSQL 提供器与专用 LISTEN 会话 | pgJDBC 及其传递依赖 |

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

### 类型化 KV

`TypedKeyValueStore<T>` 直接复用现有 `StorageSession` 和 `me.kzheart.klib.data.cache.DataCodec<T>`，不创建另一份存储或事务引擎。
Java 使用 `new TypedKeyValueStore<>(session, namespace, codec)`；Kotlin 使用扩展入口：

```kotlin
val profiles = session.typed("profiles", profileCodec)
profiles.putAwait(playerId, profile)
val found: Profile? = profiles.getAwait(playerId)
profiles.transaction {
    val current = get(playerId).orElse(null)
    if (current != null) put(playerId, current.copy(level = current.level + 1))
}
```

- Java 的 `get` 返回 `CompletionStage<Optional<T>>`，`put/delete/entries/transactionAsync` 同样返回阶段；Kotlin 通过 `getAwait/putAwait/deleteAwait/entriesAwait/transaction` 等待同一操作。
- empty/null 只表示键不存在，codec 不得编码或解码 null；codec 错误不会伪装成缺失值。编码在提交前完成并复制字节，编码异常可同步抛出。
- `TypedKeyValueView` 仅在同一 KV 事务回调及线程内有效，退出回调后读取失败。事务块不可挂起；它不会与下面的 SQL 事务组成跨存储原子性。

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

## 外部 JDBC 数据源

MySQL、PostgreSQL 提供器支持 `(DataSource, KLogger)` 构造。数据源由调用方持有，关闭顺序为会话、提供器、数据源；提供器只关闭自己打开的连接。

`JdbcDataSources.driver(driverClass, pluginClassLoader, jdbcUrl, properties)` 创建不带池的数据源，直接绑定指定类加载器的驱动，避免 `DriverManager` 选到其他插件的驱动。驱动延迟到首次连接加载，连接属性按副本传递。

可注入 HikariCP 等外部连接池；现有会话仍按单个线程串行执行，并不会因数据源带池而自动并行。阻塞的获取连接操作仍由存储执行器执行。

外置驱动的构建与启动依赖见 [构建与打包](https://github.com/kzheart/klib/wiki/Build)。经 PgBouncer 事务池访问时，不使用跨事务会话状态；`LISTEN` 应使用独立直连或会话池连接。

## 关系型 SQL 与显式对象映射

`JdbcExecutor` 独立于 `StorageSession`，适合保留现有业务表、JOIN、数据库约束与批量语句。选择任一 JDBC 后端会带上该模块；直接引用时使用 `klib-data-jdbc`。基础模块不创建池，也不自动推导实体、关系或 SQL 方言。

### 受管数据库与薄挂起入口

`Databases.open(scope, config)` 在现有有界 JDBC 执行器上完成打开、迁移门禁和连接探测，成功后才交付 `Database`。
Java 得到 `CompletionStage<Database>`；Kotlin 使用 `Databases.openAwait`，不另起一套 SQL 执行引擎。

```kotlin
import me.kzheart.klib.data.jdbc.*

val config = DatabaseConfig.driver(driverClass, pluginClassLoader, jdbcUrl, properties)
    .workers(2).queueCapacity(128)
    .statementTimeout(Duration.ofSeconds(30))
    .closeTimeout(Duration.ofSeconds(30))
    .build()
val db = Databases.openAwait(scope, config)
val profile = db.read {
    queryOne("SELECT name, joined_at, note FROM player_profile WHERE player_id = ?", playerId) {
        Profile(it.string("name"), it.instant("joined_at"), it.stringOrNull("note"))
    }
}
db.transaction {
    execute("UPDATE player_profile SET note = ? WHERE player_id = ?", note, playerId)
    execute("INSERT INTO profile_audit(player_id) VALUES (?)", playerId)
}
```

`Duration` 是 `java.time.Duration`，其余连接设置、`Profile` 和业务变量由调用方提供。`db.read` 只是自动提交工作块的便利名称，仍可执行写入，**不提供权限隔离或多语句事务**。
需要同一连接原子提交时使用 `db.transaction`；其块是普通不可挂起函数，外层挂起等待结果。

```java
CompletionStage<Optional<Profile>> profile = db.readAsync(sql ->
        sql.queryOne("SELECT name, joined_at FROM player_profile WHERE player_id = ?",
                SqlArguments.of(playerId), SqlRows.mapper(row ->
                        new Profile(row.string("name"), row.instant("joined_at")))));
CompletionStage<Integer> changed = db.transactionAsync(sql ->
        sql.update("UPDATE player_profile SET note = ? WHERE player_id = ?",
                SqlArguments.of(note, playerId)));
```

| 入口 | 行为 |
| --- | --- |
| Kotlin `query` / `queryOne` / `execute` | 显式行映射返回列表／零行 null 多行失败／受影响行数 |
| `SqlRows.mapper(SqlRowMapper)` | Java 的类型化行映射适配，继续调用现有 `SqlClient` |
| `SqlRow.string/int/long/boolean/decimal/bytes/uuid/instant` | 必填读取，SQL NULL 报错；对应 `*OrNull` 显式返回可空值 |
| Java `SqlRow.intValue/longValue/booleanValue` | 避免 Java 关键字的方法名；Kotlin 仍用 `int/long/boolean` |
| `SqlArguments.of(...)` | 集中绑定标量、UUID、Instant、BigDecimal、ByteArray、null |
| `SqlValue.nullOf(sqlType)` / `typed(value, sqlType)` | NULL 无法推导或需要显式 JDBC 类型时使用 |

- `SqlRow` 只在当前 mapper 回调及线程内有效，不能保存它、ResultSet 或连接到外部。结果对象由业务显式构造，不做 ORM 或实体反射。
- Instant 按 UTC 与 JDBC Timestamp 转换；无时区列也按 UTC 解释。UUID 在 PostgreSQL 绑定原生类型，在文本存储后端绑定标准字符串。
- 不支持的参数类型直接拒绝，使用 `SqlValue.typed` 或已有 `SqlParameters` 明确绑定。SQL 只使用 PreparedStatement；表名和排序等标识符仍来自业务白名单。
- 沿用 `JdbcTransactionException` 四态、拒绝同一执行器嵌套提交、不自动重试写入。取消等待不等于已开始的事务回滚；事务只读提示使用 `SqlTransactionOptions.withReadOnly(true)`。

### 配置、池与资源归属

`DatabaseConfig` 的默认值为 2 个 worker、128 个排队工作、30 秒语句超时和30秒同步关闭等待；Builder 校验正数，均可由业务配置传入。
`driver` 提供可选连接超时参数，默认 10 秒，对 PostgreSQL/MySQL URL 应用已有驱动适配；URL 中显式超时优先。未知驱动和外部数据源的连接／网络超时仍由对应驱动或池配置。

- `DatabaseConfig.external(dataSource)` 只归还自己借到的连接，**不关闭调用方的池**。
- `DatabaseConfig.builder(JdbcSourceFactory)` 在执行器线程创建源；用 `JdbcSource.owned(source, cleanup)` 交付关闭权，或者 `JdbcSource.external(source)` 保留调用方所有权。工厂失败前应自行清理尚未交付的资源。
- `Databases.open` 在启动工作前把资源登记到 Scope，打开失败、取消及晚到成功都进入同一关闭流程；不在完成线程重新安装 Scope，避免与关闭排空互等。
- `Database.closeAsync()` 停止接收、排空已接受工作后关闭自有源。重复关闭共享终态，取消返回的关闭观察不会取消真实排空。
- `dispose/close` 最多等待配置时限。超时明确失败，Scope 标记关闭也不代表数据库已经排空；应继续观察 `closeAsync()`，不能提前关闭外部池。SQL 回调内不能阻塞等待自身关闭。
- `statementTimeout` 只设置 `SqlClient` 创建的语句；原始 JDBC、外部迁移引擎和驱动连接超时仍由各自配置。日志只报告生命周期与失败状态，原始错误通过阶段传播，不把 URL、凭据或 SQL 参数写入日志。

可选池制品是 `me.kzheart.klib:klib-data-pool:<klib-version>`，也可直接作为额外内嵌根声明；Gradle 插件可通过 `modules { data { pool() } }` 选择并隔离它及 DBCP2、commons-pool2、commons-logging。
该制品仅薄适配 Apache Commons DBCP，不由其他 KV 后端自动引入：

```kotlin
import me.kzheart.klib.data.pool.JdbcPools
import me.kzheart.klib.data.pool.JdbcPoolOptions

val direct = JdbcDataSources.driver(driverClass, pluginClassLoader, jdbcUrl, properties)
val config = DatabaseConfig.builder(JdbcPools.dbcp(direct,
    JdbcPoolOptions(4, Duration.ofSeconds(10), Duration.ofSeconds(5)))).build()
val db = Databases.openAwait(scope, config)
```

池配置依次是最大连接数、借连接等待上限、连接验证超时；池会归还并验证连接，不更改底层 DataSource 的所有权。
自有池仅在 SQL 工作排空后关闭；连接建立及网络 I/O 仍须设置驱动超时。

### 直接使用 JDBC 执行器

```java
import me.kzheart.klib.data.jdbc.JdbcExecutor;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

JdbcExecutor sql = scope.install(new JdbcExecutor(dataSource, 2, 128));
CompletionStage<Integer> renamed = sql.transaction(client ->
        client.update("UPDATE player_profile SET display_name = ? WHERE player_id = ?", statement -> {
            statement.setString(1, newName);
            statement.setString(2, playerId);
        }));
CompletionStage<Optional<String>> name = sql.work(client ->
        client.queryOne("SELECT display_name FROM player_profile WHERE player_id = ?",
                statement -> statement.setString(1, playerId),
                row -> row.getString("display_name")));
```

| 入口 | 保证和边界 |
| --- | --- |
| `work(...)` | 每次借还一条自动提交连接；多条写入不组成事务 |
| `transaction(...)` | 同一连接上执行回调、commit 或 rollback；不自动重试 |
| `SqlClient.update(...)` | PreparedStatement 参数绑定，返回受影响行数 |
| `query(...)` / `queryOne(...)` | 显式 `RowMapper<T>`，自动关闭语句与结果集；后者零行返回 empty，多于一行失败 |
| `connection()` | 同一连接交给成熟 mapper 或 JDBC batch、generated keys、savepoint；自己关闭所建语句与结果集 |

- SQL 参数使用 JDBC 一基索引绑定。动态表名、排序字段必须来自业务白名单，不能把用户输入拼进 SQL。
- `RowMapper` 必须返回非 null 对象。字段的 SQL NULL 由业务显式处理，例如 `getString()` 的 null、`getInt()` 后的 `wasNull()`，不能把缺失字段默认为 0。
- `query` 收集全部结果，请在 SQL 中分页或限量。大结果在回调内通过 `connection()` 流式消费；不返回裸 `Stream`、`ResultSet`、statement 或连接。
- `SqlClient` 只在回调所在的线程、回调期间有效。原始 JDBC 对象也遵守该约定；不得关闭连接、改变 autoCommit、手动 commit 或保留到回调外。

### SQL 事务结果与连接状态

默认保留数据源的隔离级别和只读设置。需要时显式传入 `SqlTransactionOptions.defaults().withIsolation(Connection.TRANSACTION_REPEATABLE_READ)`；`withReadOnly(true)` 只是 JDBC 提示，驱动不支持时失败，不提供权限隔离。

事务结束后复原 autoCommit、已修改的隔离级别和只读状态，再归还连接。数据源必须借出 autoCommit 为 true 的独占连接；已经在事务中的连接会被拒绝。

`JdbcTransactionException.outcome()` 区分：

| 结果 | 意义 |
| --- | --- |
| `NOT_STARTED` | 没有开始事务，例如借连接失败 |
| `ROLLED_BACK` | 回调失败，rollback 已成功返回 |
| `COMMITTED` | commit 已成功返回，之后连接复原或关闭失败 |
| `UNKNOWN` | commit 抛错或 rollback 失败，不能确认业务写入结果 |

原始错误保留为 cause，rollback、复原和关闭错误保留为 suppressed。rollback 失败后不会调用可能提交残留写入的 `setAutoCommit(true)`，而是尝试 abort 并关闭连接。`UNKNOWN` 与 `COMMITTED` 都不能当作「未写入」自动重放。

这些保证依赖数据库支持事务。MySQL 等数据库的隐式 DDL 提交、非事务表、网络回包丢失和数据库外副作用不由 JDBC 包装层消除。

SQL 与 KV 的事务彼此独立。把 `StorageSession` 的 future 放进 SQL 回调、拼接两个 future，或在回调中发奖励，都不产生跨存储原子性。业务关联写入放进同一 SQL 回调并复用其 `SqlClient`；禁止从回调嵌套提交同一个 `JdbcExecutor`。

### SQL 启动迁移门禁

有业务 schema 迁移时，用 `JdbcExecutor.open(dataSource, workers, queueCapacity, engine, targetVersion)`，只在返回成功后注册业务入口。已有迁移工具通过 `SqlMigrationEngine` 接入；版本为非负整数，未初始化为 0。

```java
import me.kzheart.klib.data.jdbc.SqlMigrationEngine;
import javax.sql.DataSource;

SqlMigrationEngine engine = new SqlMigrationEngine() {
    public int currentVersion(DataSource source) throws Exception {
        return existingMigrator.currentVersion(source);
    }
    public void migrate(DataSource source, int targetVersion) throws Exception {
        existingMigrator.upgradeTo(source, targetVersion);
    }
};
CompletionStage<JdbcExecutor> ready = JdbcExecutor.open(dataSource, 2, 128, engine, 3)
        .thenApply(scope::install);
```

这里的 `existingMigrator` 是业务已有迁移工具的适配入口，不是 Klib 内置实现。Java 8 应选符合自身运行基线的工具版本；Klib 不传递引入 Flyway、Liquibase 或 ORM。

| 责任 | 归属 |
| --- | --- |
| 启动前拒绝负版本、数据库版本高于目标 | Klib 门禁 |
| 迁移返回后再次读取版本，必须精确等于目标才开放 executor | Klib 门禁 |
| 版本历史、checksum、脚本解析、迁移锁、锁内再次核验、失败恢复 | 外部迁移引擎 |
| 非整数版本映射、数据库 DDL 能力与较新 schema 兼容策略 | 业务适配器与所选引擎 |

已有版本等于目标时不会再次调用 `migrate`。读取或迁移失败、结果版本不匹配时，Klib 关闭新建执行器并让打开结果失败；不会自动修复历史、重建表或宣称外部 DDL 已回滚。门禁外的版本检查不是跨实例锁，迁移引擎必须在锁内重新检查。

受管入口在 `DatabaseConfig.Builder.migrations(engine, targetVersion)` 中指定同一个 `SqlMigrationEngine`；与 `JdbcExecutor.open` 共用门禁实现。
成熟引擎适配器仍自行负责历史、checksum、锁、目标版本映射和超时，Klib 不复制迁移引擎。

先在旧数据副本验证迁移与失败重跑。迁移工具的 Java/数据库版本兼容性、跨进程锁和 DDL 恢复，需要适配器自己的测试验证。

### SQL 排队、关闭与 Kotlin

并行度和等待队列容量都必须为正数。队列满、关闭后提交通过失败的 `CompletionStage` 返回 `RejectedExecutionException`，不会同步漏出拒绝错误；SQLite 写入通常从单 worker 开始。

`closeAsync()` 停止接收新工作，排空已接受的工作并归还连接后完成。`dispose()` / `close()` 最多阻塞等待 30 秒；安装进 `Scope` 后，Scope 关闭线程也可能因此阻塞。正常业务优先提前 `closeAsync()` 排空，插件停用主线程不能把它当作无阻塞关闭。超时不表示已取消 JDBC 工作；等排空完成再关闭调用方持有的数据源或池。

取消尚未开始的 future 会跳过其回调；取消已开始的 future 只停止等待，不中断事务、不保证回滚。实际 JDBC 超时仍需配置驱动、数据源或 statement；队列有界不能解决数据库永不返回的问题。

Kotlin 直接调用与 Java 共用的 SQL 执行器，通过内置协程的 `await()` 挂起等待：

```kotlin
import me.kzheart.klib.data.jdbc.JdbcExecutor
import kotlinx.coroutines.future.await

data class Profile(val id: Int, val note: String?)

suspend fun load(sql: JdbcExecutor, id: Int): Profile? =
    sql.work { client ->
        client.queryOne("SELECT id, note FROM profile WHERE id = ?",
            { statement -> statement.setInt(1, id) },
            { row -> Profile(row.getInt("id"), row.getString("note")) })
    }.await().orElse(null)
```

`work` / `transaction` 回调仍是不可挂起函数；不要在里面 `delay`、`await` 或 `join`。Java 与 Kotlin 的连接、事务、异常和关闭语义相同。

## 连接定位与超时

KV 的 JDBC 提供器是**单连接 + 单个串行执行线程**；上面的 `JdbcExecutor` 则每次工作借还连接：

- 一次 `StorageProvider.open()` 打开一条数据库连接，全部读写在同一条 `klib-storage` 线程上排队。
- 提供器关闭后才建立的连接不会发布：`open()` 等物理连接关闭结束后才失败，关闭异常通过该阶段传播。已发布会话仍先排空已接受事务，事务回调内释放不会提前关闭当前连接或等待自身。
- 好处是并发写入不会互相干扰；代价是存储吞吐等于单条连接的吞吐。
- 适合单服插件的玩家数据、商店、任务等常规规模。高并发批量分析、跨插件共享的数据库压力，要在业务侧控制写入频率，或改用外部连接池方案。
- KV 后端不自动引入池；需要池的独立 SQL 工作显式选择 `klib-data-pool`，见上面的受管数据库入口。

连接超时：

| 后端 | 默认 | 说明 |
| --- | --- | --- |
| MySQL | URL 自动补 `connectTimeout=10000`（10 秒） | 避免防火墙直接丢包时挂到操作系统 TCP 超时（可达 130 秒）且没有任何输出 |
| PostgreSQL | 10 秒 | 显式 `connectTimeout` 参数以**秒**为单位 |
| SQLite | 无 | 本地文件访问，不涉及网络超时 |

- URL 里已显式写了 `connectTimeout` 时，保留调用方的设置。
- 不使用 `DriverManager.setLoginTimeout(...)`：它是整个 JVM 的全局开关，会连带影响同一服务端里其他插件的 JDBC 连接。

## PostgreSQL LISTEN 会话

`PostgreSqlNotifications.listen(jdbcUrl, properties, channels, options, listener)` 使用独立物理连接，不借业务池；业务事务池与 LISTEN 直连地址分别配置。
会话实现 `Disposable`，可以交给 Scope；不内置 Flow，也不执行领域写入。

```java
PostgreSqlListenSession notifications = scope.install(PostgreSqlNotifications.listen(
        listenUrl, properties, Collections.singleton("profile_changes"),
        PostgreSqlListenOptions.defaults(), new PostgreSqlNotificationListener() {
            public void onNotification(PostgreSqlNotification event) {
                catalogChanges.invalidate(event.payload());
            }
            public void onListening(long generation) {
                catalogChanges.invalidateAll(); // 首次订阅及重订阅后重新读取数据库
            }
        }));
CompletionStage<Void> ready = notifications.ready();
```

Kotlin 使用相同 SAM／object 回调；`notifications.ready().await()` 等待订阅，`notifications.closeAsync().await()` 等待排空。
回调运行在专用串行线程，不能调用 Bukkit 或等待自己的关闭；回调只发布失效信号，外部协程和业务异步工作仍归业务 Scope。

| 入口或选项 | 行为或默认值 |
| --- | --- |
| `ready()` | 全部 LISTEN 在自动提交连接上订阅成功后完成 |
| `onListening(generation)` | 首次与每次重新订阅均调用，代际从 1 递增；在同代通知前串行分发 |
| `completion()` | 会话自然失败或关闭、接收与回调线程退出后的终态 |
| `closeAsync()` | 停止接收和重连、关闭连接、排空已接受回调后完成；多次观察共享终态 |
| `connectTimeout` / `pollTimeout` | 10 秒／1 秒；连接选项同时限制登录和网络读取，覆盖 URL 中冲突的超时 |
| `reconnectAttempts` / `backoff(initial, maximum)` | 最多 5 次连续重连，退避从 1 秒增加至 30 秒；成功轮询后重置连续失败计数 |
| `queueCapacity` / `closeTimeout` | 256 个待分发事件（含订阅和故障事件）／30 秒同步关闭等待 |

- `PostgreSqlListenOptions.builder()` 配置上述选项后 `build()`；超时、容量和退避校验有效范围，轮询时限须符合 pgJDBC 的 int 毫秒限制。
- 频道名正确转义为 SQL 标识符，拒绝空值、NUL 或超过服务端标识符字节上限的名称，避免静默截断或 SQL 注入。
- 先订阅并提交，再读业务快照，然后依靠通知失效。NOTIFY 不持久化，不保证断线重放或业务恰好一次；重连后必须重读。
- 认证、数据库或配置错误立即终结；其他连接故障有界重试，`onFailure(error, false)` 报告重试，最终故障通过 `completion` 和终态回调报告。
- 队列溢出明确失败，排空此前已接受事件，不静默丢弃。容量限制本库分发队列，不代表 pgJDBC 内部接收批次有相同上限。
- 关闭不会打断已接受的回调，也不等待回调自行启动的外部异步工作。阻塞回调会延迟排空；`dispose` 超时明确失败，不能称资源已关闭。
- 通知回调与 `ready()` 的同步 continuation 均可以调用 `closeAsync()` 后返回，不能 `join/get/await` 自己的关闭或关闭其等待自己的 Scope；直接等待返回的关闭 future 会被拒绝，派生异步链也不得自行阻塞等待。

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

`Schema` 和 `MigrationRunner` 用正整数版本描述 KV 存储迁移。版本可以不连续，执行时按版本升序排列。每一项迁移的「读取当前版本、执行迁移、更新版本」在同一事务中：

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

- 版本号必须为正数且唯一，迁移动作不能为 null；`Schema.version()` 返回最新声明版本，空结构为 0。
- 存储版本为负数或比当前声明的最新版本更高时拒绝迁移，不静默降级。每步失败回滚该步 KV 写入与版本，先前已成功的步骤保留。
- 后端的锁定读与版本比较不等于通用分布式迁移锁，首次初始化尤其要避免多个实例同时迁移。关系型业务表使用上面的 SQL 迁移门禁与外部引擎。
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

## Kotlin 挂起调用

存储会话、迁移、缓存以及 JSON/JDBC 提供器由 Kotlin 实现；Java 继续得到原有 `CompletionStage`，Kotlin 挂起入口等待相同操作，不创建第二套存储引擎。

```kotlin
import me.kzheart.klib.data.*

suspend fun save(session: StorageSession) {
    session.putAwait("players", "demo", byteArrayOf(1))
    val bytes = session.getAwait("players", "demo")
    session.deleteAwait("players", "demo")
}
```

需要会话所有权时使用 `provider.withSession { ... }`：块可在操作之间挂起，正常返回、抛错或取消都会关闭会话；关闭在不可取消的 IO 上下文执行，调用方挂起等待清理完成，不阻塞服务器主线程；关闭失败不会覆盖原业务异常。单独使用 `openAwait` 时，成功获得会话后由调用方负责释放。

`openAwait`、`getAwait`、`putAwait`、`deleteAwait`、`entriesAwait` 等待现有 provider 的异步操作；不会在调用线程另起一个阻塞引擎。已取消的打开操作若后来返回会话，会在 IO 线程自动释放该会话，不阻塞取消或完成线程；这类失去调用方的清理异常交给原协程上下文的异常处理器（未配置时使用协程默认处理）。

`transactionAwait { ... }` 的事务块刻意保持普通、不可挂起函数，仍由 provider 的事务执行器管理。取消等待不代表底层已开始的 JDBC 写入被回滚；需要原子性时把关联写入放入同一事务。

| 调用方选择 | 提供器内部负责 |
| --- | --- |
| 后端与会话 | 执行队列、资源归属、失败与关闭 |
| `transaction` / SQL `work` | 连接借还、提交/回滚及连接状态恢复 |
| `Schema` / `MigrationRunner` | 版本门禁与顺序执行；数据库升级策略仍由业务决定 |

`StorageProvider`、`StorageSession`、事务/行映射 SAM 与 `Migration.MigrationAction` 保留 Java 契约，兼容既有可空实现、泛型与异常声明。各驱动仍是按需选择的独立制品，不因实现语言变化一起打包。

## 线程与生命周期

- Java 存储操作返回 JDK `CompletionStage`；Kotlin 薄入口挂起等待同一个异步操作。
- 事务回调运行在存储线程；`thenApply`、`thenAccept`、`thenCompose` 等非 `Async` 回调可能运行在完成线程，已完成的阶段也可能在注册回调的线程执行。它们**不会自动切回主线程**，不能据此调用要求主线程的 Bukkit API。
- 向玩家反馈时，用 `Scope.sync(Runnable)`、`scope.submitSync(stage, mapper)` 或 `AsyncTasks.thenSync(stage, scope, action)` 显式切回主线程，详见 [Core 的桥接 CompletionStage](core.md#桥接-completionstage)。旧写法 `Scope.after(Ticks.of(0), ...)` 语义等价，新代码用 `sync`。
- 把 provider、成功打开的 session 和 `PlayerDataCache` 安装进同一个业务 `Scope`。关闭作用域时，缓存先排空脏数据，随后会话和提供器按逆序释放。
- `PlayerDataCache.dispose()` 最多阻塞等待 30 秒。超时或保存失败时，如果传了 `KLogger`，会记录仍未落盘的玩家数量。

## 常见坑

- 不传 `KLogger` 的构造器完全静默，生产环境数据库故障服主看不到。
- 停服阶段**不要**再提交新的写入，也不要用忽略返回阶段的「即发即忘」保存。
- MySQL URL、用户名和密码来自配置或环境，**不要**写进源码或日志。
- 多步异步调用拼起来的「检查后写入」不是原子的，用 `transaction(...)`。
- KV JDBC 会话只有一条连接，吞吐有上限；独立 SQL executor 按工作借还连接，但不内置池。
- JSON 后端有固定预算（见上表），超出时整个事务失败。

## 相关页面

- [Core](core.md)：作用域、`AsyncTasks` 与主线程桥接
- [Remote](remote.md)：把存储诊断附加到 Incident
- [构建与打包](../../README.md)：`data { }` 后端选择与打包
