# Config 模块

状态：稳定公开模块
模块名：`config`
制品：`me.kzheart.klib:klib-config`

`klib-config` 将类路径中的默认 YAML 提取到插件数据目录，并映射为 Java 8 POJO。它支持默认值合并、注释保留、文件监听、原子重载、版本迁移和目录型配置注册表。

## 何时使用

适合以下场景：

- 用类型安全对象替代散落的字符串路径读取；
- 首次启动时生成默认配置，并在版本升级时补入新增默认项；
- 修改 YAML 后自动刷新运行态或重建某个作用域；
- 配置格式演进时保留用户值与注释；
- 从一个目录加载多个同类型 YAML 定义。

## 接入

推荐通过 Klib Gradle 插件选择模块：

```kotlin
klib {
    modules {
        config()
    }
}
```

`config` 会自动带入 `core`。

直接依赖的高级用法：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-config:<klib-version>")
}
```

直接依赖时需自行打包和重定位 Klib 及其运行时依赖。

## 快速开始

先在资源目录放置默认文件：

```yaml
# src/main/resources/defaults/config.yml
debug: false
heartbeatTicks: 1200
database:
  host: localhost
  port: 3306
```

创建对应的配置类型。字段可以不是 `public`，但类型必须有无参数构造器；`final` 字段只要 YAML 里出现同名键就会加载失败，因此参与映射的字段不要声明为 `final`：

```java
public final class Settings {
    public boolean debug;
    public long heartbeatTicks = 1200L;
    public Database database = new Database();

    public static final class Database {
        public String host = "localhost";
        public int port = 3306;
    }
}
```

在插件的 `setup` 中安装能力并加载文档：

```java
@Override
protected void setup(Scope root) {
    ConfigModule.install(
            root,
            getDataFolder().toPath(),
            getClassLoader(),
            "defaults");

    ConfigDocument<Settings> config =
            root.config(Settings.class, "config.yml");

    logger().info("数据库地址："
            + config.value().database.host
            + ":"
            + config.value().database.port);
}
```

首次加载时，`defaults/config.yml` 会提取为插件数据目录中的 `config.yml`。请求路径必须是数据目录内的非空相对路径，`../` 等越界路径会被拒绝。

## 类型映射

内置映射覆盖常见配置模型：

- 字符串、布尔、字符和 Java 数值类型；
- 大小写不敏感的枚举；
- `Duration`，支持 ISO-8601 和 `ms`、`s`、`m`、`h`、`d` 组合，例如 `1m30s`；
- 普通数组；
- 带泛型参数的 `List<T>`、`Set<T>` 等集合；
- 键类型为 `String` 的 `Map<String, T>`；
- 嵌套 POJO 以及从父类继承的实例字段。

POJO 映射遵循以下规则：

- 类型必须可实例化并具有可访问的无参数构造器；Klib 会反射访问非公开构造器和字段；
- `static`、`transient` 和编译器生成字段会被跳过；
- YAML 未提供的字段保留构造器或字段初始化器给出的默认值；
- YAML 提供了某字段但类型不匹配时，加载失败，而不是静默转换；
- 出错信息包含来源文件、行列号和字段路径，便于定位，例如 `config.yml:12:5 (database.port): expected a number, got String`；YAML 本身语法错误时同样带上出错行列；行列均从 1 开始，源节点没有位置信息时退回 `config.yml:database.port: ...` 形式；
- 原始集合类型没有元素类型信息，因此不支持，必须写明泛型。

### 未知键提示

映射 POJO 时，若 YAML 中某个键在目标类型里找不到同名字段，Klib 会以 `WARNING` 记录一次，例如：

```text
config.yml:4:3 (database.prot): unknown configuration key is ignored; com.example.Settings$Database has no matching field
```

行为边界：

- 只提示，不抛异常，也没有严格模式开关；该键的值被忽略，字段保留默认值；
- 判定只发生在 POJO 层级。`Map<String, T>` 字段与集合元素的键由用户定义，不会被判为未知；嵌套 POJO（包括 `Map` 的 POJO 值）在各自层级单独检查；
- 与 `static`、`transient` 或编译器生成字段同名的键视为有意忽略，不提示；
- 迁移系统写入的根键 `_schema-version` 不提示；
- 同一来源的同一键只提示一次，重复 `reload()` 不会刷屏。

需要领域类型时，可以使用独立的 `YamlConfigMapper` 注册转换器。`ConfigModule.install` 使用自己的默认 mapper；自定义转换通常用于直接构造配置源或 `Registry`：

```java
YamlConfigMapper mapper = new YamlConfigMapper()
        .registerConverter(Endpoint.class, node -> {
            String[] parts = String.valueOf(node.raw()).split(":", 2);
            return new Endpoint(parts[0], Integer.parseInt(parts[1]));
        });
```

转换器需要报错时，用 `ConfigNode.mappingError(detail)` 或 `mappingError(detail, cause)` 抛出：它们是公开方法，产生的异常自动带上来源文件、行列号和当前路径，与内置错误格式一致。

## 重新加载

### 监听变化

```java
ConfigDocument<Settings> config = root.config(Settings.class, "config.yml");

config.onChange(() -> {
    logger().info("配置已更新，debug=" + config.value().debug);
});
```

生产文件源会监听文件变化。只有内容修订发生改变时，文件监听触发的重载才通知监听器；显式调用 `reload()` 或 `reloadAsync()` 时，即使内容没有变化也会通知。

`KPlugin` 已提供同步调度能力，所以配置变更监听器会排到 Bukkit 主线程。没有安装调度能力的独立 `Scope` 会在触发重载的线程直接运行监听器。

### 重建资源图

配置影响大量运行态资源时，最简单可靠的模式是：

```java
config.onChange(root::rebuild);
```

重建后旧 `ConfigDocument` 已关闭，新的 `setup` 会创建新文档。不要在长期异步任务中缓存旧的 `config.value()` 或旧文档引用。

### 等待监听器完成

`reload()` 完成配置交换后返回，但主线程监听器可能仍在队列中。命令需要等所有监听器完成后再报告成功时使用：

```java
CompletionStage<Void> completion = config.reloadAsync();
```

注意 `reloadAsync()` 的“异步”只指监听器：读文件、解析、迁移、写回和映射都在调用线程同步完成，方法返回前新值已经交换；返回的 `CompletionStage` 只等待变更监听器（在 `KPlugin` 中排到主线程）执行完毕。因此不要在主线程上把它当作“不阻塞的重载”使用；解析阶段本身失败时，异常直接由 `reload()` 抛出，或以已失败的 `CompletionStage` 返回。

它与 `CommandBuiltins.standardAsync(..., config::reloadAsync, ...)` 配合使用。监听器失败不会回滚已经成功加载的新配置；失败会通过返回的 `CompletionStage` 报告，同时以 `SEVERE` 级别写入日志（聚合异常包含每个失败监听器的堆栈）。因此 `config.onChange(root::rebuild)` 这类重建失败时，即使没有人消费 `CompletionStage`（例如文件监听触发的重载），控制台也能看到原因。

## 失败与原子性

重载采用“候选值完整解析成功后再交换”的方式。YAML 无效、字段类型错误或迁移失败时：

- `value()` 继续返回最近一次有效值；
- 变更监听器不会执行；
- 手工 `reload()` 抛出配置异常；
- 文件监听触发的失败会记录，但不会让监听线程退出。

可以统一观测失败：

```java
ConfigErrors.onReloadFailure(config, error ->
        logger().error("配置重载失败，继续使用旧值", error));
```

注册本身也归作用域持有，无需手工注销。

配置文件使用严格 UTF-8 解码。写回时会先写入同目录临时文件，再优先使用原子替换；文件系统不支持原子移动时，会先保留 `.bak` 备份再替换。这些措施用于避免常规写入失败留下半成品，不会额外强制文件或目录元数据刷入物理存储，也不承诺突然断电或操作系统崩溃时的持久性。

## 配置迁移

通过 `ConfigModule.install` 的迁移提供器，为不同文档建立连续迁移链：

```java
ConfigModule.install(
        root,
        getDataFolder().toPath(),
        getClassLoader(),
        "defaults",
        path -> {
            MigrationRunner migrations = new MigrationRunner();
            if ("config.yml".equals(path)) {
                migrations
                        .add(1, Migrations.rename("old-name", "name"))
                        .add(2, Migrations.rename(
                                "limits.old-max",
                                "limits.max"));
            }
            return migrations;
        });
```

配置中的 `_schema-version` 记录当前版本。迁移版本必须从当前版本开始逐级连续；缺少中间版本，或用户文件版本高于程序支持版本时会拒绝加载。迁移应设计为幂等操作。

`Migrations.rename` 会保留节点相关注释；目标路径已经存在时保留目标值并移除旧键。默认配置合并也会保留用户已有值和注释，只补入缺少的默认项。

## 目录注册表

一类定义分散在多个 YAML 文件时，可使用 `Registry<T>`：

```java
Registry<ArenaDefinition> arenas = Registry.open(
        root,
        getDataFolder().toPath().resolve("arenas"),
        ArenaDefinition.class,
        definition -> definition.id,
        new YamlConfigMapper());

ArenaDefinition spawn = arenas.find("spawn").orElse(null);
arenas.onChange(() -> logger().info("竞技场定义已更新"));
```

注册表只读取目录中的 `.yml` 和 `.yaml` 文件，以提取函数给出的 ID 组成不可变快照。任一文件加载失败时保留完整旧快照，不会发布半成功结果。它同样支持文件监听和 `ConfigErrors.onReloadFailure`，变更监听器失败也会以 `SEVERE` 级别记录。

## Remote 排障快照

`YamlConfigDocument` 和 `Registry` 实现 Core 的 `DiagnosticSource`。快照只包含来源、值类型、当前修订、
条目数、生命周期状态以及最近一次重载/监听器失败的异常类型，不包含配置对象或 YAML 正文，也不会在
Incident 发生时重新读取文件。开发者可显式把它们交给 Remote：

```java
remoteLoggerBuilder
        .contributor(new KlibDiagnosticContributor(config))
        .contributor(new KlibDiagnosticContributor(registry));
```

Remote 不会自动发现配置对象；是否采集这些上下文由插件开发者决定。接入与预算规则见
[Remote](remote.md#排障上下文与-contributor)。

## 生命周期与线程约束

- `ConfigDocument`、文件监听器、变更监听注册和 `Registry` 都属于创建它们的作用域；作用域关闭后不可继续重载。
- `value()` 和 `Registry.snapshot()` 发布的是完整替换后的快照，但配置 POJO 本身不是不可变对象。`value()` 返回的是活对象而不是防御性拷贝：把它当只读快照使用，修改字段不会写回 YAML，也会在下一次 reload 后随实例被替换而丢失；多线程访问以 reload 发布的新实例为准，需要长期持有时每次读取都重新调用 `value()`，不要缓存旧实例。
- 重载流程会串行化，较早开始的候选不会覆盖较晚完成的新值。
- 在 `KPlugin` 中，变更监听器通常在 Bukkit 主线程执行；不要在监听器里执行阻塞 I/O。
- 文件监听可能由后台线程触发。没有 Core 调度能力的测试或独立环境必须自行提供合适的监听器执行器。

## 注意事项

- 类路径默认文件是必需的；找不到对应资源时加载失败。
- YAML 数字采用严格语义：带前导零的整数会被拒绝，若它是标识符请加引号。
- 只修改 `config.value()` 得到的对象不会写回 YAML。Config 是读取、合并与迁移系统，不是任意对象序列化器。
- 自动监听由文件系统事件驱动；外部编辑器可能产生多次事件，但相同修订不会重复通知。
- 若仅一个局部功能受配置影响，优先重建该子作用域，而不是手工逐项替换资源。

## 相关模块

- [Core](core.md)：配置文档的作用域与主线程调度来源。
- [Lang](lang.md)：使用同一配置基础设施维护语言文件。
- [Command](command.md)：内置 `reload` 命令可等待 `reloadAsync()`。
- 完整接线方式见本页“快速开始”和“完整示例”。
