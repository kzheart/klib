# klib-script

`klib-script` 提供一个 Java 8 Kether 运行时，让插件把条件、奖励和流程编排放进配置，同时用受控的宿主服务连接消息、命令、权限和占位符。它适合短小的业务脚本，不用于执行任意 Java 代码。

## 接入模块

使用 klib Gradle 插件：

```kotlin
klib {
    modules {
        script()
    }
}
```

如果还要复用同服 TabooLib 的完整 Kether parser，改为启用单 JAR 互操作入口；它会自动加入
`script` 模块：

```kotlin
klib {
    ketherInterop(true)
}
```

直接依赖时加入：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-script:<klib-version>")
}
```

Kether 解析核心已经包含在 `klib-script` 中，没有额外运行时库。其移植来源和许可证见仓库的 `THIRD_PARTY_NOTICES.md`。`me.kzheart.klib.script.kether.core` 包中，只有注册完整 Kether 语句所需的类型（`QuestActionParser`、`QuestReader`、`ParsedAction`、`QuestAction`、`QuestContext`）属于对外使用面，见[注册完整 Kether 解析器](#注册完整-kether-解析器)；其余为移植而来的解析器内部实现，普通插件不应依赖，也不应假定其结构稳定。

## 执行脚本与条件

先创建注册表和引擎，再为每次执行建立隔离的 `ScriptContext`：

```java
import me.kzheart.klib.script.KetherScriptEngine;
import me.kzheart.klib.script.ScriptContext;
import me.kzheart.klib.script.StatementRegistry;

StatementRegistry statements = new StatementRegistry();
KetherScriptEngine engine = new KetherScriptEngine(statements);

ScriptContext context = ScriptContext.builder()
        .sender(player)
        .variable("level", Integer.valueOf(12))
        .build();

engine.evalCondition("gte &level 10", context)
        .thenAccept(allowed -> {
            if (allowed.booleanValue()) {
                // 纯 Java 后续处理；触碰 Bukkit 状态前仍要确认所在线程
            }
        });
```

`eval(...)` 返回最后一个动作的结果，`evalCondition(...)` 把布尔值、数字、文本和 `null` 转为条件结果。变量在一次上下文中可读写；命名空间默认按 `klib`、`global` 搜索。

引擎会安装变量、比较、逻辑、算术、条件、列表和延迟等内置语句。`tell`、`command`、`papi`、`perm` 等语句只有在上下文中提供相应宿主服务时才能运行。

## 向脚本暴露受控能力

通过 `ScriptContext.Builder.service(...)` 安装最小能力，而不是把插件主类或数据库连接直接放进变量：

```java
ScriptContext context = ScriptContext.builder()
        .sender(player)
        .service(MessageSink.class, (sender, message) ->
                player.sendMessage(message))
        .build();

engine.eval("tell 任务已完成", context);
```

可选宿主接口包括 `MessageSink`、`CommandSink`、`PlaceholderResolver`、`PlayerQuery` 和 `DelayScheduler`。只安装当前脚本确实需要的能力；脚本缺少服务时会以清晰异常失败，而不是静默跳过动作。

## 注册业务语句

自定义语句必须归属于 `Scope`，这样配置重建或插件关闭时会自动注销：

```java
statements.register(scope, "shop", "announce", Statements.combine()
        .remaining("message")
        .execute((arguments, context) -> {
            String message = arguments.require("message");
            context.requireService(MessageSink.class).send(
                    context.sender().orElse(null),
                    message);
            return CompletableFuture.<Object>completedFuture(message);
        }));
```

`Statements.combine()` 支持必需参数、带默认值的可选参数和最后一个剩余文本参数。动作返回 `CompletionStage<Object>`，同步结果可用 `CompletableFuture.completedFuture(...)` 包装。命名空间可以防止不同插件或业务域的语句冲突；脚本可通过上下文调整优先搜索的命名空间。

不要让解析器接受未经限制的类名、文件路径或命令模板。自定义动作应把字符串输入转换为明确的领域参数，并在动作边界验证权限、数量和资源归属。

### 注册完整 Kether 解析器

需要读取嵌套 action、关键字或自定义语法时，使用 `registerKether(...)` 直接注册完整的 Kether `QuestReader` parser。它仍由 `Scope` 管理，但默认只在当前 Klib 运行时可见：

```java
// 注意：这些类型来自 kether.core 包，与 me.kzheart.klib.script.QuestActionParser 同名但不同签名，
// 必须按下面的 import 引入，或使用全限定名。
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;

statements.registerKether(scope, "myplugin", "twice", QuestActionParser.of(reader -> {
    ParsedAction<?> nested = reader.nextAction();
    return new QuestAction<Object>() {
        @Override
        public CompletableFuture<Object> process(QuestContext.Frame frame) {
            return frame.newFrame(nested).run()
                    .thenCompose(first -> frame.newFrame(nested).run());
        }
    };
}));
```

这里的 `QuestActionParser`、`ParsedAction`、`QuestAction` 和 `QuestContext` 位于 `me.kzheart.klib.script.kether.core`。其中 `QuestActionParser` 与 `me.kzheart.klib.script.QuestActionParser`（`Statements` 系列使用的 `execute(StatementCall, ScriptContext)` 接口）同名而不同类型，`registerKether(...)` 只接受前者，写代码时不要依赖未限定名。这是需要完整 Kether 语法能力时的低层入口；普通固定参数业务语句仍优先使用 `Statements.combine()`。

## 异步动作与续接线程

单参数和双参数构造器适合同步动作。只要脚本可能执行 `delay` 或自定义异步动作，就必须使用三参数构造器提供续接执行器：

```java
Executor mainThread = command -> plugin.getServer().getScheduler()
        .runTask(plugin, command);

KetherScriptEngine engine = new KetherScriptEngine(
        statements,
        null,
        mainThread);
```

没有显式续接执行器时，异步动作会快速失败并产生 `continuation-executor-required` 错误，避免后续脚本意外运行在数据库或网络线程。执行器决定动作完成后的脚本从哪里继续；若脚本包含 Bukkit 操作，应使用主线程执行器。

## 与 TabooLib 共享语句互操作

旧的 `OpenContainerBridge` 只能作为执行级 `UnknownStatementResolver`，适合由适配器自行处理的简单、
扁平语句：

```java
OpenContainerBridge bridge = new OpenContainerBridge(discovery);
KetherScriptEngine engine = new KetherScriptEngine(
        statements,
        bridge,
        mainThread);
```

它不会把真实 `QuestReader`、嵌套 action 或 Frame 状态交给远端，因此不能用于完整 TabooLib Kether
兼容。新接入应使用下面的 `TabooLibKetherInterop`；`OpenContainerBridge` 仅为已有的定制执行适配保留。

需要双向复用完整 Kether parser 时，安装 `TabooLibKetherInterop`，并通过 `registerShared(...)` 显式发布语句：

```java
TabooLibKetherInterop interop = TabooLibKetherInterop.install(
        scope,
        statements,
        plugin.getName());

statements.registerShared(scope, "myplugin", "custom-action",
        QuestActionParser.of(reader -> {
            String value = reader.nextToken();
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    return CompletableFuture.<Object>completedFuture(value);
                }
            };
        }));

KetherScriptEngine engine = new KetherScriptEngine(
        statements,
        interop,
        mainThread);
```

`registerShared(...)` 同时注册到当前 Klib 运行时，并向服务器上已发现的 TabooLib OpenContainer 发布。后加载的容器会由作用域调度器定期发现并重放；Scope 关闭时会注销远端 action。普通 `register(...)` 和 `registerKether(...)` 永远不会自动共享，远端导入的 action 也不会再次导出。

TabooLib 会回调业务 JAR 中与其 OpenContainer 约定一致的 `OpenAPI`。`klib-script` 已携带 `me.kzheart.klib.script.taboolib.common.OpenAPI` 与相应 `OpenResult` 形状；Klib Gradle 插件在打包时将它们和兼容主入口一起 relocation 到业务插件的私有路径，开发者不需要自己创建协议类。

### Guard 云端商品

Guard 商品没有独立 Bukkit 主类，不能生成商品自己的 `OpenAPI`。应改用门户级 Broker：

```java
GuardKetherInterop interop = GuardKetherInterop.install(
        root,
        statements,
        host());

statements.registerShared(root, "myproduct", "custom-action", parser);

KetherScriptEngine engine = new KetherScriptEngine(
        statements,
        interop,
        root.syncExecutor());
```

`GuardKetherInterop` 同样支持双向共享：`registerShared(...)` 发布商品 action，门户发现的外部
TabooLib action 会导入商品注册表。差异在于 action 解析结果只以 `long` 句柄进入门户；商品私有的
Kether parser、action 和 frame 始终留在商品类加载器内。商品 Scope 关闭会撤销全部发布与导入、
清除句柄并取消未完成的 future，因此旧 generation 不能继续调用新版本商品。

商品 action 的完成值也受类加载器边界约束：字符串、基础数值和安全的父加载器对象可直接返回，
集合、Map 与数组会递归检查并复制；商品私有 DTO 会被拒绝，避免外部插件通过返回值长期持有已卸载
商品的 ClassLoader。门户最多同时保留 4096 个待外部释放的解析句柄，并在 action facade 被回收或
商品关闭时释放。

新版 Klib OpenContainer 在撤销 action 时会携带 owner，门户只允许原发布者撤销。旧版两参数
`kether_remove_action` 无法证明调用者身份，因此仅在 owner 容器消失后由发现循环清理，不允许一个
仍在线的冲突插件借清理请求撤销别人的路由。

构建时 `klib-script` 必须保持商品私有 relocation，`klib-guard-api` 与 `klib-core` 则由 Guard
父加载器提供。启用 Gradle DSL `ketherInterop(true)` 的 Guard 商品应生成协议 marker，而不能包含
`plugin.yml` 或商品级 TabooLib Bukkit 主类。

共享 action 应使用插件独占 namespace，例如 `myplugin` 或 `myplugin.shop`。TabooLib 的移除协议不携带 owner；多个插件覆盖同一个 `namespace:name` 后，任一插件注销都可能删除当前生效项。因此不要默认发布到 `kether`、`global` 或统一的 `klib` namespace。

互操作按 OpenContainer channel 能力工作，不锁定精确 TabooLib 构建版本。当前协议测试基线为 6.2.4 和 6.3.0。插件类加载器级热卸载仍受 TabooLib 容器缓存限制；配置和 Scope 重建受支持，替换插件 JAR 后应重启服务器。

## 错误、缓存与生命周期

- `eval(...)` 的失败会包装为带错误代码、行列位置和本地化消息的 `ScriptException`。业务层应记录原因并向配置作者展示位置，不要只吞掉异常。
- 编译结果按脚本文本、命名空间和注册表版本缓存；注册或注销语句会让相关缓存失效。不要自行缓存底层 Kether `Quest`。
- `ScriptContext` 的变量映射支持并发访问，但放入其中的可变对象不因此变成线程安全对象。
- 注册语句的 `Scope` 关闭后，该注册立即失效。引擎和注册表可由较长生命周期持有，业务语句则应安装在可重建子作用域中。
- `TabooLibKetherInterop` 必须安装在不短于 shared action 的 Scope 中；关闭时会先停止接收协议调用，再清除远端注册和容器引用。
- 脚本应设置来源、长度和业务复杂度上限；运行时已有动作数、嵌套深度和缓存大小保护，但这不能代替调用方的权限与资源限制。

仓库中的 `klib-script` 测试覆盖内置动作、异步续接、错误定位、编译缓存和 OpenContainer 互操作，可作为扩展语句行为的事实来源。
