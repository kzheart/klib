# Command 模块

注解命令通过 `@Command(value = "mail", help = true)` 安装自动分页帮助；主命令和 GUI 的入口声明见下文的“自动帮助与主命令入口”。

状态：稳定公开模块
模块名：`command`
制品：`me.kzheart.klib:klib-command`

`klib-command` 用类型化树描述 Bukkit 命令，统一完成参数解析、补全、权限、玩家限制、帮助、错误定位和作用域关闭后的逻辑停用。命令在插件启动时声明；支持公开命令生命周期 API 的 Paper 使用该 API 安装执行入口，并在发送客户端命令树时按玩家权限生成 Brigadier 展示树。

## 注解与平铺声明

`commands().register(new PlayerCommands(), new AdminCommands())` 将 `@Command`、`@Route` 方法编译为同一套命令树。
支持参数注入、Permission、Check、Greedy、带前置参数上下文的 Suggest/Suggestions，以及 CommandCall.await 主线程回调。
同根的处理器在一次调用中合并，别名共享完整命令树；`MountedCommand.of(根命令, 处理器, 子命令, 别名...)` 可把已有处理器的全部路由再挂到另一根命令的子命令下。所有声明在启动阶段完成，失败绑定不启用，命令随所属作用域关闭立即逻辑停用；物理节点不保证同时消失。

程序化声明可使用 `root.route("action start").argument(token).executes(handler)`，无需按层嵌套 lambda。
领域参数解析用 `Arguments.contextual`；原有 Arguments.custom 保持两参数补全器语义。
本页原有按树声明方式仍可用；新 API 的完整用法、权限合并和限制见 [组件与注解](../annotations.md)。

## 何时使用

适合以下场景：

- 不想手工拆分 `String[] args`；
- 需要整数范围、枚举、在线玩家、可选值或贪婪文本等类型化参数；
- 希望补全、帮助和执行共享同一棵命令树；
- 希望配置重载后命令读取最新业务状态，而不用重新注册命令；
- 希望命令错误与业务消息共用 Lang 语言文件。

## 接入

推荐通过 Klib Gradle 插件选择模块：

```kotlin
klib {
    modules {
        command()
    }
}
```

`command` 会自动带入 `core`、`lang` 和 `config`。

直接依赖的高级用法：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-command:<klib-version>")
}
```

直接依赖时需自行打包、重定位并提供 Bukkit API；推荐的 Gradle 插件会自动处理 Klib 模块闭包。

## 快速开始

最常用的写法是注解声明。在 `KPlugin.setup()` 中安装命令能力，再用 `commands().register(...)` 一次声明处理类；这个初始化入口由 `JavaPlugin.onEnable` 阶段调用：

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

`CommandModule.install(this)` 发现当前服务端支持的注册方式，并使用默认命令消息；命令来自代码声明，不需要在 `plugin.yml` 重复声明相同标签。注解的完整规则见
[组件与注解](../annotations.md)。

需要让命令错误与帮助复用语言文件，或在启动时以程序化方式构建命令树时，先安装语言能力，再通过 `Scope.command` 声明：

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

这里注册的是 `/coins give <target> <amount>`。参数对象既描述树节点，也是 `context.get(...)` 的类型化键，因此必须保存并复用同一个实例；不要在读取时重新调用 `Arguments.player("target")`。

### 按名读取参数

拿不到建树时的 `Arg` 实例（例如处理器写在另一个类里，或参数被 `Arguments.optional(...)` 包装过）时，可以按参数名读取：

```java
int amount = context.get("amount", Integer.class).intValue();
Optional<Object> raw = context.find("amount");
```

- 名称取自 `Arguments.xxx(name, ...)` 声明的名字，已规范化为小写；
- 同名参数出现在同一条解析路径的多个层级时，返回最深处的值；
- 名称未出现在本次解析中，`get(String, Class)` 抛 `IllegalArgumentException`，`find` 返回 `Optional.empty()`；
- 可选参数默认值为 `null` 时，`find` 同样返回 `Optional.empty()`，`get(String, Class)` 返回 `null`。

按实例读取仍是推荐写法，它在编译期就带上类型；按名读取用于跨类传递和包装参数场景。

`BukkitCommandRegistrar.discover(this, "myplugin")` 显式传入所属插件，避免依赖库与宿主共享 ClassLoader；`CommandModule.install(this)` 已自动使用这个入口。省略插件参数的 `discover(prefix)` 仅用于 Klib 已打包在所属插件 ClassLoader 内的情况。字符串参数是旧 Bukkit 注册路径使用的命名空间前缀，建议使用插件 ID 的小写稳定形式。支持公开生命周期 API 的 Paper 使用实际插件名对应的命名空间，这个参数不会替换 Paper 的插件命名空间。

## 构建命令树

每个节点都可以配置：

```java
command.description("说明")
        .permission("myplugin.use")
        .playerOnly()
        .executes(context -> run(context.sender()));
```

- `description` 用于 Bukkit 元数据和帮助；
- `permission` 在执行、帮助与补全中都会过滤；
- `playerOnly` 拒绝控制台及其他非玩家发送者；
- `executes` 指定参数在该节点结束时执行的处理器；
- `literal` 添加固定单词；
- `argument` 添加类型化参数。

节点可以同时拥有处理器和子节点，例如 `/arena` 显示摘要，而 `/arena join` 执行加入操作。

到达一个没有处理器但仍有可访问子节点的节点时：

- 停在根命令上（如只输入 `/coins`）显示命令帮助第一页，结果状态为 `HELP`；
- 已经进入子命令却缺少后续参数（如 `/coins give`）只反馈该节点的用法，结果状态为 `INCOMPLETE`：

  ```text
  用法: /coins give <target> <amount>
  ```

  该节点下有多条可达分支时逐行列出；分支超过一页（8 条）或发送者无权访问任何分支时，退回帮助第一页。用法行使用 `command.usage` 消息键。

命令名、literal 和参数名都会规范化为小写单词，不能包含空格。同一节点下不能出现重名 literal 或重名参数。

## 参数

### 内置参数

常用工厂包括：

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

- `integer` 和 `decimal` 支持闭区间范围；
- `bool` 接受 `true/false`、`yes/no`、`on/off`、`1/0` 并提供补全；
- `enumeration` 大小写不敏感，补全使用小写枚举名；
- `player` 只解析精确匹配的在线玩家；
- `choice` 大小写不敏感并返回声明时的规范值；
- `string` 消费一个 token，可选自定义补全；
- `greedyString` 消费剩余全部文本，必须是路径中的最后一个节点。

同一节点下可以放多个具体参数类型作为分支，但第一个能成功解析的分支会胜出。`string` 接受任意单 token，会遮蔽后续同级参数，因此它之后不能再声明同级参数分支。

### 可选参数

```java
Arg<Integer> amount = Arguments.optional(
        Arguments.integer("amount", 1, 64),
        Integer.valueOf(1));

command.argument(amount, node -> node.executes(context ->
        give(context.get(amount).intValue())));
```

输入缺少该参数且路径可以沿可选节点到达处理器时，`context.get(amount)` 返回默认值。贪婪参数不能设为可选。

可选参数应放在必填参数之后。虽然树允许在可选节点后继续添加节点，但命令省略可选值时，只会沿可访问的可选参数继续补默认值，不会跳过它去匹配一个必填节点。

### 自定义参数与补全

```java
Arg<UUID> playerId = Arguments.custom(
        "playerId",
        input -> UUID.fromString(input),
        (sender, prefix) -> knownIds(prefix));
```

解析器返回 `null` 或抛出 `IllegalArgumentException` 会作为普通参数错误反馈给发送者。补全器可以返回 `null`，结果会按当前前缀再次过滤并按大小写不敏感顺序排序。

`Arguments.custom(...)` 是唯一的参数扩展点。`CommandArgument` 与 `Arg` 虽然公开，但只用于声明字段类型：`Arg` 的构造器和解析方法都是包内可见，库外无法继承，自行实现 `CommandArgument` 的对象也会被 `argument(...)` 拒绝。需要复杂解析时把逻辑写进 `ArgumentParser`，而不是新建实现类。

## 权限、玩家限制与可见性

权限和 `playerOnly` 属于声明它们的节点。执行时会逐层检查；帮助和 Tab 补全也会隐藏不可访问分支。

```java
command.literal("admin", admin -> admin
        .permission("myplugin.admin")
        .literal("reload", reload -> reload
                .executes(context -> reload())));
```

如果一个无权限 literal 与同级参数可能匹配同一输入，无权限 literal 不会遮蔽该参数；模块会先尝试发送者有权访问的参数分支。

不要只依赖客户端补全隐藏敏感命令。服务端执行路径始终会重新检查权限，但业务处理器内部涉及具体对象授权时仍需自行校验。

## 自动帮助与主命令入口

注解入口设置 `@Command(value = "mail", aliases = {"m"}, help = true)` 后，会根据已注册路由生成 `help [page]`。`help` 默认是 `false`；同根处理类合并完成后安装一次，别名共享帮助。`@Description` 显示路由用途，权限与 `Player` 发送者限制决定哪些业务命令可见。

主命令默认显示帮助时，不声明 `@Route("")`；GUI 单独声明为 `@Route("open")`。已有的 `@Route({"", "open"})` 应移除空字符串，变为 `@Route("open")`。自动帮助会保留已有根处理器，以及显式声明的 `help` 子命令。完整注解示例见 [组件与注解](../annotations.md#自动帮助与主命令入口)。

程序化入口也应把 GUI 放到独立子命令：

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

此处根节点没有 `executes`：`/mail` 显示帮助第一页，`/mail help 1` 显式指定页码，`/mail open` 打开界面。若根节点或无参数可匹配的默认分支保留 GUI 处理器，裸主命令仍会执行该处理器。`CommandBuiltins.create().install(root)` 只安装帮助，不会附带配置重载或调试操作。

## 内置帮助、重载与调试命令

推荐显式提供管理员权限，并让配置重载等待监听器完成：

```java
AtomicBoolean debug = new AtomicBoolean();

CommandBuiltins.standardAsync(
        "myplugin.admin",
        config::reloadAsync,
        debug::get,
        debug::set)
        .install(command);
```

这会安装：

- `help [page]`：分页显示发送者可访问的命令；
- `reload`：异步完成后才发送成功消息；
- `debug`：切换调用方维护的调试状态。

同步重载可以使用 `CommandBuiltins.standard(permission, reloadAction, ...)`。

重载失败时，同步与异步两条路径都会记录完整堆栈到日志。如果失败异常（或其 cause 链上任意一层）属于 Config 模块的 `ConfigException` 族，发送者还会收到 `command.builtin.reload.failure` 消息，其中 `{reason}` 是异常自带的定位信息，例如：

```text
重新加载失败: config.yml:limits.max: 需要整数
```

原因文本会去掉 legacy 颜色码并限制在 200 字符内；在 MiniMessage 管线下占位符值在解析之后插入，不会被当作标签。其他异常走业务 `CommandErrorHandler`，未配置时只记录。配置异常的内置定位提示仅在未指定业务错误处理器时使用；提供错误处理器即可接管。

`reload` 和 `debug` 属于敏感操作。权限参数不要传 `null`；确实希望任何人都可用时，必须显式传 `CommandBuiltins.PERMISSION_NONE`。未带权限参数的旧 `standard(...)` 重载会默认要求 `klib.command.builtin.admin`，不应在新代码中使用。

也可以通过 `CommandBuiltins.create()` 只选择部分内置项，或用 `help(false, null)` 关闭帮助。

## 消息与输出

推荐把 `LangRuntime.pipeline()` 传给 `CommandModule.install`。命令模块使用 `command.*` 消息键解析无权限、参数错误、内部错误、帮助和内置命令文本；这些键会随语言文件重载。

```java
CommandModule.install(
        root,
        BukkitCommandRegistrar.discover(this, "myplugin"),
        lang.pipeline());
```

不需要自定义语言时，可以使用简化安装：

```java
CommandModule.install(root, BukkitCommandRegistrar.discover(this, "myplugin"));
```

此形式使用内置消息、在线玩家解析器和 Spigot 富文本输出。高级适配场景可以传入自己的 `PlayerResolver`、`RichTextSink` 与 `CommandMessages`。

处理器抛出的普通异常由 Klib 捕获并记录到 `KLogger`，返回 `FAILED`；默认不发送统一的“命令执行出错”，也不自动回显异常消息。业务插件可在模块安装时提供 `CommandErrorHandler`，或在根/路由上设置 `errorHandler(...)`；优先使用最近路由的处理器，其次根处理器，最后模块策略。处理器可以发送自定义消息、关闭界面、转交业务反馈或保持静默。`Error` 在记录及业务反馈后仍继续抛出；错误处理器本身抛普通异常只记录，不递归调用或再补一条固定文案。

`CommandDispatcher` 同时提供轻量 `DiagnosticSource`：只报告根命令名、调用次数、失败次数和最近失败的
异常类型，不记录发送者、命令参数或玩家身份。需要把命令状态附到 Remote Incident 时，由开发者显式注册
`new KlibDiagnosticContributor(dispatcher)`；Command 模块本身不依赖 Remote，也不会自动上传数据。

## 启动注册、配置重载与逻辑停用

所有根命令、别名和路由都在插件启动时声明。使用 `KPlugin` 时放在 `setup()`，不要覆盖框架管理的 `onEnable`；使用普通 `JavaPlugin` 的高级集成则在 `onEnable` 中安装和声明。注册入口会按当前服务端能力选择路径：

- 暴露公开命令生命周期 API 的 Paper：在 `JavaPlugin.onEnable` 阶段通过 `LifecycleEvents.COMMANDS` 安装 `BasicCommand` 原始参数执行入口。服务端决定何时重建和重新发布命令注册，不直接修改正在使用的服务端 Brigadier 树。
- 没有该 API 的 Bukkit/Paper：保留启动阶段的 `CommandMap` 注册，执行和 Tab 补全交给同一套 Klib 分发器。

公开 API 通过宿主能力发现接入，Klib 的公共制品仍保持 Java 8 字节码与 API 边界，不硬依赖 Java 21 或现代 Paper API 类。目标服务端自身需要的 Java 版本由服务端决定。

配置重载只更新命令处理器读取的配置、语言和业务状态，不重建命令树，也不通过 `root.rebuild()` 或关闭再创建命令作用域来实现。需要重建监听器、任务或其他业务资源时，将它们放在不含命令声明的独立子作用域；启动时声明的处理器从稳定服务入口读取最新状态。调整根名、别名或路由声明后应重启插件所在服务端。

`Scope.command` 返回的 `CommandRegistration` 归传入作用域持有。关闭注册句柄或其作用域会立即禁止该绑定的执行、补全和权限检查；关闭后不会重新启用旧处理器。这个保证是逻辑停用：物理命令节点可能仍留在服务端或玩家已收到的命令树里，直到服务端生命周期重建或重启才消失。Klib 不承诺运行时新增根命令、即时物理注销，也不会为了关闭作用域强制刷新在线玩家的命令树。

仍可用子作用域管理命令的逻辑存活期，但必须在启动时创建并声明：

```java
root.scope("arena-commands", arena -> {
    arena.command("arena", command -> configureArenaCommand(command));
});
```

关闭这个作用域会立即停用 `/arena`，不等于支持运行时重新注册它。对于可随配置启停的功能，通常应保留启动时的命令声明，并在业务入口检查功能状态。

### 客户端展示树与服务端执行

Brigadier 集成只在 `AsyncPlayerSendCommandsEvent` 的同步回调中处理事件提供的当前玩家树副本。投影时逐节点检查权限与玩家限制，不修改服务端共享根树，不在异步回调中查询 Bukkit 权限，也不接管 Paper 的内部构建线程或队列。

服务端始终通过原始命令参数调用 Klib 分发器，保留中文命令、含冒号参数（如 `zap:ID`）、本地化错误和大小写不敏感的 literal 匹配。客户端树用于显示用法和补全，不能替代服务端的参数解析与权限校验。关闭作用域后，即使客户端还显示旧节点，执行入口和补全入口也已停用。

### 显式覆盖未命名空间的标签

默认 `CommandRegistrationPolicy.REJECT` 保留已有绑定并拒绝根名或别名冲突。确实需要在启动时接管已有裸命令时，显式选择公开注册策略：

```java
CommandModule.install(context().scope(), BukkitCommandRegistrar.discover(
        this, getName().toLowerCase(Locale.ROOT), CommandRegistrationPolicy.REPLACE_UNQUALIFIED));
```

`REPLACE_UNQUALIFIED` 只接管不含命名空间的标签，根名和声明的别名各自参与冲突判断；不会覆盖其他插件的 `namespace:label` 入口。现代 Paper 的命名空间仍由实际插件名决定，`discover` 的前缀只用于旧 Bukkit 路径。

关闭替换后的绑定只保证立即逻辑停用，不保证恢复被覆盖的裸标签，也不提供多个插件覆盖同名命令的恢复栈。不要依赖关闭顺序重新交还标签；需要恢复原命令时应移除冲突声明并重启服务端。注册失败不启用失败绑定，但不应把一次注册当成可恢复任意第三方标签的全局事务。

同一插件内不得让 Klib 与其他注册器同时管理相同标签，包括重复的 `plugin.yml` 命令声明。不要直接修改 `knownCommands`、服务端 Brigadier 根树，或用固定 tick 延时模拟运行时替换。

### 业务决定异常反馈

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

使用显式注册器时可调用 `CommandModule.install(scope, bridge, errors)`，共享语言管线时使用 `install(scope, bridge, messages, errors)`。参数解析、权限、缺参数及用法仍由命令规则处理，不进入业务处理器；业务显式抛出的 `CommandRejectedException` 保留其选择的拒绝消息。注解命令使用模块级策略；`CommandCall.await(stage, success, failure)` 的异步失败回调本来就由调用方提供，保持该契约。内置异步 reload 同样使用原命令上下文的策略。

## 生命周期与线程约束

- 安装与命令声明必须在插件启动阶段的服务器主线程执行；从异步线程调用注册会失败。
- 注册句柄允许从其他线程关闭；关闭立即逻辑停用绑定，不等待 Paper 构建器，也不保证物理节点立即清理。
- Bukkit 命令处理器通常运行在主线程。处理器中不要执行数据库、网络或大文件 I/O；使用 `scope.async(...).thenSync(...)`。
- 异步工作完成后，只有回到主线程才能修改玩家、世界或背包。
- 自定义 `CompletionStage` 用于内置异步重载时，应确保完成回调能够安全发送 Bukkit 消息；Config 的 `reloadAsync()` 在 `KPlugin` 环境中会在主线程监听器完成后结束。
- 命令能力、注册与语言管线都必须在所属作用域仍打开时使用；配置重载不重新声明命令。

## 注意事项

- `context.get(arg)` 按对象身份匹配，必须使用建树时的同一 `Arg` 实例；`Arguments.optional(...)` 返回的是新实例，读取时用包装后的实例或改用 `context.get(name, type)`。
- `greedyString` 必须位于路径末尾，之后不能添加 literal 或参数。
- literal 优先于同级参数；具体值与命令词冲突时，应调整树结构避免歧义。
- 命令模块只解析 Bukkit 交给它的命令 token，不负责 shell 风格引号或转义。
- 公开生命周期 API 与 Brigadier 客户端投影按宿主能力发现；缺少生命周期 API 时使用启动期 Bukkit 注册，缺少客户端事件时保留基础执行与 Tab 补全。
- 默认拒绝根名或别名冲突，不以“只剩命名空间入口”作为注册成功；仅在确实需要接管裸标签时选择 `REPLACE_UNQUALIFIED`。
- 同一插件的同一个标签只能由一个注册入口管理，不要将 Klib 与 `plugin.yml`、其他命令框架或直接注册器混用。

## 相关模块

- [Core](core.md)：命令注册的作用域和异步任务。
- [Config](config.md)：可重载类型化配置。
- [Lang](lang.md)：命令消息、帮助和富文本输出。
- 完整命令树的组成方式见本页“构建命令树”。

## Unicode 命令标签

注解命令根名、别名和字面量支持 Unicode 字母与数字，例如 `@Command(value="mail", aliases={"邮箱"})`
和 `@Route("领取")`。中文别名与原命令共用权限、补全、客户端命令树与作用域逻辑停用，不需要另写转发命令。
标签仍不得包含空白、参数括号或其他控制符；标点范围为 `_ . : -`。
