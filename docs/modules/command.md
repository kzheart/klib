# Command 模块

状态：稳定公开模块
模块名：`command`
制品：`me.kzheart.klib:klib-command`

`klib-command` 用类型化树描述 Bukkit 命令，统一完成参数解析、补全、权限、玩家限制、帮助、错误定位和作用域注销。在支持的服务端上，它还会尽力同步 Brigadier 客户端命令树。

## 何时使用

适合以下场景：

- 不想手工拆分 `String[] args`；
- 需要整数范围、枚举、在线玩家、可选值或贪婪文本等类型化参数；
- 希望补全、帮助和执行共享同一棵命令树；
- 命令应在插件重载时完整注销并重新注册；
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

先安装语言与命令能力，再通过 `Scope.command` 注册：

```java
@Override
protected void setup(Scope root) {
    LangRuntime lang = LangModule.install(
            root,
            getServer(),
            getDataFolder().toPath(),
            getClassLoader(),
            "zh_CN",
            null);

    CommandModule.install(
            root,
            BukkitCommandRegistrar.discover("myplugin"),
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

`BukkitCommandRegistrar.discover("myplugin")` 的参数是 Bukkit 命令冲突时使用的命名空间前缀，建议使用插件 ID 的小写稳定形式。

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

原因文本会去掉 legacy 颜色码并限制在 200 字符内；在 MiniMessage 管线下占位符值在解析之后插入，不会被当作标签。其他异常仍显示通用的 `command.internal-error`。

`reload` 和 `debug` 属于敏感操作。权限参数不要传 `null`；确实希望任何人都可用时，必须显式传 `CommandBuiltins.PERMISSION_NONE`。未带权限参数的旧 `standard(...)` 重载会默认要求 `klib.command.builtin.admin`，不应在新代码中使用。

也可以通过 `CommandBuiltins.create()` 只选择部分内置项，或用 `help(false, null)` 关闭帮助。

## 消息与输出

推荐把 `LangRuntime.pipeline()` 传给 `CommandModule.install`。命令模块使用 `command.*` 消息键解析无权限、参数错误、内部错误、帮助和内置命令文本；这些键会随语言文件重载。

```java
CommandModule.install(
        root,
        BukkitCommandRegistrar.discover("myplugin"),
        lang.pipeline());
```

不需要自定义语言时，可以使用简化安装：

```java
CommandModule.install(root, BukkitCommandRegistrar.discover("myplugin"));
```

此形式使用内置消息、在线玩家解析器和 Spigot 富文本输出。高级适配场景可以传入自己的 `PlayerResolver`、`RichTextSink` 与 `CommandMessages`。

处理器抛出的运行时异常会被捕获、写入 `KLogger`，并向发送者显示本地化内部错误；`Error` 在记录和提示后仍会继续抛出。

`CommandDispatcher` 同时提供轻量 `DiagnosticSource`：只报告根命令名、调用次数、失败次数和最近失败的
异常类型，不记录发送者、命令参数或玩家身份。需要把命令状态附到 Remote Incident 时，由开发者显式注册
`new KlibDiagnosticContributor(dispatcher)`；Command 模块本身不依赖 Remote，也不会自动上传数据。

## 注册、重载与注销

命令注册成功后，模块会在支持的 Paper 服务端上立即刷新客户端命令树，使在线玩家无需重新登录即可获得补全；不暴露该能力的服务端（如 Spigot、旧版本）静默跳过，命令树在下次登录时生效。

`Scope.command` 返回的 `CommandRegistration` 归传入作用域持有。作用域关闭或重建时，它会：

1. 从 Bukkit `CommandMap` 注销命令；
2. 按对象身份清理命名空间及别名键；
3. 在支持的 Paper 服务端上刷新客户端命令树。

因此动态功能应把命令注册在对应子作用域，而不是根作用域：

```java
root.scope("arena", arena -> {
    arena.command("arena", command -> configureArenaCommand(command));
});
```

重建作用域后旧命令会先完整注销，再注册新树，不会累积重复处理器；注销与注册两侧都会刷新客户端命令树，因此重建不会让在线玩家的补全丢失。

## 生命周期与线程约束

- Bukkit 命令注册必须在服务器主线程执行；从异步线程调用会失败。
- 注销若从非主线程触发，模块会调度回主线程并等待完成；无法安全调度时会显式失败。
- Bukkit 命令处理器通常运行在主线程。处理器中不要执行数据库、网络或大文件 I/O；使用 `scope.async(...).thenSync(...)`。
- 异步工作完成后，只有回到主线程才能修改玩家、世界或背包。
- 自定义 `CompletionStage` 用于内置异步重载时，应确保完成回调能够安全发送 Bukkit 消息；Config 的 `reloadAsync()` 在 `KPlugin` 环境中会在主线程监听器完成后结束。
- 命令能力、注册与语言管线都必须在所属作用域仍打开时使用。

## 注意事项

- `context.get(arg)` 按对象身份匹配，必须使用建树时的同一 `Arg` 实例；`Arguments.optional(...)` 返回的是新实例，读取时用包装后的实例或改用 `context.get(name, type)`。
- `greedyString` 必须位于路径末尾，之后不能添加 literal 或参数。
- literal 优先于同级参数；具体值与命令词冲突时，应调整树结构避免歧义。
- 命令模块只解析 Bukkit 交给它的命令 token，不负责 shell 风格引号或转义。
- Brigadier 集成是发现式增强；即使当前服务端不暴露对应接口，Bukkit 执行和 Tab 补全仍是基础能力。
- 根命令冲突时 Bukkit 可能只保留命名空间形式；选择稳定且唯一的 fallback prefix。

## 相关模块

- [Core](core.md)：命令注册的作用域和异步任务。
- [Config](config.md)：可重载类型化配置。
- [Lang](lang.md)：命令消息、帮助和富文本输出。
- 完整命令树的组成方式见本页“构建命令树”和“完整示例”。
