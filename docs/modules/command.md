# Command 模块

用一棵类型化命令树统一处理参数解析、补全、权限、帮助和错误反馈，不用再手工拆 `String[] args`。

| 模块名 | 制品 | 自动带入 |
| --- | --- | --- |
| `command` | `me.kzheart.klib:klib-command` | `core`、`lang`、`config` |

## 快速开始

```kotlin
klib {
    modules {
        command()
    }
}
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

```java
@Override
protected void setup() {
    CommandModule.install(this);
    commands().register(new CoinCommands());
}
```

```java
import me.kzheart.klib.command.annotation.Command;
import me.kzheart.klib.command.annotation.Description;
import me.kzheart.klib.command.annotation.Param;
import me.kzheart.klib.command.annotation.Permission;
import me.kzheart.klib.command.annotation.Route;

@Command(value = "coins", help = true)
@Permission("myplugin.coins")
public final class CoinCommands {
    @Route("give <target> <amount>")
    @Permission("myplugin.coins.give")
    @Description("向玩家发放金币")
    public void give(CommandSender sender,
            @Param("target") Player target,
            @Param("amount") int amount) {
        giveCoins(target, amount);
    }
}
```

效果：

- `/coins give <target> <amount>` 发放金币；
- `/coins` 和 `/coins help [page]` 显示按权限过滤的分页帮助。

命令只在代码里声明，**不要**再在 `plugin.yml` 里写同名命令。注解的完整规则见 [组件与注解](../annotations.md)。

## 两种声明方式

| 方式 | 写法 | 适合 |
| --- | --- | --- |
| 注解 | `commands().register(new A(), new B())` | 大多数业务命令 |
| 程序化 | `commands().register("name", root -> ...)` 或 `scope.command(...)` | 动态结构、自定义参数、需要精细控制节点 |

两种方式编译成同一套命令树，以下规则通用：

- 同根命令的多个处理类放在**同一次** `register(...)` 中合并，别名共享完整树。
- `MountedCommand.of(根命令, 处理器, 子命令, 别名...)` 把一个处理类的全部路由再挂到另一个根命令下。
- 注解支持参数注入、`@Permission`、`@Check`、`@Greedy`、`@Suggest`/`@Suggestions`（可读取前置参数），以及 `CommandCall.await` 主线程回调。
- 程序化可以平铺声明：`root.route("action start").argument(token).executes(handler)`，不用逐层嵌套 lambda。
- 领域参数用 `Arguments.contextual`（可读发送者和前置参数）；`Arguments.custom` 仍是两参数补全器。

### 程序化建树

需要命令错误和帮助复用语言文件时，先安装 Lang，再把它的管线传给命令模块：

```java
@Override
protected void setup() {
    Scope root = context().scope();
    LangRuntime lang = LangModule.install(
            root,
            getServer(),
            getDataFolder().toPath(),
            getClassLoader(),
            "zh_CN",
            null);

    CommandModule.install(
            root,
            BukkitCommandRegistrar.discover(this, "myplugin"),
            lang.pipeline());

    Arg<Player> target = Arguments.player("target");
    Arg<Integer> amount = Arguments.integer("amount", 1, 64);

    root.command("coins", command -> {
        command.description("金币管理")
                .permission("myplugin.coins");

        command.literal("give", give -> give
                .permission("myplugin.coins.give")
                .argument(target, targetNode -> targetNode
                        .argument(amount, amountNode -> amountNode
                                .executes(context -> giveCoins(
                                        context.get(target),
                                        context.get(amount).intValue())))));
    });
}
```

`Arg` 对象既是树节点，也是 `context.get(...)` 的类型化键，**必须复用建树时的同一个实例**。读取时不要重新调用 `Arguments.player("target")`。

### 按名读取参数

拿不到建树时的 `Arg` 实例时（处理器在别的类里，或参数被 `Arguments.optional(...)` 包装过），可以按名字读取：

```java
int amount = context.get("amount", Integer.class).intValue();
Optional<Object> raw = context.find("amount");
```

| 情况 | `get(name, type)` | `find(name)` |
| --- | --- | --- |
| 同名参数在路径上出现多次 | 取最深处的值 | 取最深处的值 |
| 本次解析没有这个参数 | 抛 `IllegalArgumentException` | `Optional.empty()` |
| 可选参数的默认值为 `null` | 返回 `null` | `Optional.empty()` |

名称取自 `Arguments.xxx(name, ...)`，已转成小写。优先按实例读取，因为类型在编译期就确定了。

## 安装选项

| 调用 | 说明 |
| --- | --- |
| `CommandModule.install(this)` | 最常用。自动选择注册方式，使用默认消息 |
| `CommandModule.install(this, errors)` | 同上，再加业务错误处理器 |
| `CommandModule.install(root, bridge)` | 显式注册器、内置消息、在线玩家解析和 Spigot 富文本输出 |
| `CommandModule.install(root, bridge, lang.pipeline())` | 命令消息走语言文件（推荐） |
| `CommandModule.install(scope, bridge, errors)` / `(scope, bridge, messages, errors)` | 显式注册器 + 错误处理器 |

高级适配可以自己传入 `PlayerResolver`、`RichTextSink` 和 `CommandMessages`。

`BukkitCommandRegistrar.discover(this, "myplugin")`：

- 显式传入所属插件，不依赖库和宿主共用 ClassLoader。`install(this)` 内部用的就是它。
- 省略插件参数的 `discover(prefix)` 只适用于 Klib 打包在所属插件 ClassLoader 里的情况。
- 字符串是旧 Bukkit 注册路径的命名空间前缀，建议用插件 ID 的小写形式。现代 Paper 的命名空间由实际插件名决定，这个参数不会改变它。

## 构建命令树

```java
command.description("说明")
        .permission("myplugin.use")
        .playerOnly()
        .executes(context -> run(context.sender()));
```

| 方法 | 作用 |
| --- | --- |
| `description` | Bukkit 元数据和帮助中的说明 |
| `permission` | 执行、帮助、补全都按它过滤 |
| `playerOnly` | 拒绝控制台和其他非玩家发送者 |
| `executes` | 参数在这个节点结束时执行的处理器 |
| `literal` | 添加固定单词 |
| `argument` | 添加类型化参数 |

一个节点可以同时有处理器和子节点，例如 `/arena` 显示摘要，`/arena join` 加入。

走到一个没有处理器、但还有可访问子节点的节点时：

- 停在根命令（如 `/coins`）：显示帮助第一页，结果状态为 `HELP`。
- 进入子命令后缺少参数（如 `/coins give`）：显示该节点的用法和用途，结果状态为 `INCOMPLETE`，用法行使用 `command.usage` 消息键：

  ```text
  用法 › /coins give <target> <amount>
  用途 › 发放金币
  ```

  有多条分支时逐行列出。分支超过一页（8 条），或发送者没有权限访问任何分支时，改为显示帮助第一页。

## 命令标签

- 命令名、literal 和参数名会统一转成小写，不能含空格。同一节点下不能有重名的 literal 或参数。
- 根名、别名和 literal 支持 Unicode 字母和数字，例如 `@Command(value = "mail", aliases = {"邮箱"})`、`@Route("领取")`。
- 中文别名与原命令共用权限、补全、客户端命令树和逻辑停用，不需要另写转发命令。
- 标点只能用 `_ . : -`，不能含空白、参数括号或控制符。

## 参数

### 内置参数

```java
Arg<Integer> count = Arguments.integer("count", 1, 64);
Arg<BigDecimal> price = Arguments.decimal(
        "price", BigDecimal.ZERO, new BigDecimal("9999.99"));
Arg<Boolean> enabled = Arguments.bool("enabled");
Arg<Mode> mode = Arguments.enumeration("mode", Mode.class);
Arg<Player> player = Arguments.player("player");
Arg<String> type = Arguments.choice("type", "mining", "garden");
Arg<String> id = Arguments.string("id");
Arg<String> reason = Arguments.greedyString("reason");
```

| 工厂 | 行为 |
| --- | --- |
| `integer` / `decimal` | 支持闭区间范围 |
| `bool` | 接受 `true/false`、`yes/no`、`on/off`、`1/0`，带补全 |
| `enumeration` | 大小写不敏感，补全为小写枚举名 |
| `player` | 只匹配名字完全一致的在线玩家 |
| `choice` | 大小写不敏感，返回声明时的规范值 |
| `string` | 消费一个 token，可自定义补全 |
| `greedyString` | 消费剩余全部文本，必须是路径最后一个节点 |

同一节点下可以放多个参数分支，按顺序第一个解析成功的生效。`string` 能接受任意单个 token，会挡住后面的同级参数，所以它之后不能再加同级参数分支。

### 可选参数

```java
Arg<Integer> amount = Arguments.optional(
        Arguments.integer("amount", 1, 64),
        Integer.valueOf(1));

command.argument(amount, node -> node.executes(context ->
        give(context.get(amount).intValue())));
```

- 输入缺少该参数、且路径能沿可选节点到达处理器时，`context.get(amount)` 返回默认值。
- 贪婪参数不能设为可选。
- 可选参数要放在必填参数之后。省略可选值时，只会沿可选节点继续补默认值，不会跳过它去匹配后面的必填节点。

### 自定义参数与补全

```java
Arg<UUID> playerId = Arguments.custom(
        "playerId",
        input -> UUID.fromString(input),
        (sender, prefix) -> knownIds(prefix));
```

- 解析器返回 `null` 或抛 `IllegalArgumentException`，会作为普通参数错误反馈给发送者。
- 补全器可以返回 `null`。结果会按当前前缀再过滤一次，并按不区分大小写的顺序排序。
- `Arguments.custom(...)` 是唯一的参数扩展点。`Arg` 和 `CommandArgument` 只用来声明字段类型，库外不能继承；自己实现 `CommandArgument` 的对象会被 `argument(...)` 拒绝。复杂解析逻辑写进 `ArgumentParser`。

## 权限、玩家限制与可见性

```java
command.literal("admin", admin -> admin
        .permission("myplugin.admin")
        .literal("reload", reload -> reload
                .executes(context -> reload())));
```

- `permission` 和 `playerOnly` 作用于声明它们的节点，执行时逐层检查。帮助和 Tab 补全会隐藏不可访问的分支。
- 没有权限的 literal 不会挡住同级参数，会优先尝试发送者有权访问的参数分支。
- 服务端执行时一定会重新检查权限，不要只靠隐藏补全来保护敏感命令。涉及具体对象的授权仍要在业务处理器里自己校验。

## 自动帮助与主命令入口

注解方式：`@Command(value = "mail", aliases = {"m"}, help = true)` 会根据已注册路由生成 `help [page]`。

- `help` 默认是 `false`。同根处理类合并后只安装一次，别名共用。
- `@Description` 提供每条路由的用途。权限和 `Player` 发送者限制决定哪些命令可见。
- **主命令默认显示帮助**：不要声明 `@Route("")`，GUI 单独用 `@Route("open")`。旧写法 `@Route({"", "open"})` 要去掉空字符串。
- 已有的根处理器和显式声明的 `help` 子命令会保留。

完整注解示例见 [组件与注解](../annotations.md#自动帮助与主命令入口)。

程序化方式同样把 GUI 放到独立子命令：

```java
commands().register("mail", root -> {
    root.description("邮件命令").permission("myplugin.mail");
    CommandBuiltins.create().install(root);
    root.route("open")
            .description("打开邮件界面")
            .playerOnly()
            .executes(context -> openMailbox((Player) context.sender()));
});
```

| 输入 | 结果 |
| --- | --- |
| `/mail` | 帮助第一页（根节点没有 `executes`） |
| `/mail help 1` | 指定页码 |
| `/mail open` | 打开界面 |

如果根节点或不带参数就能匹配的默认分支挂了 GUI 处理器，`/mail` 仍会执行它。`CommandBuiltins.create().install(root)` 只安装帮助，不带 reload 和 debug。

## 帮助样式

| 预设 | 样式 |
| --- | --- |
| `ELEGANT`（默认） | 典雅：标题 + 单行用途 |
| `COMPACT` | 简洁：紧凑目录 |
| `PANEL` | 面板：两行用途 + 边线 |
| `CLASSIC` | 经典：沿用 `CommandMessages` 的标题和分页文案 |

所有预设都支持点击预填命令、悬停显示完整用途、真实分页命令和权限过滤，不依赖资源包。

```java
import me.kzheart.klib.command.api.CommandHelpStyle;

commands().register("coins", root -> {
    root.helpStyle(CommandHelpStyle.preset(CommandHelpStyle.Preset.PANEL));
    CommandBuiltins.create().install(root);
    // 各业务路由继续声明 description(...)，参数节点可继承祖先说明。
});
```

设置的位置，优先级从高到低：

1. 根或路由上的 `helpStyle(...)`；`helpStyle(() -> currentStyle)` 每次动态读取，配置重载后不用重建命令树。
2. 全插件默认：`context().scope().registerCapability(CommandHelpStyle.class, style)`，注解命令同样继承。

### 自定义模板

`CommandHelpStyle.builder(preset)` 可以定制 `header`、`entry`、`description`（条目第二行）、`usage`（缺参数提示）、`group`、`previous`、`next`、`footer`、`hover`、三种命令层级的 RGB 颜色、`pageSize` 和 `clearLines`。

| 占位符 | 用于 |
| --- | --- |
| `{command}` `{page}` `{pages}` `{count}` `{summary}` | 标题、页脚 |
| `{usage}` `{description}` `{index}` | 条目 |
| `{category}` | 分组 |

- 模板支持 MiniMessage。参数、说明和当前值在解析后按纯文本插入，不会变成点击标签。
- 自定义条目和缺参数提示必须同时保留用法和说明的占位符。没写 description 时显示“暂无说明”，不会根据名字编造用途。

也可以从业务插件自己的 YAML 解析：`CommandHelpStyles.parse(section)`。

```yaml
preset: elegant # elegant / compact / panel / classic / custom
page-size: 7
clear-lines: 0
custom: # 仅 preset: custom 时应用，不影响其他预设
  header: '<gold><bold>/{command}</bold> <gray>· 命令指南 {page}/{pages}'
  entry: '  <gold>› {usage} <dark_gray>— <gray>{description}'
  usage: |
    <gold>用法 <dark_gray>› {usage}
    <gray>用途 <dark_gray>› {description}
  footer: '<dark_gray>点击预填 · 悬停查看用途 · {page}/{pages}'
  previous: '<gray>[ ← 上一页 ]'
  next: '<gold>[ 下一页 → ]'
  command-color: '#e8b04a'
  literal-color: '#e7d9c1'
  argument-color: '#a39a8c'
```

- 多行模板用双引号里的 `\n` 或块文本 `|`。单引号会保留字面的 `\n`。
- Klib 不会创建或改写业务配置。值有错误时抛解析异常，交给业务的 reload 流程处理。

### 翻页与汇总帮助

<details>
<summary>展开：翻页路由与多命令汇总帮助 HelpRenderer.renderEntries</summary>

- 普通命令树的翻页按钮执行 `/<根命令> help <页码>`，需要用 `CommandBuiltins` 或 `@Command(help = true)` 安装这条路由。自己调用 sendHelp 时，页容量要和这条路由一致。
- 多个独立短命令的汇总帮助用 `HelpRenderer.renderEntries(sender, command, summary, entries, page, style, navigation)`：
  - `entries` 传已经按权限过滤过的 `CommandHelpEntry`，usage 要带 `/`；
  - `navigation` 返回实际注册的翻页命令，例如 `number -> "/help " + number`；
  - 支持 category 和额外的悬停说明。不要把 `/<根> help` 当成所有业务帮助的通用路由。

</details>

## 内置命令：help / reload / debug

```java
AtomicBoolean debug = new AtomicBoolean();

CommandBuiltins.standardAsync(
        "myplugin.admin",
        config::reloadAsync,
        debug::get,
        debug::set)
        .install(command);
```

| 子命令 | 行为 |
| --- | --- |
| `help [page]` | 分页显示发送者能访问的命令 |
| `reload` | 异步重载完成后才发送成功消息 |
| `debug` | 切换调用方自己维护的调试状态 |

- 同步重载用 `CommandBuiltins.standard(permission, reloadAction, ...)`。
- 只要部分内置项，用 `CommandBuiltins.create()` 挑选；`help(false, null)` 关闭帮助。
- `reload` 和 `debug` 属于敏感操作，**权限参数不要传 `null`**。确实要所有人可用时，显式传 `CommandBuiltins.PERMISSION_NONE`。
- 不带权限参数的旧 `standard(...)` 默认要求 `klib.command.builtin.admin`，新代码不要用。

重载失败时，同步和异步都会把完整堆栈写进日志：

- 异常本身或 cause 链上任意一层属于 Config 的 `ConfigException` 时，发送者会收到 `command.builtin.reload.failure` 消息，`{reason}` 是异常自带的定位信息：

  ```text
  重新加载失败: config.yml:limits.max: 需要整数
  ```

  原因文本会去掉旧式颜色码，最长 200 字符。在 MiniMessage 管线下，占位符的值在解析之后才插入，不会被当成标签。
- 其他异常交给业务的 `CommandErrorHandler`，没配置时只记录日志。
- 只要配置了业务错误处理器，就由它接管，不再使用上面的配置异常默认提示。

## 错误反馈

处理器抛出的普通异常会被 Klib 捕获、写进 `KLogger`，结果为 `FAILED`。默认**不**给玩家发统一的“命令执行出错”，也不回显异常消息，提示内容由业务决定：

```java
import me.kzheart.klib.command.CommandModule;
import me.kzheart.klib.command.api.CommandErrorHandler;

CommandErrorHandler errors = (call, failure) -> {
    // Klib 已记录完整异常；按业务类型选择可展示的信息，或不发送消息。
    call.sender().sendMessage("操作未完成，请查看本插件的状态提示");
};
CommandModule.install(this, errors);
commands().register("shop", root -> root
        .errorHandler((call, failure) -> call.sender().sendMessage("商店操作未完成"))
        .executes(call -> call.sender().sendMessage("商店入口")));
```

- 处理器的查找顺序：最近的路由 → 根命令 → 模块级策略。注解命令使用模块级策略。
- 错误处理器可以发消息、关界面、转交业务反馈，也可以什么都不做。
- `Error` 在记录和反馈之后仍会继续抛出。错误处理器自己抛出的普通异常只记录，不会递归处理，也不会补发固定文案。
- 参数解析、权限、缺参数和用法提示走命令规则，不经过错误处理器。
- 业务主动抛出的 `CommandRejectedException` 会原样展示它的拒绝消息。
- `CommandCall.await(stage, success, failure)` 的失败回调由调用方自己提供。内置的异步 reload 使用原命令上下文的错误策略。

## 消息、富文本与诊断

- 推荐给 `CommandModule.install` 传入 `LangRuntime.pipeline()`。无权限、参数错误、内部错误、帮助和内置命令的文本都使用 `command.*` 消息键，并会随语言文件一起重载。
- 富文本发送方法从公开平台接口或父类中查找，支持 Paper 的匿名 Spigot 实现；平台没有组件 API 时，才退回到带颜色码的纯文本。
- `CommandDispatcher` 自带一个轻量的 `DiagnosticSource`：只报告根命令名、调用次数、失败次数和最近一次失败的异常类型，不记录发送者、参数或玩家身份。
- 想把命令状态附到 Remote Incident 里，需要自己注册 `new KlibDiagnosticContributor(dispatcher)`。Command 不依赖 Remote，也不会自动上传任何数据。

## 启动注册、配置重载与逻辑停用

**核心规则：命令只在插件启动时声明一次。重载配置不会重建命令树。**

- 使用 `KPlugin` 时，在 `setup()` 中声明，不要覆盖 `onEnable`。普通 `JavaPlugin` 的高级集成在 `onEnable` 中安装和声明。
- 注册方式按服务端能力自动选择：

  | 服务端 | 注册方式 |
  | --- | --- |
  | 有公开命令生命周期 API 的 Paper | 在 `onEnable` 阶段通过 `LifecycleEvents.COMMANDS` 安装 `BasicCommand`。何时重建和发布命令由服务端决定，不改动正在使用的 Brigadier 树 |
  | 其他 Bukkit/Paper | 启动阶段注册到 `CommandMap`。执行和补全都交给 Klib 分发器 |

- 现代 API 通过运行时能力检测接入，公共制品仍然是 Java 8 字节码，不硬依赖 Java 21 或新版 Paper 类。

**配置重载**只更新处理器读取的配置、语言和业务状态：

- 不要用 `root.rebuild()`，也不要关掉再重建命令作用域来实现重载。
- 需要重建监听器、任务等资源时，放进一个不含命令的独立子作用域。
- 修改根名、别名或路由后，需要重启服务端。

**逻辑停用**：`Scope.command` 返回的 `CommandRegistration` 归所在作用域所有。

- 关闭注册句柄或作用域后，执行、补全和权限检查立即停用，之后也不会重新启用旧处理器。
- 物理命令节点可能还留在服务端或玩家已收到的命令树里，直到服务端重建生命周期或重启。
- Klib 不支持运行时新增根命令、立即物理注销，也不会为此强制刷新在线玩家的命令树。

子作用域可以管理命令的逻辑存活期，但也必须在启动时创建和声明：

```java
root.scope("arena-commands", arena -> {
    arena.command("arena", command -> configureArenaCommand(command));
});
```

关闭它会立即停用 `/arena`，但不能在运行时再重新注册。功能需要随配置开关时，保留启动时的声明，在业务入口里检查开关状态即可。

### 客户端展示树与服务端执行

<details>
<summary>展开：Brigadier 客户端树如何投影、与服务端执行的关系</summary>

- Brigadier 集成只在 `AsyncPlayerSendCommandsEvent` 的同步回调里，处理事件给出的当前玩家命令树副本。投影时逐个节点检查权限和玩家限制。
- 不修改服务端共享的根树，不在异步回调里查询 Bukkit 权限，也不接管 Paper 内部的构建线程或队列。
- 服务端始终用原始参数调用 Klib 分发器，因此保留中文命令、带冒号的参数（如 `zap:ID`）、本地化错误和不区分大小写的 literal 匹配。
- 客户端树只用于显示用法和补全，不能代替服务端的解析和权限校验。作用域关闭后，即使客户端还显示旧节点，执行和补全也已停用。

</details>

### 显式覆盖未命名空间的标签

<details>
<summary>展开：用 REPLACE_UNQUALIFIED 接管已有裸命令</summary>

默认策略 `CommandRegistrationPolicy.REJECT`：保留已有绑定，根名或别名冲突时拒绝注册；只注册上带命名空间的入口不算成功。确实需要在启动时接管已有的裸命令时：

```java
CommandModule.install(context().scope(), BukkitCommandRegistrar.discover(
        this, getName().toLowerCase(Locale.ROOT), CommandRegistrationPolicy.REPLACE_UNQUALIFIED));
```

- `REPLACE_UNQUALIFIED` 只接管不带命名空间的标签，根名和每个别名分别判断冲突；不会覆盖其他插件的 `namespace:label`。
- 关闭替换后的绑定只保证逻辑停用。被覆盖的裸标签不会恢复，多个插件覆盖同名命令时也没有恢复栈。需要恢复原命令时，删掉冲突的声明并重启。
- 注册失败时不会启用这次的绑定，但一次注册并不是能恢复任意第三方标签的全局事务。
- 同一插件里，同一个标签不能同时交给 Klib 和其他注册器管理（包括 `plugin.yml` 里的重复声明）。不要直接改 `knownCommands` 或服务端 Brigadier 根树，也不要用延时 tick 模拟运行时替换。

</details>

## 线程与生命周期

- 安装和声明命令必须在启动阶段、在主线程执行。从异步线程注册会失败。
- 注册句柄可以在任何线程关闭。关闭后立即逻辑停用，不等待 Paper 构建器，也不保证物理节点马上清除。
- 命令处理器通常运行在主线程。不要在里面做数据库、网络或大文件 I/O，改用 `scope.async(...).thenSync(...)`。
- 异步任务完成后，必须回到主线程才能修改玩家、世界或背包。
- 给内置异步重载传自定义 `CompletionStage` 时，要确保它完成时可以安全发送 Bukkit 消息。在 `KPlugin` 环境下，Config 的 `reloadAsync()` 会等主线程上的监听器执行完才完成。
- 命令能力、注册和语言管线都只能在所属作用域还打开时使用。

## 常见坑

- `context.get(arg)` 按对象身份匹配，必须用建树时的同一个 `Arg` 实例。`Arguments.optional(...)` 返回的是新实例，读取时要用包装后的实例，或者改用 `context.get(name, type)`。
- `greedyString` 必须在路径末尾，后面不能再加 literal 或参数。
- literal 优先于同级参数。如果具体的值和命令词可能冲突，要调整树结构消除歧义。
- 命令模块只解析 Bukkit 交给它的 token，不处理 shell 风格的引号和转义。
- 缺少生命周期 API 时退回启动期 Bukkit 注册；缺少客户端事件时保留基本的执行和 Tab 补全。
- 同一个标签只能交给一个注册入口管理。不要把 Klib 和 `plugin.yml`、其他命令框架或直接注册器混在一起用。

## 相关页面

- [组件与注解](../annotations.md)：注解命令的完整规则
- [Core](core.md)：作用域和异步任务
- [Config](config.md)：可重载的类型化配置
- [Lang](lang.md)：命令消息、帮助和富文本
