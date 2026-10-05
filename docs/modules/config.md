# Config 模块

把类路径中的默认 YAML 提取到插件数据目录，并映射为 Java 8 POJO。支持默认值合并、注释保留、文件监听、原子重载、版本迁移和目录型配置注册表。

| 模块名 | 制品 | 自动带入 |
| --- | --- | --- |
| `config` | `me.kzheart.klib:klib-config` | `core` |

适合：

- 用类型安全对象替代散落的字符串路径读取；
- 首次启动时生成默认配置，版本升级时补入新增默认项；
- 修改 YAML 后自动刷新运行态或重建某个作用域；
- 配置格式演进时保留用户值与注释；
- 从一个目录加载多个同类型 YAML 定义。

## 快速开始

```kotlin
klib {
    modules {
        config()
    }
}
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

**1. 在资源目录放默认文件：**

```yaml
# src/main/resources/defaults/config.yml
debug: false
heartbeatTicks: 1200
database:
  host: localhost
  port: 3306
```

**2. 创建配置类型。**

- 字段可以不是 `public`，但类型必须有无参数构造器。
- `final` 字段只要 YAML 里出现同名键就会加载失败，**参与映射的字段不要声明为 `final`**。

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

**3. 标注文件路径：**

```java
@ConfigFile("config.yml")
public final class Settings { /* 同上 */ }
```

**4. 在插件的 `setup()` 中安装能力并加载文档：**

```java
@Override
protected void setup() {
    ConfigModule.install(this);

    ConfigDocument<Settings> config = configs().load(Settings.class);

    logger().info("数据库地址："
            + config.value().database.host
            + ":"
            + config.value().database.port);
}
```

首次加载时，`defaults/config.yml` 会提取为插件数据目录中的 `config.yml`。

| 调用 | 说明 |
| --- | --- |
| `ConfigModule.install(this)` | 简化默认安装：插件数据目录、插件类加载器、资源目录 `defaults` |
| 接收 `Scope` 的 `ConfigModule.install` 重载 | 需要其他目录或迁移提供器时使用；根作用域通过 `context().scope()` 取得 |
| `configs().load(Settings.class)` | 从 `@ConfigFile` 取得文件路径 |
| `configs().load(Settings.class, "config.yml")` | 没有 `@ConfigFile` 时使用 |

请求路径必须是数据目录内的非空相对路径，`../` 等越界路径会被拒绝。

### 声明与校验注解

| 注解 / API | 作用 |
| --- | --- |
| `@Key` | 字段映射 |
| `@Range` | 数值范围 |
| `@Validate` | 跨字段校验 |
| `@Comment` | 用于显式 `ConfigDefaults.yaml(Settings.class)` 默认 YAML 生成，**不会自动写入现有文件** |

- 校验失败沿用原子重载流程，保留旧值。
- 配置仍通过 `ConfigDocument` 读取，不自动注入业务字段。

完整规则见 [组件与注解](../annotations.md)。

## 类型映射

内置映射覆盖常见配置模型：

| 类型 | 说明 |
| --- | --- |
| 字符串、布尔、字符、Java 数值类型 | — |
| 枚举 | 大小写不敏感 |
| `Duration` | ISO-8601，或 `ms`、`s`、`m`、`h`、`d` 组合，如 `1m30s` |
| Core 的 `IntRange`、`DoubleRange` | 单个数字、`"1-5"`/`"1~5"`/`"-5~-1"` 文本，或带 `min`、`max` 的映射；上界小于下界时加载失败并报告位置 |
| 普通数组 | — |
| `List<T>`、`Set<T>` 等集合 | 必须带泛型参数；原始集合类型没有元素类型信息，不支持 |
| `Map<String, T>` | 键类型必须是 `String` |
| 嵌套 POJO | 包括从父类继承的实例字段 |

POJO 映射规则：

- 类型必须可实例化并有可访问的无参数构造器；Klib 会反射访问非公开构造器和字段。
- `static`、`transient` 和编译器生成字段会被跳过。
- YAML 未提供的字段，保留构造器或字段初始化器给出的默认值。
- YAML 提供了某字段但类型不匹配时，**加载失败，不会静默转换**。

出错信息包含来源文件、行列号和字段路径：

```text
config.yml:12:5 (database.port): expected a number, got String
```

- YAML 本身语法错误时同样带上出错行列。
- 行列均从 1 开始。
- 源节点没有位置信息时，退回 `config.yml:database.port: ...` 形式。

### 未知键提示

YAML 中某个键在目标 POJO 里找不到同名字段时，Klib 以 `WARNING` 记录一次：

```text
config.yml:4:3 (database.prot): unknown configuration key is ignored; com.example.Settings$Database has no matching field
```

<details>
<summary>展开：未知键的判定边界</summary>

- 只提示，不抛异常，也没有严格模式开关；该键的值被忽略，字段保留默认值。
- 判定只发生在 POJO 层级。`Map<String, T>` 字段与集合元素的键由用户定义，不会被判为未知；嵌套 POJO（包括 `Map` 的 POJO 值）在各自层级单独检查。
- 与 `static`、`transient` 或编译器生成字段同名的键视为有意忽略，不提示。
- 迁移系统写入的根键 `_schema-version` 不提示。
- 同一来源的同一键只提示一次，重复 `reload()` 不会刷屏。

</details>

### 自定义转换器

需要领域类型时，用独立的 `YamlConfigMapper` 注册转换器。`ConfigModule.install` 使用自己的默认 mapper，自定义转换通常用于直接构造配置源或 `Registry`：

```java
YamlConfigMapper mapper = new YamlConfigMapper()
        .registerConverter(Endpoint.class, node -> {
            String[] parts = String.valueOf(node.raw()).split(":", 2);
            return new Endpoint(parts[0], Integer.parseInt(parts[1]));
        });
```

转换器要报错时，用公开方法 `ConfigNode.mappingError(detail)` 或 `mappingError(detail, cause)` 抛出。异常自动带上来源文件、行列号和当前路径，与内置错误格式一致。

## 重新加载

### 监听变化

```java
ConfigDocument<Settings> config = configs().load(Settings.class);

config.onChange(() -> {
    logger().info("配置已更新，debug=" + config.value().debug);
});
```

| 触发方式 | 何时通知监听器 |
| --- | --- |
| 文件监听（生产文件源） | 只有内容修订发生改变时 |
| 显式 `reload()` / `reloadAsync()` | 总是通知，即使内容没变 |

监听器在哪个线程运行：

- `KPlugin` 已提供同步调度能力，监听器排到 Bukkit 主线程。
- 没有安装调度能力的独立 `Scope`，在触发重载的线程直接运行监听器。

### 重建资源图

配置影响大量运行态资源时，最简单可靠的模式：

```java
config.onChange(root::rebuild);
```

重建后旧 `ConfigDocument` 已关闭，新的 `setup` 会创建新文档。**不要在长期异步任务中缓存旧的 `config.value()` 或旧文档引用。**

### 等待监听器完成

`reload()` 完成配置交换后就返回，但主线程监听器可能还在队列中。命令需要等所有监听器完成后再报告成功时：

```java
CompletionStage<Void> completion = config.reloadAsync();
```

**`reloadAsync()` 的“异步”只指监听器：**

- 读文件、解析、迁移、写回和映射都在调用线程同步完成，方法返回前新值已经交换。
- 返回的 `CompletionStage` 只等待变更监听器（在 `KPlugin` 中排到主线程）执行完毕。
- 因此不要在主线程上把它当作“不阻塞的重载”。
- 解析阶段本身失败时，异常直接由 `reload()` 抛出，或以已失败的 `CompletionStage` 返回。

它与 `CommandBuiltins.standardAsync(..., config::reloadAsync, ...)` 配合使用。监听器失败时：

- 不会回滚已经成功加载的新配置；
- 失败通过返回的 `CompletionStage` 报告，同时以 `SEVERE` 级别写入日志（聚合异常包含每个失败监听器的堆栈）；
- 所以 `config.onChange(root::rebuild)` 这类重建失败时，即使没人消费 `CompletionStage`（例如文件监听触发的重载），控制台也能看到原因。

## 失败与原子性

重载采用“候选值完整解析成功后再交换”。YAML 无效、字段类型错误或迁移失败时：

- `value()` 继续返回最近一次有效值；
- 变更监听器不会执行；
- 手工 `reload()` 抛出配置异常；
- 文件监听触发的失败会记录，但不会让监听线程退出。

统一观测失败（注册本身归作用域持有，无需手工注销）：

```java
ConfigErrors.onReloadFailure(config, error ->
        logger().error("配置重载失败，继续使用旧值", error));
```

### 文件编码与写回

<details>
<summary>展开：UTF-8 解码、临时文件替换与持久性边界</summary>

- 配置文件使用严格 UTF-8 解码。
- 写回时先写入同目录临时文件，再优先使用原子替换。
- 文件系统不支持原子移动时，先保留 `.bak` 备份再替换。
- 这些措施用于避免常规写入失败留下半成品；不会额外强制文件或目录元数据刷入物理存储，**也不承诺突然断电或操作系统崩溃时的持久性**。

</details>

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

- 配置中的 `_schema-version` 记录当前版本。
- 迁移版本必须从当前版本开始逐级连续。缺少中间版本，或用户文件版本高于程序支持版本时，**拒绝加载**。
- 迁移应设计为幂等操作。
- `Migrations.rename` 保留节点相关注释；目标路径已存在时保留目标值并移除旧键。
- 默认配置合并也会保留用户已有值和注释，只补入缺少的默认项。

## 目录注册表

一类定义分散在多个 YAML 文件时，用 `Registry<T>`：

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

- 只读取目录中的 `.yml` 和 `.yaml` 文件，以提取函数给出的 ID 组成不可变快照。
- 任一文件加载失败时保留完整旧快照，不会发布半成功结果。
- 同样支持文件监听和 `ConfigErrors.onReloadFailure`；变更监听器失败也以 `SEVERE` 级别记录。

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

## 创建配置子节

对底层 `YamlDocument` 的节点调用 `parent.createSection("transfer")`，在已存在的映射内创建空子节并返回视图。

- 同名值会被替换；父节点和文档后续读取立即可见，`toYaml()` 包含新子节。
- 键按原样处理，`"a.b"` 是单个键，不会拆成路径。
- 当前节点缺失或不是映射时，抛出带来源与路径的 `ConfigMappingException`，不会创建未关联的子节。
- 替换会保留已有键节点及其注释；旧子视图仍引用旧节点，替换后需要重新读取。
- 这是内存中的文档修改，**不会自动保存到文件，也不触发配置重载监听**。

## 原值文档与格式 writer

<details>
<summary>展开：用 ValueDocument 承载第三方解析器（JSON/TOML 等）得到的 Map</summary>

已用独立解析器得到 `Map<String, ?>` 时，可以创建 `ValueDocument`，直接保留原有标量类型，不必先序列化为 YAML。

- 它不读取文件、不解析 JSON/TOML，也不自动解码字符串。
- 解析策略与格式 writer 由调用方明确提供。

```java
Map<String, Object> values = new LinkedHashMap<String, Object>();
values.put("title", "NPC");
values.put("settings", new LinkedHashMap<String, Object>());

ValueDocument document = ValueDocument.of("dialogue.json", values,
        (path, section) -> jsonSectionWriter.write(path, section));
ConfigNode root = document.root();
ConfigNode settings = document.node("settings");
settings.createSection("transfer");
String text = settings.sectionText();
```

**path writer 回调**（`jsonSectionWriter` 表示调用方实际选用的格式 writer，不是本模块提供的 JSON API）：

- 首参是当前节点路径，根节点为 `""`；第二参是当前 mapping 的深层不可变 Map/List 快照。
- 相同值的两个节仍会分别传入各自路径，可用于定位原文件中的注释。
- 回调必须返回非 null 文本，异常直接传给调用方。

**`ValueDocument.of` 的数据语义：**

- 复制输入 Map/List 容器为文档自有的可变树，之后修改输入容器不影响文档。
- 标量保留原对象及类型，例如日期、布尔值和大整数。
- 嵌套 mapping 键必须是 String，不接受递归 Map/List 容器。
- `raw()` 返回深层不可变普通 Map/List 快照，不随文档修改而改变；标量对象本身不会额外克隆。
- null 和缺失键的 `raw()` 都为 null，用 `exists()` 区分。

**两种文档（`YamlDocument`、`ValueDocument`）共有的节点能力：**

| 方法 | 行为 |
| --- | --- |
| `name()` | 返回单个原样键，根返回空字符串；`child("a.b")` 的 name 为 `"a.b"`，不按点号截断 |
| `node("a.b")` | 仍按点分路径逐级读取；与 literal-key 的 `child` 分开使用 |
| `index(i)` | 返回列表元素视图，路径为 `list[i]`，name 为 `[i]`。非列表抛带来源/路径的 `ConfigMappingException`，越界抛 `IndexOutOfBoundsException` |
| `sectionText()` | 只接受存在的 mapping。`ValueDocument` 调用上述 writer；`YamlDocument` 用自身 YAML writer 渲染该子节并保留子节内注释，不把普通 `Map.toString` 当作格式文本 |
| `sameNode(other)` | 比较节点身份，不比较内容（见下） |
| `createSection` | 直接修改所在文档的父 mapping，同一父树的其它视图可见新值。替换前持有的旧子视图继续指向旧节点，需重新读取。不自动保存文件或派发 reload |

`sameNode(other)` 的身份规则：

- 必须属于同一文档；不同文档不可混比，也不暴露底层可变容器。
- YAML 比较 AST 节点；原值 mapping/list 比较容器引用；原值标量（含显式 null）比较父容器、原样键或索引与值引用。
- 两个值相等、路径文本相同或来源名相同，都不代表同一节点。
- 缺失节点和 null 参数始终返回 false。

**节点 writer：** 需要区分原节和同路径替换后的新节时，用命名工厂 `ValueDocument.ofWithNodeWriter(sourceName, values, BiFunction<ConfigNode, Map<String,Object>, String>)`。

- writer 第一个参数是本次 `sectionText()` 的当前节点视图，第二个仍是深层不可变容器快照。
- 可以保存初始节点视图，用 `sameNode` 关联所属格式的注释或其它元数据。
- 替换后的新节不会与初始节混同，仍持有的旧视图继续识别原节点；列表内映射与含点号、方括号的原样键也不依赖文本路径猜身份。
- 原有 `of` 的 path writer 继续按原契约工作。两种 writer 的异常和 null 结果都直接传播，不返回伪造文本。

**映射：** `YamlConfigMapper.read(document.root(), Settings.class)` 可映射两种文档的 POJO、列表、Map、数组及自定义 converter。

- 未知键告警仍按来源和路径去重。
- 原值文档没有 YAML 行列标记，错误包含来源与节点路径；YAML 文档继续提供原行列信息。
- 原有 `YamlDocument` 的解析、raw 转换、注释、默认值合并和 migration 行为保持原契约；新增后端不会把第三方格式的字符串或类型规范化规则强加给默认 YAML 文档。

</details>

## 线程与生命周期

- `ConfigDocument`、文件监听器、变更监听注册和 `Registry` 都属于创建它们的作用域；作用域关闭后不可继续重载。
- `value()` 和 `Registry.snapshot()` 发布的是完整替换后的快照，但配置 POJO 本身**不是**不可变对象：
  - `value()` 返回活对象，不是防御性拷贝，把它当只读快照用；
  - 修改字段不会写回 YAML，下一次 reload 后随实例被替换而丢失；
  - 多线程访问以 reload 发布的新实例为准；需要长期持有时每次都重新调用 `value()`，不要缓存旧实例。
- 重载流程串行化，较早开始的候选不会覆盖较晚完成的新值。
- 在 `KPlugin` 中，变更监听器通常在 Bukkit 主线程执行，**不要在监听器里做阻塞 I/O**。
- 文件监听可能由后台线程触发。没有 Core 调度能力的测试或独立环境，必须自行提供合适的监听器执行器。

## 常见坑

- 类路径默认文件是必需的，找不到对应资源时加载失败。
- YAML 数字采用严格语义：带前导零的整数会被拒绝；如果它是标识符，请加引号。
- 只修改 `config.value()` 得到的对象不会写回 YAML。Config 是读取、合并与迁移系统，不是任意对象序列化器。
- 自动监听由文件系统事件驱动。外部编辑器可能产生多次事件，但相同修订不会重复通知。
- 只有一个局部功能受配置影响时，优先重建该子作用域，而不是手工逐项替换资源。

## 相关页面

- [Core](core.md)：配置文档的作用域与主线程调度来源。
- [Lang](lang.md)：使用同一配置基础设施维护语言文件。
- [Command](command.md)：内置 `reload` 命令可等待 `reloadAsync()`。
- [组件与注解](../annotations.md)：`@ConfigFile`、`@Key`、`@Range`、`@Validate`、`@Comment` 的完整规则。
