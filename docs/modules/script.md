# klib-script

`klib-script` 提供一个 Java 8 Kether 运行时，让插件把条件、奖励和流程编排放进配置，同时用受控的宿主服务连接消息、命令、权限和占位符。配置脚本由插件管理员维护；JEXL 表达式可以访问显式放入变量的对象。

## 接入模块

使用 klib Gradle 插件：

```kotlin
klib {
    modules {
        script()
    }
    relocate("org.apache.commons.jexl3", "jexl3")
    relocate("org.apache.commons.logging", "commonslogging")
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

Kether 解析核心已经包含在 `klib-script` 中。`calc` / `invoke` 通过传递运行时依赖使用 Apache Commons JEXL 与 Commons Logging；构建插件会打包它们，请使用上面的显式重定位避免与同服插件冲突。手动打包也必须包含并重定位这两个依赖。其移植来源和许可证见仓库的 `THIRD_PARTY_NOTICES.md`。`me.kzheart.klib.script.kether.core` 包中，只有注册完整 Kether 语句所需的类型（`QuestActionParser`、`QuestReader`、`ParsedAction`、`QuestAction`、`QuestContext`）属于对外使用面，见[注册完整 Kether 解析器](#注册完整-kether-解析器)；其余为移植而来的解析器内部实现，普通插件不应依赖，也不应假定其结构稳定。

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


只检查语法而不开始执行时，调用 `KetherScriptEngine.validate(source, context)` 或
`ScopedScriptRuntime.validate(source, context)`。它复用编译缓存，编译所有具名块，错误同步抛出
`ScriptException`；不注入 guard、不调用动作或宿主服务，重复的 `def main` 也不会绕过预检。
`inline`、JavaScript 或其他动作在运行时才生成的字符串脚本仍需要执行测试，静态编译不等于验证其运行时结果。

需要在进入业务流程前同步处理编译失败时，调用 `KetherScriptEngine.evalChecked(source, context)`。
编译失败立即抛出本地化 `ScriptException`；动作执行失败（包括立即失败）仍通过返回的 `CompletionStage` 传播。
它与 `eval` 共用编译缓存，每次调用只执行一次脚本，不先预检再重新解析。现有 `eval` 仍将编译失败放入返回阶段。

默认 `sender(...)` 固定发送者。需要脚本内切换执行者时，显式使用
`ScriptContext.builder().senderVariable("actor").variable("actor", player).build()`。
每次 `sender()` 读取当前帧可见的 `actor`，原生动作修改变量会立即影响后续宿主服务；
缺失、移除或显式 null 都表示无发送者，不回退到 `sender(...)`，也不会替调用者初始化变量。
命名空间派生视图保留此设置。宿主服务仍负责检查接收者类型和访问线程。

## 嵌套动作、分支与表达式

`tell`、`colored` / `color`、`inline` / `function` 的参数可以是文字、`&变量`、`*字面量`、代码块或另一个动作。带引号的参数始终是文字；已注册动作的语法错误会抛出，独立位置的未知语句仍然报错。

```text
tell colored inline *"&aLevel up! &f{{ &level }}!"
case &level [
  when <= 15 -> calc "level * 2 + 7"
  when <= 30 -> calc "level * 5 - 38"
  else calc "level * 9 - 158"
]
```

`colored` 支持传统 `&` 颜色和格式代码、`&#RRGGBB` 与 `&x&R&R&G&G&B&B`。`inline` 顺序执行 `{{ 脚本 }}`，并保留 `${name}` 与 `{{ name }}` 变量简写。嵌套脚本共用求值深度限制；错误会向外传播。

`command` 在调用 `CommandSink` 前求值命令参数，可接 `inline`、`&变量`、`*字面量`、引号文本、代码块或已注册动作：

```text
command inline *"scoreboard players add Alex rewards {{ &level }}" as console
command &commandText
command inline "say {{ calc 'level + 1' }}" as console
```

`as console` 向宿主传递空 sender，省略时使用上下文 sender。已有 `command say ready` 写法仍可使用；完整命令建议加引号，避免首词与脚本动作同名。异步命令参数完成后的 dispatch 和后续语句使用宿主续接执行器，换行、分号和代码块边界保留。

`case` 只求值输入一次，按顺序选择第一个满足条件的分支；仅执行选中的分支体，无匹配且无 `else` 时返回 `null`。分支分隔符为 `->` 或 `then`，省略比较符时按推断类型后的相等判断。条件可写为 `[ 条件一 条件二 ]`，普通比较对各条件取“或”；`in` / `contains` 的多条件组成列表。支持 `==` / `is`、`!=` / `!is` / `not`、`=!` / `is!`（不转换类型）、`=!!` / `is!!`（同一对象）、`=?` / `is?`（忽略大小写）、`>` / `gt`、`>=` / `gte`、`<` / `lt`、`<=` / `lte`、`in` 和 `contains` / `has`。

`calc` / `calculate` 使用 JEXL 表达式，`invoke` 使用 JEXL 脚本。静态表达式在解析阶段编译；`dynamic` 后接受嵌套动作，运行时将结果编译求值：

```text
calc dynamic inline "{{ &level }} * 2"
invoke "var doubled = level * 2; return doubled;"
```

JEXL 读取当前帧与 `ScriptContext` 的变量，不自动注入服务器、控制台或插件对象。`invoke` 中的局部赋值不写回 `ScriptContext`。异步参数完成后的消息和后续动作通过引擎指定的 continuation executor 执行；默认构造器遇到异步续接会明确失败，宿主必须按下文注入正确的线程调度。

底层解析器可用 `QuestReader.nextValue()` 接受动作或字面量参数，用 `nextParsedAction()` 保持严格动作解析。二者都不会吞掉已识别动作内部的错误。

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

可选宿主接口包括 `MessageSink`、`CommandSink`、`PlaceholderResolver`、`PlayerQuery`、`DelayScheduler`、`ScriptSenderQuery` 和 `ScriptPropertyAccess`。只安装当前脚本确实需要的能力；脚本缺少服务时会以清晰异常失败，而不是静默跳过动作。

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
// 按下面的 import 选定完整语法解析器，正文使用简单类名。
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

这里的 `QuestActionParser`、`ParsedAction`、`QuestAction` 和 `QuestContext` 位于 `me.kzheart.klib.script.kether.core`。其中 `QuestActionParser` 与 `me.kzheart.klib.script.QuestActionParser`（`Statements` 系列使用的 `execute(StatementCall, ScriptContext)` 接口）同名而不同类型，`registerKether(...)` 只接受前者，按示例显式 import 后使用简单类名；同一文件确实需要两种同名接口时才使用必要限定名。这是需要完整 Kether 语法能力时的低层入口；普通固定参数业务语句仍优先使用 `Statements.combine()`。

## 数值、赋值与发送者动作

通用数值语句逐个执行嵌套动作，既有 `add/sub/mul/div` 仍保留：

```text
math add [ math div [ &size 5 ] 2 ]
math 10 + 2 * 3
set chooseA to round random 10
permission admin
sender
```

`math` 支持列表式 add/sub/mul/div（或 +、-、*、/）及中缀左结合，**不采用乘除优先级**。所有操作数可转换为 Int 时返回整数，整数除法截断；否则使用 Double。列表的操作数异常沿原动作规则打印错误并取 0；中缀首值错误向调用方传播，不复制原框架可能不结束的 Future。

`round` 返回饱和边界的 Int，半数向正无穷方向取整，NaN 抛错。`random N` 对 Int 返回 `[0,N)`；`random A to B` 的整数边界两端包含、自动排序，Double 使用半开区间，相等边界返回该值。集合和对象数组随机选元素，空集合/空数组返回 null；非空的 null 元素保留原错误行为。

`set key to 动作` 顺序求值并将原始结果写入变量，动作自身返回 null；保留旧 `set key value` 的类型推断与返回值。支持 `set property key from 动作 to 动作` 与 `set &object[key] to 动作`，先交给宿主 `ScriptPropertyAccess`，未支持时使用内置的 Map（`@键`）、List 与数组（下标）写入，不自动反射任意字段。不支持的属性写入抛错，原框架在这些分支仅输出警告；赋值值表达式保留打印错误并完成 null 的规则。属性读取见[原框架内置语句](#原框架内置语句)。

`permission` 接嵌套参数，使用 `ScriptSenderQuery.isPlayer` 确认玩家，再通过 `PlayerQuery.hasPermission` 查询；其他发送者失败。`sender` 对玩家使用 `ScriptSenderQuery.name`，对 null 及其他非玩家按原语义返回 `console`。原 `perm` 动作保留原有宿主查询行为，不因新动作改变。随机区间多行文本的 trimIndent 以及原随机动作非终止错误链不在当前兼容范围；这里传播异常，不返回永远不结束的 Future。

## 比较与宿主查询

`check` 读取两个嵌套动作，按左、右顺序执行，再比较结果：

```text
check &game not null
check &choose is null
check player level < 10
check papi "%wealth_level%" >= 5
check math 2 + 3 == 5
```

比较符包括 `==/is`、`!=/!is/not`、`=!/is!`（不推断类型的相等）、`=!!/is!!`（对象身份）、`=?/is?`（文本忽略大小写）、`>/gt`、`>=/gte`、`</lt`、`<=/lte`、`contains/has` 和 `in`。保留既有 `=` 相等别名。普通相等按原算法推断字符串数字及布尔值，两个 Number 以 double 比较；排序比较使用原数值强制转换规则，不能当作 BigDecimal 精确比较或字典序排序。包含判断对集合/对象数组查元素、Map 查键，其余值转字符串；不自动展开原始类型数组。

独立 `true` 和 `false` 保留原容错字面量的 String 结果，`null` 返回真实 null；不要把字面量文本的 Java 类型误写成 Boolean。

`player 属性` 通过 `ScriptSenderQuery.isPlayer` 确认玩家，再调用 `PlayerQuery.property`，null 结果视为不可读。属性名按原框架操作名表匹配多词名称（如 `block x`、`bed spawn x`、`on ground`、`display name`），取最长的完整匹配；不在表中的单个词元按宿主自定义属性传入。名称以小写、空格分隔传给宿主。

`player 属性 to|add|increase|+|sub|decrease|- 值` 调用 `PlayerQuery.write`，返回 false 时报“不可写”。`=` 仍留给外围 `check` 的相等别名，独立 `player level = 5` 解析失败，不会改变玩家；原框架把 `=` 当作写入。

`papi` 与 `placeholder` 接一个完整嵌套动作，再调用 `PlaceholderResolver.resolve`；要求真实玩家语义的发送者查询服务。多词文本须使用引号，如 `papi "hi %name%"`。null 输入转换为空串，其他值使用原 trimIndent 规则。沿原字符串辅助算法，输入 Future 或首次展开失败时打印错误，再以空串展开一次；第二次失败传播。输入动作在返回 Future 之前同步抛错则直接传播，不进入这条重试链。普通动作的参数消费、发送者要求因此与旧扁平 PAPI 实现有变化，控制台不能再直接展开。

异步参数完成后通过 frame 的续接执行器调用宿主查询。宿主仍须负责 Bukkit 主线程检查和实际插件集成。

## 原框架内置语句

引擎内置 TabooLib 6.3.0 的通用语句，写法与语义按原框架实现，同一份脚本在两边都能运行。

| 类别 | 语句 |
| --- | --- |
| 流程 | `wait`/`sleep` 时长、`exit`/`stop`/`terminate`、`pause`、`seq [ ... ]`、`repeat 次数 动作`、`call 块`、`goto 块`、`async`、`await`、`await_all [ ... ]`、`await_any [ ... ]`、`import`/`release` 命名空间、`optional 值 else 动作`、`pass`、`vars`/`variables` |
| 循环 | `for i in 值 then 动作`、`map i in 值 with 动作`、`while 条件 then 动作`、`break` |
| 集合 | `array [ ... ]`/`arr`、`size`/`length`、`arr-get`/`element`/`elem 下标 in 列表`、`arr-add 值 to 列表`、`arr-push`/`arr-add-first`、`arr-remove 值 in 列表`、`arr-remove-at`、`arr-take`/`arr-remove-first`、`arr-drop`/`arr-remove-last`、`arr-find 值 in 列表`、`mutable`、`shuffle`、`reverse`、`split 文本 [by 正则]`、`join [ ... ] [by 分隔符]`、`range 起 to 止 [step 步长]` |
| 文本与时间 | `uncolor`/`uncolored`、`scale`/`scaled`、`format 毫秒 [by 格式]`、`printed 文本 [by 分隔符]`、`match 文本 by 正则`、`time`/`date [as 格式]`、`day of year|month|week`、`year`、`month`、`hour`、`minute`、`second`（复数形式同义）、`log`/`print`/`info`、`warn`/`warning`、`error`/`severe` |
| 游戏 | `tell`/`send`/`message`、`actionbar`、`broadcast`/`bc`、`players`、`switch 玩家名|console`、`title 文本 [subtitle 文本] [by 淡入 停留 淡出]`、`subtitle`、`location`/`loc 世界 x y z [and yaw pitch]`、`sound 名称 [by 音量 音调]`、`stopsound`、`itemstack`、`material`、`scoreboard 内容`、`command 文本 [as player|op|console]`、`js`/`javascript`/`$` |

```text
for p in players then {
  tell inline "{{ &p }}"
}
set list to array [ *a *b *c ]
arr-add *d to &list
tell join [ &list[0] &list[size] ] by -
title "&a你好 @sender" subtitle "欢迎" by 10 40 10
command "give @sender diamond 1" as op
```

- 列表语句就地修改变量中的列表（`array` 生成可变列表）；`for`、`map`、`while` 在同步执行时以循环推进，不会因次数多而耗尽调用栈，异步迭代通过续接执行器恢复。`for`/`map` 遍历 Map 时额外提供 `键-key`、`键-value` 变量，结束后移除。
- `exit` 与原框架一样只设置退出状态：后续语句不再执行，脚本以最后的值正常完成，不报错。`pause` 返回永不完成的 Future，宿主应自行限制等待。
- `wait` 通过 `DelayScheduler` 等待，结束时发送者为已离线玩家（`ScriptSenderQuery.isOnline`）则停止脚本。
- 文本中的 `@sender` 在 `tell`、`actionbar`、`broadcast`、`title`、`command` 中替换为发送者名称；`command ... as console` 替换为 `console`。`command` 文本会去除公共缩进。
- `switch` 切换本次脚本后续语句的发送者；嵌套 `inline` 求值不继承切换结果。
- `sound` 省略 `by` 时音量与音调为 0（原框架行为）；名称以 `resource:` 开头时为资源包音效，否则把 `.` 换为 `_` 并转大写后按枚举查找。

属性读取：`&变量[键]` 与 `动作[键]`（如 `player[name]`）、`get property 键 from 动作`。先交给宿主 `ScriptPropertyAccess`，再使用内置属性：String 的 `upper`/`lower`/`length`/`trim`，Map 的 `@键`/`size`/`keys`/`values`，List 与数组的下标和 `size`，正则 Matcher 的组号或组名。不支持时向 `ScriptLogger` 警告并返回 null。

### 容错解析

原框架默认把未注册的词元当作字面量，因此 `array [ 1 2 ]`、`if true then 1` 可以直接写。Klib 默认仍为严格解析，以便在加载时发现拼写错误；需要兼容原框架脚本时使用四参数构造器：

```java
KetherScriptEngine engine = new KetherScriptEngine(statements, interop, mainThread, true);
```

字面量词元是字符串，例如 `array [ 1 2 ]` 得到 `["1", "2"]`。

安装 `TabooLibKetherInterop` 时，未注册的词元先交给同服 TabooLib 容器解析；没有容器认领才按字面量处理，远端语句自身的语法错误照常抛出。容错模式下引号括起的文本总是字面量，即使与语句同名（原框架会把 `"mm"` 这类与语句同名的引号文本解析为语句）。Klib 另有原框架没有的内置语句名（`add`、`sub`、`mul`、`div`、`eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`and`、`or`、`unset`、`list`、`namespace`），未加引号作为文字使用时需要加引号或 `*` 前缀。

### 游戏语句的宿主服务

游戏语句通过 `ScriptPlatform`（在线玩家、广播、控制台、按名查找玩家、动作栏、标题、音效、坐标、材质与物品、侧边栏）、`PlayerQuery.write`、`CommandSink.dispatchAsOperator`、`ScriptLogger` 与 `JavaScriptEvaluator` 访问服务器。Bukkit 插件可直接安装默认实现：

```java
ScriptContext context = BukkitScriptServices.apply(ScriptContext.builder().sender(player), plugin)
        .service(PlaceholderResolver.class, (target, text) -> PlaceholderAPI.setPlaceholders((Player) target, text))
        .build();
```

`BukkitScriptServices.apply` 安装 `MessageSink`、`CommandSink`（含 `as op`）、`ScriptSenderQuery`、`PlayerQuery`（原框架全部玩家操作，含写入与取值范围限制）、`DelayScheduler`（按 50 毫秒一刻向下取整）、`ScriptLogger`（插件日志）、`ScriptPlatform`、ItemStack/ItemMeta 属性，以及服务端存在 JavaScript 引擎时的 `JavaScriptEvaluator`。`PlaceholderResolver` 依赖 PlaceholderAPI，仍由宿主提供。`scoreboard` 需要侧边栏实现，通过 `apply(builder, plugin, (player, lines) -> ...)` 传入，lines 为 null 表示移除；未传入时该语句报不支持。所有服务必须在主线程调用，引擎的续接执行器应为主线程调度器。在旧版本中不存在的玩家属性（如 `swimming`、`ping`、`pose`）调用时报不支持。

## 原生动作与宿主变量

原生 parser 中通过 `ScriptFrames.context(frame)` 访问 sender、locale、namespace 和已安装的服务；不用读取引擎内部变量。返回的 `ScriptContext` 是当前帧的实时视图，不是脱离执行上下文的副本。`setVariable` 依原生规则将非 `~` 变量写到根帧，`~` 局部变量留在当前帧；`removeVariable` 移除最近一层可见定义。`~klib:` 内部键不可读写。

```java
ScriptContext host = ScriptFrames.context(frame);
host.setVariable("answer", Integer.valueOf(42));
return ScriptFrames.eval(frame, "tell inline *\"answer={{ &answer }}\"")
        .toCompletableFuture();
```

`ScriptFrames.variables(frame)` 返回父层在前、近层覆盖的只读原始变量快照，包含显式 null 或 `QuestFuture`；读取延迟变量的结果用 `host.variable(...)`，未就绪时保持原生错误，不阻塞。该优先级是通用的近层覆盖规则，业务需要其他历史枚举顺序时应明确自行组合帧。

普通动作与原生动作即时共享同一帧变量；嵌套求值和整个脚本结束时将变量差量写回其调用上下文，包括失败前已经发生的修改。没有改变的键不会覆盖调用方同时新增或修改的值。宿主构建器可接收 null，原生显式 null 在快照与最终上下文保留；既有 `setVariable(name, null)` 仍表示删除，`variable(...)` 对 null 返回空 Optional。

帧视图的线程和有效期与动作相同；不要留存到执行结束后，也不要把它当成线程安全的独立上下文。异步参数需要在引擎的 continuation executor 上继续访问帧和宿主服务。

## 即时文本模板

对于需要同步生成文本的调用方，使用显式的 `ScriptTemplates.renderImmediate`：

```java
String message = ScriptTemplates.renderImmediate(
        "value={{ &value }}", source -> engine.eval(source, context));
```

它从最内层 `{{...}}` 向外执行，每段保持原有空白，反斜线转义的定界符仅还原为文字。每段读取 Future 的 `getNow(null)`，未完成或结果 null 时立即插入字符串 `null`，不会等待、取消或阻止异步动作继续发生；已完成的失败或取消会抛出。调用方决定是否捕获错误并返回业务默认文本。替换结果中产生的模板继续解析；遇到无法匹配起始符的第一个结束符时停止。

此接口不采用 `inline` 的简单变量名捷径，也不改变 `inline` 原有的顺序等待行为。各段共享或隔离变量由传入的 evaluator 决定。

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

## 作用域拥有的执行与可选 Bukkit 宿主

插件生命周期内会运行 `wait`、`async`、`await` 或其他异步动作时，推荐使用 `ScopedScriptRuntime`。它保留原有 Kether 解析和上下文接口，额外把原生执行上下文归属到 `Scope`；不改变旧 `KetherScriptEngine` 构造器的默认解析模式。

```java
import me.kzheart.klib.script.BukkitScriptHost;
import me.kzheart.klib.script.KetherCompatibility;
import me.kzheart.klib.script.ScopedScriptRuntime;
import me.kzheart.klib.script.ScriptContext;
import me.kzheart.klib.script.StatementRegistry;

StatementRegistry statements = new StatementRegistry();
ScopedScriptRuntime scripts = new ScopedScriptRuntime(
        context().scope(), statements, null, context().scope().syncExecutor(), true);
BukkitScriptHost host = BukkitScriptHost.builder(this, context().scope())
        .scoreboard(true)
        .build();

ScriptContext scriptContext = host.apply(ScriptContext.builder().sender(player)).build();
scripts.eval("tell ready\nwait 1s\ntell done", scriptContext);
```

该示例使用 `KPlugin` 无参 `setup()` 所提供的 `context()`；自己的业务动作仍通过同一个注册表注册。成功安装新配置后调用 `scripts.cancelPending()`，旧脚本的等待和已排队续接不再操作旧配置；若配置验证失败，不调用它即可保留原执行。`close()` 随作用域释放，不应把关闭请求再投递到已经关闭的调度器。调用返回阶段的 `toCompletableFuture().cancel(false)` 会终止对应原生执行，而不只取消一层结果包装。取消直接在调用线程清理原生上下文；如果自定义帧清理动作会访问 Bukkit，也必须在主线程调用取消。即使主脚本已经返回，仍在运行的 detached `async` 子动作也由作用域持有，完成后释放。

`await` 的失败与取消会传播至外层，避免成功回调没有执行而使结果永久挂起。`all [ ... ]` 和 `any [ ... ]` 消费完整动作树，支持文字、`check`、权限、自定义动作和嵌套组合；保持按列表顺序求值全部输入的既有策略，不新增短路副作用变化。空组结果分别为 true 和 false。嵌套 `player` 动作使用原生 `ScriptSenderQuery` 和 `PlayerQuery` 检查，自定义宿主需同时安装；`BukkitScriptHost` 已提供这两项。

`BukkitScriptHost` 显式启用额外能力，复用一组带主线程和关闭检查的 Bukkit 服务；旧的 `BukkitScriptServices.apply(...)` 仍可使用。宿主构建、`apply`、服务调用和释放都要求主线程。可选项：

- `scoreboard(true)`：首行为标题，随后最多 15 行，保留重复行和空行。null 或空列表移除；移除、退出或作用域关闭时，只有当前计分板仍属于该宿主才恢复先前计分板，不覆盖其他插件后来接管的计分板。文本长度遵循当前服务器 API 限制
- `scoreboard((player, lines) -> ...)`：接入插件已有的共享侧边栏，例如任务追踪；回调替代原生侧边栏，外部计分板及其释放策略仍由调用者管理
- `javascript(factory)`：第一次 `js` 动作才创建并复用求值器；`javascriptEngine("nashorn")` 通过插件类加载器的 JSR-223 发现实现，缺少引擎时明确失败

未选择侧边栏或 JavaScript 时不启用相应额外能力。`PlaceholderResolver` 仍由插件根据自己的可选插件策略安装。

Klib 公共产物及运行时依赖保持 Java 8，不强制捆绑 Nashorn。现代 Java 服务器的业务插件可自行打包兼容其 Java 版本的引擎，并显式传入：

```java
import me.kzheart.klib.script.ScriptJavaScriptEngines;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

BukkitScriptHost host = BukkitScriptHost.builder(this, context().scope())
        .javascript(() -> ScriptJavaScriptEngines.using(
                new NashornScriptEngineFactory().getScriptEngine()))
        .build();
```

`NashornScriptEngineFactory` 来自业务插件选择的引擎依赖，不是 Klib API；使用独立 Nashorn 时应重定位 `org.openjdk.nashorn` 与其 `org.objectweb.asm` 依赖。`ScriptJavaScriptEngines.using(...)` 为每次执行建立独立变量映射，但不会深拷贝宿主对象，也不是不可信代码沙箱。脚本只能由可信插件管理员维护；变量中的 Java 对象仍可被脚本调用。

### 显式旧配置 case 兼容

兼容旧配置时，可在同一个注册表上调用：

```java
KetherCompatibility.installLegacyCases(context().scope(), statements);
```

它允许 `case` 的 `else -> value`，也保留默认支持的 `else value`；针对旧显示映射，允许一行分支结果中的连续大写文字标签，例如 `VERY SLOW`。注册过的动作、带引号字符串、嵌套表达式和注释不会按标签合并；新配置仍推荐给多词文字加引号。注册由作用域持有，释放后恢复默认 case 解析器。

这项配置不打开全局容错，也不发现 TabooLib。需要旧框架宽松字面量行为时仍显式选择 `toleranceParser=true`；未知独立词元可能成为合法文字，已注册动作内部的语法错误仍失败。数字文字通常保留字符串，需数值结果时使用 `math`、`type` 或传入有类型的变量。不要把宽松模式当成拼写检查器。

## 与 TabooLib 共享语句互操作

Bukkit 容器发现先按插件入口类名筛选 TabooLib 插件，再读取容器信息，避免解析无关插件公开方法中未安装的可选依赖类型。

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
