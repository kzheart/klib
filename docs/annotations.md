# 组件与注解 API

用注解声明命令、配置、事件、任务和菜单，在启动处显式注册一次，生命周期跟随插件或组件。

## 默认作用域

插件只在启动处安装需要的模块，日常业务不再逐次传 Scope：

```java
public final class ExamplePlugin extends KPlugin {
    @Override
    protected void setup() {
        ConfigModule.install(this);
        CommandModule.install(this);
        ConfigDocument<Settings> settings = configs().load(Settings.class);
        commands().register(new PlayerCommands(settings), new AdminCommands(settings));
        events().register(new PlayerListener());
        tasks().register(new Maintenance());
    }
}
```

- `commands()`、`configs()`、`events()`、`tasks()`、`components()` 绑定当前插件实例的生命周期。
- 没有全局当前 Scope、线程变量、类路径扫描或自动依赖注入。依赖通过构造器传入。
- `context()` 在 setup 期间可用，插件构造阶段不可用。
- 业务资源重建后需重新构造依赖对象，不能继续使用旧服务入口。
- 命令只在启动时声明，配置重载不重建含命令的根作用域或组件。
- 现有 `setup(Scope)` 仍可用；新代码选择无参 setup，**不要同时覆盖两种入口**。
- 高级集成可通过 `context().scope()` 访问底层 Scope。

## 功能组件

```java
public final class DungeonFeature extends KComponent {
    @OnStart
    public void start() { /* 初始化业务资源 */ }

    @Override
    protected void setup() {
        commands().register(new DungeonCommands());
        events().register(new DungeonListener());
        tasks().register(new Maintenance());
    }

    @OnStop
    public void stop() { /* 关闭业务资源 */ }
}
```

```java
ComponentHandle feature = components().install(new DungeonFeature());
feature.close();
```

| 阶段 | 顺序 |
| --- | --- |
| 安装 | 检查生命周期方法签名 → attach 上下文 → 调用 `@OnStart` → 执行 setup |
| 关闭 | 逆序释放 setup 中的注册资源 → 调用 `@OnStop` |
| 初始化中途失败 | 走与关闭相同的清理路径 |

- `@OnStop` 必须能处理只初始化了一部分的状态。
- 每类最多一个有效 `@OnStart` 和一个 `@OnStop`，均为 public、非 static、无参 void。
- 普通对象也可仅通过生命周期注解安装，不必继承 KComponent。
- 同一个 KComponent 实例不能重复安装；重新开启不含命令声明的业务组件应创建新实例。
- **含命令的组件必须在插件启动时安装**。关闭只保证命令立即逻辑停用，不支持通过运行时重新安装组件恢复命令。
- 组件不会自动扫描、注册字段里的对象；命令、事件和任务仍在 setup 中显式注册一次。
- 可随配置重建的事件、任务等业务资源，应与启动时声明的命令分开管理。

## 命令

注解包为 `me.kzheart.klib.command.annotation`。`@Command` 与 Bukkit 的 Command 同名，建议显式 import。

- 根名、别名与路由在启动时一次声明。配置重载只更新处理器读取的配置、语言和业务状态。
- 作用域关闭立即停用执行、补全和权限检查；物理节点可能保留到服务端生命周期重建或重启。
- 同一插件不得将相同标签交给 Klib 与其他注册器共同管理。完整规则见 [Command 模块](modules/command.md#启动注册配置重载与逻辑停用)。

```java
@Command(value = "dungeonbridge", aliases = {"dbe"}, help = true)
@Permission("bamdungeon.use")
@Check("ready")
public final class DungeonCommands {
    @Route({"open", "ui"})
    @Description("打开副本菜单")
    public void ui(Player player) { /* 打开菜单 */ }

    @Route("search <query>")
    @Description("查询副本")
    public void search(Player player, @Param("query") @Greedy String query) { }

    @Route("select <dungeon> <difficulty>")
    @Description("选择副本和难度")
    public void select(Player player,
            @Param("dungeon") String dungeon,
            @Param("difficulty") @Suggest("difficulty") String difficulty) { }

    @Suggestions("difficulty")
    public List<String> difficulties(SuggestionContext context) {
        String dungeon = context.get("dungeon", String.class);
        return Collections.singletonList(dungeon + "-easy");
    }

    @CheckHandler("ready")
    public void ready() {
        // 不可进入时抛出 new CommandRejectedException("数据服务尚未就绪")。
    }
}
```

### 参数与注入

| 方法参数 | 含义 |
| --- | --- |
| `Player`（无 `@Param`） | 发送者，自动限制为玩家入口 |
| `@Param Player` | 在线目标玩家 |
| `CommandSender`、`CommandContext`、`CommandCall` | 直接注入 |
| 其他业务参数 | 必须显式写 `@Param`，不依赖 `-parameters` |

- 支持的参数类型：String、int/Integer、long/Long、double/Double、boolean/Boolean、UUID、枚举、在线 Player。double 必须有限。
- 贪婪参数必须是末尾 String。
- 可选入口用不同的 `@Route` 方法表达，例如分别声明 `search` 和 `search <query>`。
- 共享参数路径必须保持名称、类型和补全声明一致；绑定到不同对象的补全器不能占用同一个共享参数节点。

### 权限与检查

- 类级 `@Permission` 为默认值，方法级 `@Permission` 替换默认值。
- 类级和方法级 `@Check` 累加，按声明顺序去重。
- `@CheckHandler` 可无参或接收一个 `CommandCall`，只在执行命令时调用，不在补全和帮助中执行。
- `CommandRejectedException` 是可直接展示的业务拒绝；其他异常仍由命令错误边界记录并提示内部错误。
- 权限用于服务端执行、帮助、Tab 补全和可用的 Brigadier 客户端树。**补全不代替业务对象授权。**

### 合并与挂载

- 同根命令的多个处理类**必须放在同一次** `register(playerCommands, adminCommands)` 中；所有别名共享合并后的完整树。
- `MountedCommand.of("main", questCommands, "quest", "q")` 把一个处理类的全部路由再挂到 `/main quest ...` 与 `/main q ...` 下：
  - 处理类自身的根命令照常注册（同一实例同时传入即可）；
  - 子命令可写多级，如 `"quest data"`；
  - 挂载忽略处理类的 `@Command` 名称、别名与类级 `@Description`，权限、Check 与补全照常生效；
  - 挂载项须与目标根的处理类放在同一次 `register(...)` 中，不支持嵌套挂载。

### 声明约束与注册校验

- 所有声明方法须 public、非 static。路由须返回 void。
- 继承方法按正常 Java override 规则处理，不隐式继承父类方法注解。
- 注册前校验整个批次：重复路由、缺失参数、未知补全器、错误签名会在触碰 Bukkit 前失败。
- 注册桥接中途失败，会逻辑关闭本批次已成功绑定的命令和别名，不保证物理节点立即清理或恢复原标签。
- 旧 Bukkit 启动注册中，单个尚未成功的注册尝试会清理自己的部分写入。

### 自动帮助与主命令入口

`@Command(value = "mail", help = true)` 这类声明会安装自动分页帮助：

- `@Command` 的 `help` 默认是 `false`。设置 `help = true` 后，Klib 在合并路由后安装 `help [page]`。
- 同根命令任一处理类启用即可，别名共享合并后的帮助。
- 帮助按发送者权限与玩家限制过滤，不执行业务 `@CheckHandler`。
- `@Description` 提供每条命令的用途。

上面的 `DungeonCommands` 没有空字符串根路由，因此：

| 输入 | 结果 |
| --- | --- |
| `/dungeonbridge`、`/dbe` | 帮助第一页 |
| `/dbe help 1` | 显式指定第一页 |
| `/dbe open`、`/dbe ui` | 打开菜单 |

- 原来用 `@Route({"", "open"})` 打开 GUI 的，改成 `@Route("open")`，**移除空字符串路由**。
- 启用自动帮助会保留已有根处理器，裸主命令仍会执行该处理器。
- 已显式声明的 `@Route("help")` 也会保留，Klib 不覆盖它，也不另外生成分页入口。
- 挂载处理器的 `@Command(help = true)` 不会替目标根安装帮助。应在目标根的处理类启用，或在程序化根声明中安装 `CommandBuiltins`。程序化写法见 [Command 模块](modules/command.md#自动帮助与主命令入口)。

### 异步回调

用一个明确的注册调用同时设置成功和失败回调，避免链式设置期间已经完成的 Future 丢失失败处理器：

```java
@Route("reviews")
public void reviews(CommandCall call) {
    call.await(repository.reviews(),
            rows -> call.reply("待核对会话: " + rows.size()),
            failure -> call.reply("读取失败"));
}
```

- 两种回调均回到主线程，注册所属功能关闭后不再执行。
- `reply` 对已离线玩家不发送消息。
- 不会撤销已提交的数据库事务，也不会强行终止外部 `CompletionStage`。

### 程序化平铺声明

启动时构建的程序化命令也可平铺声明。`route` 返回路径末尾节点，单参数版本 `argument` 返回追加的参数节点：

```java
Arg<String> token = Arguments.string("token");
commands().register("dbe", root -> {
    root.route("action start").argument(token)
            .executes(context -> start(context.get(token)));
    root.route("reload").permission("bamdungeon.admin")
            .executes(this::reload);
});
```

- 程序化自定义参数用 `Arguments.contextual(name, (input, context) -> value, suggestions)`，可读取发送者和已解析的前置参数。
- 注解内置类型之外的领域解析目前使用此程序化入口，不会自动把任意业务类型当作字符串解析。

## 配置

`ConfigFile` 位于 `me.kzheart.klib.config.annotation`（core 契约），其他配置注解由 config 模块提供。

```java
@ConfigFile("config.yml")
public final class Settings {
    @Key("max-party-size")
    @Range(min = 1, max = 16)
    @Comment("副本允许的最大队伍人数")
    public int maxPartySize = 4;

    @Validate
    public void validate() {
        // 跨字段校验，失败时抛出 ConfigValidationException。
    }
}
```

- `configs().load(Settings.class)` 仍返回 `ConfigDocument`；值不偷偷注入业务字段。**每次业务操作取一次 `value()` 快照。**

| 注解 | 规则 |
| --- | --- |
| `@Key` | 当前对象的一个 YAML 键，不是点分路径；重名映射拒绝加载 |
| `@Range` | 支持数值字段，包括 YAML 缺失时的 Java 默认值；拒绝 null、NaN、无穷和超界值 |
| `@Validate` | 在 POJO 映射完成后执行 |
| `@Comment` | 供显式 `ConfigDefaults.yaml(Settings.class)` 生成默认 YAML，不会改写正在使用的配置 |

- 映射、范围或业务校验失败，都不会发布新配置，也不会提交文件源的待定修改。
- 普通安装仍从 `src/main/resources/defaults/config.yml` 加载默认文件，不会自动改用注解生成。
- 默认生成支持标量、嵌套 POJO、标量集合和 Map；包含 POJO 的集合默认值请用 YAML 资源描述。

## 事件与任务

事件复用 Bukkit `@EventHandler`，通过 `events().register(listener)` 注册：

- 方法必须 public、非 static、void，且只有一个 Event 子类型参数。
- 保留 priority 和 ignoreCancelled。
- 事件不自动切线程，仍在 Bukkit 派发线程处理。

```java
public final class Maintenance {
    @Every(ticks = 20)
    public void tick() { /* 主线程 Bukkit 操作 */ }

    @Every(ticks = 1200, thread = TaskThread.ASYNC)
    public void flush() { /* 不访问 Bukkit 状态 */ }
}
```

`tasks().register(instance)` 注册固定周期任务：

- ticks 必须大于零，方法为 public、非 static、无参 void。
- 同一任务不重叠执行：异步上次运行未结束时跳过本次触发。
- 功能关闭时取消后续调度；已运行的 I/O 是否响应中断由业务实现决定。
- 动态周期或临时任务继续使用 `tasks().every`、`after`、`async`、`thenSync`。
- 直接调用带注解的方法仍是普通 Java 调用，不会切线程或启动定时器。

## 菜单

```java
Menus menus = Menus.install(this); // KPlugin 的 setup 中安装一次
menus.open(player, new DungeonMenu());
```

组件内需要独立关闭菜单时用 `Menus.install(context(), plugin)`，不要复用插件根上下文创建的服务。

```java
@Menu(title = "副本列表", layout = {"dd     nn"})
public final class DungeonMenu {
    private int page;

    @Entries('d')
    public List<MenuEntry> entries() { return pageEntries(page); }

    @Button('n')
    public MenuEntry nextButton() { return nextPageButton(); }

    @Click('n')
    public void next(MenuClick click) {
        page++;
        click.refresh();
    }
}
```

| 注解 / 方法 | 规则 |
| --- | --- |
| `@Menu` 布局 | 1..6 行，每行 9 字符；空格是空槽，其他字符必须有唯一 Button 或 Entries 提供器 |
| `@Button` | 返回非 null `MenuEntry` |
| `@Click` | 接收 `MenuClick`、返回 void，替代该按钮条目原有的动作 |
| `@Entries` | 返回 `List<MenuEntry>`，按行优先填充区域；少于容量时留空槽，超出容量时报错，调用方应先分页 |
| `refresh()` | 重新计算模型并渲染原物品栏，不创建新会话、不重复注册事件；标题和尺寸不能改变 |

- 每次打开创建一个对象；同一服务拒绝同时复用一个活动菜单对象。
- 动态条目保留自己的动作，不再给 Entries 区域额外绑定 Click。
- 模型编译失败时保留原模型；业务对象中已经修改的 page 等字段**不会自动回滚**。
- 不允许新模型覆盖投放区。菜单关闭后不能再替换模型，会话任务随关闭清理。

## 验证

注解路由注册、权限与别名、自动帮助、根处理器及自定义帮助的测试见 [AnnotatedCommandsTest.java](../klib-command/src/test/java/me/kzheart/klib/command/AnnotatedCommandsTest.java)。

## 范围

- 不引入全包扫描、自动注入、事务代理或通用 `@Async`。
- 数据事务、物品 Builder、语言消息和外部插件能力继续使用现有显式 API。
- 语言接口注解是可选后续方向，目前没有 `@MessageKey` 或 `@Placeholder` 接口代理。
