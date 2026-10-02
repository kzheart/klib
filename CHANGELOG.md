# 更新日志

## 未发布

- `klib-compat` 新增 `Capabilities.SIDEBAR` 与 `me.kzheart.klib.compat.sidebar.Sidebars`：按玩家发送数据包侧边栏，不替换玩家的 Bukkit 记分板，更新只发送差异，玩家退出时丢弃状态、作用域关闭时移除；四个 `compat-v*` 实现均公开该能力。改写自 TabooLib `NMSScoreboard`（MIT），不引入 TabooLib 或 Kotlin 运行时。
- 侧边栏在 Paper 1.12.2、1.16.5、1.18.2、1.20.4、1.21.1、1.21.4、1.21.11、26.1.2 与 26.2 上用真实客户端验证；`klib-compat` 只在编译期使用 Spigot API 与 `klib-core`，发布依赖不变。

## 0.8.7 - 2026-10-02

- 统一 Java 源码、测试与示例的类型引用：显式 import 后使用简单类名，嵌套类型使用 Outer.Inner，仅真实同名冲突保留必要限定名；公共类型名称、签名与执行行为保持不变。

## 0.8.5 - 2026-10-02

- `ValueDocument` 可持有第三方格式已解析的原值树，保留标量类型、键顺序、缺失与显式 null、可变子节和文档所属 writer；现有 YAML AST 契约保持不变。
- `ConfigNode` 新增原样名称、列表元素视图和子节文本能力；mapper 的 POJO、列表、映射和数组支持两种后端，保留 YAML 错误位置。
- `ValueDocument.ofWithNodeWriter` 与 `ConfigNode.sameNode` 为格式 writer 提供节点身份，区分同路径替换的新节、旧持有视图及列表内映射，原 path writer 契约保留。

## 0.8.4 - 2026-10-02

- `KetherScriptEngine.evalChecked` 区分同步编译异常与异步执行失败，复用同一编译缓存，现有 `eval` 契约保持不变。
- `ScriptContext.Builder.senderVariable` 可从当前帧可见变量动态读取执行者，支持切换、显式 null 与移除；固定 sender 仍为默认行为。
- `ConfigNode.createSection` 在已有映射内创建或替换空子节，保留原样键和父文档可见性。

## 0.8.3 - 2026-10-02

- `klib-script` 的 `check` 改为双嵌套动作解析，补齐原比较运算符、null 与独立布尔文本，保留公开的 `=` 别名。
- 新增只读 `player` 查询；`papi`/`placeholder` 接完整动作，玩家和占位符查询通过显式宿主服务提供，异步续接回到宿主执行器。PAPI 多词参数需加引号且不再接受非玩家；比较改用原类型推断/数值强制转换算法。

## 0.8.2 - 2026-10-02

- `klib-script` 新增 `ScriptFrames`：原生动作访问宿主服务和实时分层变量，普通动作、嵌套脚本与原生 Frame 的写入相互可见；求值结束写回调用方上下文，保留显式 null，排除内部键。
- 新增 `ScriptTemplates.renderImmediate`：按最内层求值与反斜线转义处理文本模板，未完成或 null 值输出 `null`；现有 `inline` 等待语义保持不变。
- 补充动作树形式的 `math`、`round`、`random`、`set ... to ...`、`permission` 与 `sender`，对象属性和发送者信息通过显式宿主服务提供。

## 0.8.1 - 2026-10-02

- 修复 `klib-script` 的 `command` 扁平词元读取错误：命令参数可求值 `inline *"..."`、变量及嵌套动作，保留已有无星号文本写法、多语句边界和自定义同名语句注册；异步参数完成后由宿主续接执行器派发命令。

## 0.8.0 - 2026-10-01

- 新增 `klib-data-postgresql` 与 `PostgreSqlStorageProvider`，支持 PostgreSQL 字节存储、原子覆盖写和事务迁移，运行时引入 pgJDBC 42.7.13。
- JDBC 方言补充 PostgreSQL `BYTEA`、`ON CONFLICT` 与秒级连接超时。
- CI 与发布门禁使用真实 PostgreSQL 验证读写、迁移回滚和重新打开后的持久化。

## 0.7.0 - 2026-10-01

### 新功能

- `klib-script` 支持 `tell colored inline` 嵌套动作、`case` 条件分支、`calc` / `calculate` JEXL 表达式以及 `invoke` JEXL 脚本。动态表达式与内联脚本共用上下文和异步续接调度。
- `QuestReader.nextValue()` 为完整语句解析器提供动作或字面量参数读取；独立未知动作和嵌套动作错误仍然失败。
- 脚本模块传递引入 Commons JEXL 3.2.1 和 Commons Logging 1.2，打包时须按模块文档配置重定位。

### 修复

- `if` 没有 `else` 时不再吞掉后续独立语句。内联脚本变量写入同步回外层执行帧。

## 0.6.0 - 2026-09-24

### 新功能

- `klib-core` 新增 `Cooldowns`：任意键的线程安全冷却表，支持原子获取、缩短、延长、按条件清除，
  `perPlayer(scope)` 在玩家退出时自动移除。
- `klib-core` 新增 `WeightedPool`、`IntRange`、`DoubleRange` 与 `Chance`；非法权重在构建时失败，
  区间解析支持 `1-5`、`1~5` 与负数。`klib-config` 将两种区间注册为内置配置类型。
- `ItemBuilder` 新增 `customModelData`、`unbreakable`、`glow`、`skullOwner`，`Items` 新增 `playerHead()`
  与 `customModelData(item)`；低版本缺失的属性通过反射兼容。
- `klib-item` 新增 `ExternalItems`，以 `prefix:id` 统一生成与识别 MMOItems、NeigeItems、ItemsAdder 和
  MythicMobs 物品，全部通过反射适配，并提供挂钩状态报告。
- `klib-hook` 新增 `me.kzheart.klib.hook.cost`：从配置字符串解析消耗与奖励，先检查后扣除，
  中途失败自动逆序退还，并报告无法退还的部分。

## 0.5.0 - 2026-09-23

### 新功能

- `KPlugin` 新增无参 `setup()` 与实例绑定的 `commands()`、`configs()`、`events()`、`tasks()`、`components()`。
- 新增 `KComponent` 功能组件与 `@OnStart`、`@OnStop` 生命周期注解。
- 新增命令、配置、事件、任务和菜单的注解声明入口，详见 `docs/annotations.md`。

## 0.4.0 - 2026-08-21

### 破坏性变更

- 将数据存储拆为 `klib-data`、`klib-data-json`、`klib-data-jdbc`、
  `klib-data-sqlite` 和 `klib-data-mysql`。基础数据模块不再传递引入 Gson、SQLite、
  MySQL Connector/J 或 Protobuf；JSON 与 SQLite 使用宿主运行时，MySQL 驱动必须显式选择。
- `SQLiteStorageProvider` 移至 `me.kzheart.klib.data.sqlite`，`MySqlStorageProvider`
  移至 `me.kzheart.klib.data.mysql`，JDBC 公共类型移至 `me.kzheart.klib.data.jdbc`。
- `klib-item` 与 `klib-ui` 不再把 Spigot API 写入发布依赖元数据。

## 0.3.0 - 2026-08-20

### 新功能

- 新增 `GuardKetherInterop`，支持云端商品与门户插件双向共享 Kether Action。
- 为跨 ClassLoader 调用增加值边界、句柄生命周期与作用域自动清理。
- 扩展 TabooLib Kether OpenContainer 的 owner-aware Action 移除能力。

### Guard API 0.2.0

- 新增 `me.kzheart.klib.guard.kether` 公共 Broker 协议。
- `PluginHost` 新增商品代次与 Kether Broker 能力。
- 该版本对旧 `PluginHost` 实现不兼容，Guard runtime 与云端商品需要同步升级。

## 0.2.0 - 2026-08-18

### 破坏性变更

- Klib 普通模块、Gradle 插件和 Guard API 改为独立版本源，不再假定三者版本相同。
- 推荐的 Gradle 插件接入删除字符串式模块选择，统一使用可由 IDE 补全的类型安全 DSL，例如
  `modules { command(); data() }`。

### 新功能

- 普通模块升级到 `0.2.0`，增加 Maven Central POM、源码包、Javadoc 包、校验和与 PGP 签名接线。
- 新增 Apache-2.0 的最小 `klib-guard-api:0.1.0` 编译期模块，只暴露商品生命周期契约。
- 公共库、Gradle 插件和 Guard runtime 拆分为独立仓库，公共库不再依赖私有源码或构建缓存。

## 0.1.1 - 2026-08-16

### 破坏性变更

- `klib-remote` 从旧诊断、心跳和版本查询接口切换为 Remote v1；旧 API 不再保留。

### 新功能

- 首次正式发布面向 Bukkit/Paper 的 Java 8 Klib 公共模块，覆盖生命周期与调度、配置、语言、命令、
  物品、数据存储、UI、Kether、外部插件 Hook 和 Minecraft 版本能力查询。
- 新增 Remote v1 Java 客户端：支持结构化日志、Incident、操作链、远端 fail-closed 策略和有界离线队列。

### 修复

- 修复 Windows 首次写入默认配置和提交 JSON 存储事务时，目录物理刷盘可能触发
  `AccessDeniedException` 的问题；继续保留临时文件写入与原子替换。
