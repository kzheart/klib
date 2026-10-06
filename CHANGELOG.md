# 更新日志

## 0.15.0（2026-10-06）

- UI 重写聊天面板（不兼容旧 API）：`ChatPanel` 改为带 id 的页面定义，内容由页面函数在每次显示、点击、翻页和输入完成后按实时状态重建，不再重放打开时的旧模型。
- 按钮改为稳定地址 `/<命令> <页面 id> <按钮 id>`，取消一次性令牌与固定 2 分钟有效期；旧按钮点击时按实时页面路由，按钮消失、页面失效、缺少权限或会话过期都会提示玩家并记录 `chat-panel` 调试日志，权限与 guard 每次点击重新检查。
- 动作执行后下一 tick 重画当前页；整页一条消息并补满固定行数（默认 20 行），布局为标题、正文、状态行、导航行。新增 `ChatPanelView` 分组、一行多按钮、悬停、按钮当前值与 (R) 重置，`session.status(...)` 与 `panels.notice(...)` 把结果显示在面板内。
- 会话支持页面跳转与返回、按空闲时间过期（默认 15 分钟）、`once()` 一次性确认页；关闭时发送空屏把面板文字顶走。
- 面板内输入改用 `ChatPanelInput`：输入期间面板保持显示并提供预填与取消，提交、取消或超时后回到原页；Paper 服务端可切换为虚拟告示牌输入，可通过 `ChatPanelSignInput` 替换实现。
- 新增 `ChatPanelOptions` 与 `ChatPanelText`，可调整行数、空闲时间、发送方式、告示牌输入和全部内置文案。
- 重构 README 的项目介绍、核心能力、接入导航与模块索引，新增项目标识与状态徽章，并同步文档入口及 Wiki 首页。

## 0.14.1（2026-10-05）

- 修复 Paper 匿名 Spigot 实现类的反射访问失败：从公开父类或接口解析发送方法，保留帮助的 RGB、悬停、命令预填和分页点击事件。

## 0.14.0 - 2026-10-05

- Command 与 Lang 修复现代 Bungee ChatColor 非枚举导致富消息整体退回纯文本的问题；颜色按公共常量读取，恢复分页、悬停与预填，并补齐复制等点击动作。
- Command 新增典雅、简洁、面板、经典四套 `CommandHelpStyle` 预设、YAML `CommandHelpStyles.parse` 和自定义布局/配色/分页，根/路由可使用动态提供器随配置重载更新样式。
- 帮助保留全部有效参数写法、从有描述的祖先继承用途；参数不完整时同时输出完整用法和说明，并可点击预填。内置 help 使用可选页码，根帮助与翻页使用同一页容量。
- 新增 `CommandHelpEntry` 与 `HelpRenderer.renderEntries`，支持业务自行过滤权限后汇总独立短命令、分组、悬停和实际翻页命令。

## 0.13.0 - 2026-10-05

- Command 新增 `CommandErrorHandler`、模块安装错误策略和根/路由 `errorHandler(...)`：普通业务异常默认仅记录并返回 FAILED，不再自动发送统一“命令执行出错”或回显异常信息；最近路由覆盖根及模块策略。内置异步 reload 保留原命令上下文，严重 Error 仍继续抛出，参数与权限错误保持命令规则。
- 聊天面板也支持业务 `errorHandler(...)`；未配置时只记录并结束失败会话，不发送固定错误提示。

- UI 新增 `BukkitChatPanels`、`ChatPanel`、`ChatPanelButton` 和 `ChatPanelSession`：支持字段/列表行、分页、悬停、点击回调、玩家权限命令、预填当前值、剪贴板复制与可选 F/潜行+F 保存取消。
- 聊天按钮绑定玩家实例、当前页 UUID 和绝对有效期；刷新、重复点击、退出和作用域关闭使旧按钮失效。每次操作及聊天输入完成时重新检查权限与业务 guard，异步输入通过所属 Scope 回到主线程。
- 面板复用现有 Lang 富文本和 BukkitChatPrompts，不保存业务草稿、不授予 OP、不以控制台代执行玩家命令；UI 公开依赖 Command（含 Lang），安装时需在启动阶段先安装命令模块。

## 0.12.0 - 2026-10-05

- Command 改为启动声明模型：支持公开命令生命周期 API 的 Paper 通过 `JavaPlugin.onEnable` 中的 `LifecycleEvents.COMMANDS` 安装 `BasicCommand` 原始参数入口；无该 API 的 Bukkit/Paper 保留启动期 `CommandMap` 注册。通过宿主能力发现保持公共 Java 8 制品，不引入 Java 21 硬依赖。
- 删除依赖 NMS `COMMAND_SENDING_POOL`、`processQueue` 的协调机制及 `CommandMutationGate`、`ServerCommandSync`。配置重载不重建命令；作用域关闭立即停用执行、补全和权限检查，物理节点可能保留到服务端生命周期重建或重启，不再承诺运行时新增根或即时物理注销。
- 默认冲突策略仍为 `REJECT`；显式 `REPLACE_UNQUALIFIED` 仅接管裸标签，不覆盖其他插件命名空间，关闭后不承诺恢复原绑定。现代 Paper 使用实际插件名的命名空间，`discover` 前缀仅用于旧 Bukkit 注册路径；同一插件不得混用其他注册器管理相同标签。
- 新增显式宿主的 `BukkitCommandRegistrar.discover(plugin, prefix[, policy])`；`CommandModule.install(plugin)` 直接使用已知宿主，不依赖 Klib 与插件共享 ClassLoader。
- Brigadier 仅在 `AsyncPlayerSendCommandsEvent` 的同步回调中修改玩家树副本，并逐节点过滤权限。原始参数服务端分发保留中文标签、含冒号参数、本地化错误和大小写不敏感的 literal 匹配。
- Core 的 `Scope.syncExecutor()` 复用同步任务句柄，作用域关闭会取消尚在排队的回调；保持原有同步排队顺序，不增加额外 tick 延迟。

## 0.11.4 - 2026-10-05

- Item 新增 ZaphkielPlus 通用适配器，以 `zap:ID` 生成、识别物品；通过实际插件对象取得公共服务，停用期间保留只读身份识别。
- 外部物品生成支持玩家上下文与逐件批量准备；Zap 数量生成要求逐件创建，避免复制实例身份及随机结果。

## 0.11.3 - 2026-10-05

- Command 在服务端真正消费队列后才启动异步构建器排他窗口及超时计时，避免耗时较长的插件启动阶段误丢命令。

- Command 新增显式 `CommandRegistrationPolicy.REPLACE_UNQUALIFIED`：在实际注册的安全窗口捕获并替换裸标签，保留其他插件命名空间，失败回滚；关闭后仅恢复仍活动且未被后来持有者占用的原绑定。默认冲突拒绝行为保持不变。

## 0.11.2 - 2026-10-04

- 修复 `BukkitScriptHost.apply(...)` 错误拒绝异步纯表达式上下文构建的问题：安装仅复制构造期冻结的服务引用，可从任意线程作用于调用方独占的新构建器；变量和发送者上下文保持隔离。
- 安装与宿主关闭状态切换串行，关闭后拒绝新安装；所有实际 Bukkit 服务及 JavaScript 调用继续检查主线程和关闭状态，未放宽线程访问边界。

## 0.11.1 - 2026-10-05

- Command 将 Paper 命令注册/注销与客户端刷新统一协调：异步等待在途构建器释放旧节点，主线程批量变更并合并刷新，避免作用域关闭时并发修改命令树。
- 待完成的命令注册可取消；绑定关闭立即禁止执行、权限检查与补全，节点清理使用服务端队列而非已停用插件的调度器。全服停止不刷新客户端，未消费排他任务过期释放且拒绝迟到写入。

## 0.11.0 - 2026-10-04

- 新增真正只编译的 `validate(source, context)`，不执行动作、模板或宿主服务，避免通过执行哨兵模拟预检。
- Script 新增 `ScopedScriptRuntime`，把原生执行与 detached async 子动作归属到作用域，支持配置重载取消、调用方取消、关闭后续接门禁和完成后的资源释放。
- 修复 `await` 的异常及取消传播，避免等待链永久挂起，并保证流程动作的异步输入在指定执行器续接；`all`/`any` 改为保留原生动作树，支持嵌套 check、权限及业务动作，保持依次求值全部输入的策略。嵌套玩家动作使用原生 `ScriptSenderQuery`/`PlayerQuery` 校验，自定义宿主应安装完整玩家服务。
- 修复具名脚本前置注释识别；明确 exit 会结束作用域持有的 detached async，避免完成主结果后继续持有等待。
- Script 新增显式 `KetherCompatibility.installLegacyCases(...)`，可选接受 `else ->` 和旧显示映射的大写多词标签，不改变默认解析或容错选项。
- Script 新增 `BukkitScriptHost`，缓存带主线程及关闭检查的宿主服务；原生侧边栏和外部共享侧边栏回调均显式启用，按所有权恢复计分板。
- 新增 `ScriptJavaScriptEngines` 与懒加载 JavaScript 工厂接入，不捆绑 Nashorn、不提高公共库或运行时依赖的 Java 8 边界。

## 0.10.1 - 2026-10-05

- Command 新增 `@Command(help = true)`，从注解路由生成权限过滤的 `help [page]`，保留自定义帮助与根命令行为。
- 修复注解命令的可见性计算：内置帮助处理器没有额外访问条件时仍可见，避免 `help` 被误隐藏。

- 校正 GitHub Wiki 的模块、作用域和平台说明，与当前源码及已发布 API 保持一致；UI 聊天输入示例显式使用同步执行器消费结果，避免完成后才注册的回调在错误线程访问 Bukkit。

## 0.10.0 - 2026-10-04

- PostgreSQL 集成测试不再复用构建缓存，避免把未配置数据库时的跳过报告当作已连接数据库的验证结果。

- Lang 新增 `BukkitAdventure`，显式连接 Paper 自带的 Adventure；内嵌重定位不再污染调用宿主 API 的参数类型，支持富文本、物品名/lore、书本与菜单标题。
- UI 新增 `MenuInventoryFactory` 与 `MenuRenderer.install` 工厂重载，保留框架的点击保护、会话和资源回收；校验工厂返回的 holder 与尺寸。
- 注解命令的根名、别名、字面量支持 Unicode 字母与数字，中文别名与原命令共享权限、补全和生命周期。

## 0.9.2 - 2026-10-03

- 修复 Bukkit Kether 容器发现扫描无关插件公开方法、因其可选依赖（如 WorldGuard）未安装而触发 `NoClassDefFoundError`；现在先按插件入口类名筛选 TabooLib 容器。

## 0.9.1 - 2026-10-03

- 修复安装 `TabooLibKetherInterop` 时，引号中的空白文本（如 `" "`）被交给远端解析而报 `action must not be blank`。
- 容错模式下引号括起的文本总是字面量，不再被同名语句解析（例如列表中的 `"or"` 不会成为 Klib 的 `or` 语句）。

## 0.9.0 - 2026-10-03

- `klib-script` 补齐原框架（TabooLib 6.3.0）内置 Kether 语句：`wait`/`sleep`、`exit`/`stop`/`terminate`、`pause`、`for`、`while`、`map`、`repeat`、`break`、`seq`、`call`、`goto`、`async`/`await`/`await_all`/`await_any`、`import`/`release`、`optional`、`pass`、`vars`、`log`/`warn`/`error`、`array` 与 `arr-*` 系列、`size`/`length`、`split`、`range`、`shuffle`/`reverse`/`mutable`、`join [ ... ] by`、`uncolored`、`scale`、`format`、`printed`、`match`、`time`/`date`、`day of`、`year`/`month`/`hour`/`minute`/`second`、`actionbar`、`broadcast`/`bc`、`players`、`switch`、`title`/`subtitle`、`location`/`loc`、`sound`/`stopsound`、`itemstack`、`material`、`scoreboard`、`js`/`javascript`/`$`；`tell` 增加 `send`/`message` 别名与 `@sender` 替换。
- 新增 `&变量[键]`、`动作[键]` 属性读取与 `get property 键 from 动作`，内置 String、Map、List、数组与正则 Matcher 属性，宿主 `ScriptPropertyAccess` 优先；`set &对象[键]` 同样支持这些内置类型。
- `player` 按原框架操作名表匹配多词属性（如 `block x`、`on ground`），支持 `to`/`add`/`sub` 写入；`command` 支持 `as|by|with player/op/console` 与 `@sender`。
- 新增宿主服务 `ScriptPlatform`、`ScriptLogger`、`JavaScriptEvaluator`，以及 `PlayerQuery.write`、`CommandSink.dispatchAsOperator`、`ScriptSenderQuery.isOnline` 默认方法；`BukkitScriptServices.apply(builder, plugin)` 一次安装全部 Bukkit 默认实现。
- `KetherScriptEngine` 新增四参数构造器，`toleranceParser` 为 true 时与原框架默认行为一致，未注册词元按字面量处理；默认仍为严格解析。安装 `TabooLibKetherInterop` 时，同服容器都不认领的词元同样按字面量处理。
- 行为变化：`element`、`elem`、`async` 等成为内置语句，原先依赖它们作为字面量或远端语句名的脚本需改用 `*element` 等写法。

## 0.8.11 - 2026-10-02

- `MountedCommand` 的挂载子命令支持多级路径（如 `"quest data"`），用于挂载本身嵌套在另一处理器子命令下的处理器。

## 0.8.10 - 2026-10-02

- `klib-command` 新增 `MountedCommand.of(command, handler, literal, aliases...)`：在同一次 `register(...)` 中把注解处理器的全部路由挂到另一根命令的子命令（及其别名）下，处理器自身根命令不受影响；权限、Check 与补全沿用处理器声明，不支持嵌套挂载。

## 0.8.9 - 2026-10-02

- `QuestReader.source(begin, end)` 返回指定游标范围的原始脚本文本（包含匿名块的花括号），供需要保存后续动作源码的解析器使用；未持有源码的读取器抛出 `UnsupportedOperationException`，现有解析行为不变。

## 0.8.8 - 2026-10-02

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
