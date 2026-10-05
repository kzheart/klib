# UI 模块

构建物品栏菜单、分页、物品投放区、聊天控制面板和聊天输入流程。点击、拖拽、数字键、双击、关闭归还和异步聊天事件由统一监听器处理，业务代码只描述模型和动作。

| 模块名 | 制品 | 自动带入 |
| --- | --- | --- |
| `ui` | `me.kzheart.klib:klib-ui` | `core`、`item`、`command`（含 `lang`） |

## 快速开始

```kotlin
klib {
    modules {
        ui()
    }
}
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

- **需要 CodeMC 仓库**：`ui` 带入 `item`，要解析 Item-NBT-API，配置见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。
- 直接依赖 `klib-ui` 时，还需保证 `klib-core`、`klib-item`、`klib-command`、`klib-lang` 及其运行时依赖可用：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-ui:<klib-version>")
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156")
}
```

最简单的用法是注解菜单：

```java
Menus menus = Menus.install(this);
menus.open(player, new Screen());
```

## 会话菜单注解

- `Menus.install(plugin)` 创建绑定插件的服务；组件内用 `Menus.install(context(), plugin)`。
- `menus.open(player, new Screen())` 读取 `@Menu`、`@Button`、`@Entries`、`@Click`，通过现有 `MenuCompiler` 和 `MenuRenderer` 打开。
- 每个菜单对象保存自己的页码和筛选状态。`MenuClick.refresh()` 重新计算模型，原会话、物品栏和任务归属不变。
- 底层 `MenuHolder.refresh(MenuModel)` 同样可以更新模型，条件是标题、尺寸不变且不覆盖投放区。

完整签名、列表容量和关闭约束见 [组件与注解](../annotations.md)。

## 创建并打开菜单

三步：构建 `MenuTemplate` → 编译为不可变 `MenuModel` → 交给作用域持有的 `MenuRenderer` 打开。

```java
import me.kzheart.klib.item.Items;
import me.kzheart.klib.ui.MenuCompiler;
import me.kzheart.klib.ui.MenuEntry;
import me.kzheart.klib.ui.MenuModel;
import me.kzheart.klib.ui.MenuRenderer;
import me.kzheart.klib.ui.MenuTemplate;

MenuRenderer menus = MenuRenderer.install(scope, plugin);

MenuTemplate template = MenuTemplate.builder("采集菜单", 3)
        .layout(
                "         ",
                "    T    ",
                "         ")
        .character('T', MenuEntry.of(
                Items.of("IRON_PICKAXE").name("&6领取工具").build(),
                click -> giveTool(click.player())))
        .build();

MenuModel model = MenuCompiler.compile(template);
menus.open(player, "gather-main", model);
```

- 行数必须是 1 到 6。使用布局时行数必须与菜单行数一致，每行恰好 9 个字符，空格表示空槽。
- 也可以用 `slot(...)` 或 `MenuCompiler.compileSlots(...)` 直接绑定槽位。
- 编译后的模型不可变，可以给多个玩家重复使用。
- `MenuEntry` 保存物品快照和点击动作。框架拦截已编译条目的移动，统一处理双击、数字键、拖拽和 shift-click。**业务动作不要再自行修改菜单中的展示物品。**

## 从配置编译菜单

配置模块把 YAML 解码为普通 `Map<String, Object>` 后，用 `MenuCompiler.compileYaml(...)` 编译：

- 支持的键：`title`、`rows`、`cancel-clicks`、`layout`、`items`、`slots`。
- 业务通过 `YamlItemResolver` 把配置中的物品 ID 解析成 `MenuEntry`，配置层不需要知道 Bukkit 或第三方物品 API。

## 分页

`Paginator<T>` 会复制数据，并把请求页码限制到合法范围：

```java
Paginator<Listing> paginator = new Paginator<Listing>(listings, 45);
Page<Listing> page = paginator.page(requestedPage);

for (Listing listing : page.values()) {
    // 将当前页条目编译到菜单槽位
}
```

- 页码从 0 开始。
- 数据为空时 `pageCount()` 也返回 1。
- 上一页、下一页按钮按 `Page` 的当前索引和总页数决定是否展示。

## 接收玩家投放的物品

在 `open(...)` 的会话配置回调中添加 `DropZoneController`。投放区持有收到物品的克隆，支持放置、shift 插入、拖拽和取回。

```java
Set<Integer> inputSlots = new LinkedHashSet<Integer>(
        Arrays.asList(Integer.valueOf(10), Integer.valueOf(11)));

menus.open(player, "recycle", model, session -> {
    DropZoneController zone = new DropZoneController(
            inputSlots,
            item -> item.getType() != Material.AIR);
    session.addDropZone(zone);
});
```

- 菜单关闭、被其他菜单替换或插件作用域关闭时，会话排空投放区，把物品交给打开菜单时配置的归还目标。
- `MenuRenderer` 默认先归还玩家背包，溢出时掉落在玩家位置。
- **不要把真实玩家物品作为普通 `MenuEntry` 展示**；可取回的物品放进投放区。

## 通过聊天收集输入

每个插件作用域只需安装一个 `BukkitChatPrompts`：

```java
BukkitChatPrompts prompts = BukkitChatPrompts.install(scope, plugin);

PromptSession<Integer> prompt = prompts.start(player, PromptSpec
        .builder(input -> {
            try {
                int value = Integer.parseInt(input.trim());
                return value > 0
                        ? Optional.of(Integer.valueOf(value))
                        : Optional.<Integer>empty();
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        })
        .timeout(Ticks.seconds(30))
        .cancelKeyword("cancel")
        .invalidMessage("请输入正整数，或输入 cancel 取消")
        .build());

prompt.completionSync().thenAcceptAsync(outcome -> {
    if (outcome.status() == PromptStatus.ANSWERED && player.isOnline()) {
        int amount = outcome.value().get().intValue();
        openConfirmMenu(player, amount);
    }
}, scope.syncExecutor());
```

- 同一玩家启动新提示会取消旧提示。
- 框架会取消对应聊天消息，业务插件不用再维护 `AsyncPlayerChatEvent` 监听器。
- 示例给消费结果的回调指定了 `scope.syncExecutor()`，保证即使提示已经完成，后续玩家操作也在主线程执行。

## 聊天控制面板

聊天面板用于字段、开关和列表管理，可以替代只承担参数选择的物品栏页面。

| 类型 | 职责 |
| --- | --- |
| `ChatPanel` | 描述标题、行、按钮、页脚和权限 |
| `BukkitChatPanels` | 启动时声明的按钮命令、玩家会话、刷新和输入 |
| 调用方 | 持有业务对象和保存规则 |

在 `KPlugin.setup()` 中先安装 Command，再复用同一份聊天提示服务安装面板：

```java
import me.kzheart.klib.command.CommandModule;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.ui.chat.BukkitChatPanels;
import me.kzheart.klib.ui.chat.ChatPanel;
import me.kzheart.klib.ui.chat.ChatPanelButton;
import me.kzheart.klib.ui.prompt.BukkitChatPrompts;

CommandModule.install(this);
BukkitChatPrompts prompts = BukkitChatPrompts.install(context().scope(), this);
BukkitChatPanels panels = BukkitChatPanels.install(
        context().scope(), this, "myplugin_panel", prompts);
commands().register("settings", root -> root.playerOnly()
        .permission("myplugin.settings")
        .executes(call -> panels.open((Player) call.sender(), ChatPanel
                .builder(RichText.plain("配置面板"))
                .permission("myplugin.settings")
                .row(RichText.plain("操作："), ChatPanelButton.command(
                        RichText.plain("[查看状态]"), "myplugin status"))
                .row(RichText.plain("文本："), ChatPanelButton.suggest(
                        RichText.plain("[预填]"), "待编辑的原文").hover(RichText.plain("填入聊天框后再发送")))
                .footer(ChatPanelButton.action(RichText.plain("[关闭]"), session -> session.dispose()))
                .pageSize(8).clearLines(12).lifetimeMillis(120000L)
                .build())));
```

- 需要导入 `org.bukkit.entity.Player`。
- 安装调用必须位于启动主线程。
- 按钮命令名应以插件为前缀，**不能与其他插件共享同一个裸标签**。
- 默认发送复用 Lang 的 Bukkit 富文本桥接。自定义宿主可传入 `BiConsumer<Player, RichText>`，不需要把重定位后的 Adventure 类型传入 Bukkit。

### 按钮

| 工厂 | 行为 |
| --- | --- |
| `action(label, callback)` | 调用业务回调 |
| `command(label, text)` | 以玩家权限执行命令 |
| `suggest(label, text)` | 预填输入框 |
| `copy(label, text)` | 复制到剪贴板；旧版客户端不支持时改用 `suggest` |

- `hover(...)` 和 `permission(...)` 返回新的不可变按钮。无权限按钮隐藏，执行时再次校验。
- 预填与复制是客户端展示操作，不会执行其中的文本，也不能作为业务授权手段。
- 旧聊天文本仍留在客户端历史中。

### 刷新、翻页与会话

- `ChatPanel.Builder.guard(Predicate<Player>)` 在点击、刷新和输入完成时检查目标是否仍存在或仍是原版本。
- `ChatPanelSession.refresh(newModel)` 主动替换行数据，`page(index)` 翻页。新模型需保留适当的业务 guard。
- 每次刷新会作废旧按钮。
- 一次动作执行后，若未关闭、切换面板或启动输入，会自动重绘当前模型。
- 会话有效期从打开时开始计时，刷新不延长。
- 每名玩家只保留一个活动面板，打开新面板会关闭旧面板。

### 面板内输入

在 action 回调中发送业务提示，再启动输入（`session` 为 `ChatPanelSession`）：

```java
import java.util.Optional;
import me.kzheart.klib.ui.prompt.PromptSpec;
import me.kzheart.klib.scheduler.Ticks;

session.player().sendMessage("输入新的名称，或输入 cancel 取消");
session.input(PromptSpec.<String>builder(text -> text.trim().isEmpty()
                ? Optional.<String>empty() : Optional.of(text))
        .timeout(Ticks.seconds(60)).build(), currentName,
        value -> {
            // 在主线程重新核对并保存业务值，然后 refresh(newModel) 或重新 open。
        }, () -> session.refresh(currentModel));
```

- 面板会附带“预填当前值”按钮。
- 输入复用 `BukkitChatPrompts`：消息被消费，不向公共聊天广播。
- 解析器只做纯解析，不能访问 Bukkit 可变状态。
- 成功和取消回调都回到所属 Scope 的同步执行器。
- 等待输入期间，权限、玩家会话或目标 guard 变化会拒绝回调。
- 关闭面板只取消自己启动的提示，不取消其他模块后续替换的输入。

### 快捷键、到期与错误

- `hotkeys(onSave, onCancel)` 显式启用 F 保存、潜行+F 取消；默认不接管副手交换。调用方自行保存或丢弃业务草稿，并关闭或刷新面板。
- 到期、退出或 Scope 关闭只撤销面板及提示，**不自动保存业务数据**。
- `ChatPanel.Builder.errorHandler(...)` 由业务选择错误提示。未配置时只记录异常并结束失败会话，不发送固定文案。
- 所有公开面板操作及释放都要求主线程。作用域关闭后，注册的命令立即逻辑停用。

## Paper 富文本标题

<details>
<summary>展开：用 MenuInventoryFactory 支持 MiniMessage 组件标题</summary>

需要组件标题时，可为 `MenuRenderer` 提供 `MenuInventoryFactory`，不替换框架的点击保护和会话管理。`BukkitAdventure` 来自 Lang 模块，使用 Paper 自带的 Adventure 类加载器，避免把内嵌的组件类型传给 Paper。

```java
import me.kzheart.klib.lang.BukkitAdventure;
import me.kzheart.klib.ui.MenuRenderer;

MenuRenderer menus = MenuRenderer.install(
        context().scope(), this, BukkitAdventure::inventory,
        (player, model, click, failure) -> lang.send(player, "menu.failed"));
```

- 此时 YAML 的 `title` 可以包含 MiniMessage 字体、颜色等标签。资源包专属的偏移语法由应用先展开成字体标签。
- 自定义工厂必须返回具有指定 holder 和尺寸的物品栏。返回 null、替换 holder 或尺寸不符都会失败并清理会话。
- 已有的安装重载继续使用 Bukkit 字符串标题。
- 工厂在主线程调用。

</details>

## 线程与生命周期

- `MenuRenderer` 和 `BukkitChatPrompts` 都必须安装到 `Scope`。作用域关闭时，监听器、打开的菜单、刷新任务和未完成提示统一清理。
- 菜单创建、打开、渲染和玩家背包操作必须在 Bukkit 主线程进行。
- `MenuRenderer.open(...)`、`MenuRenderer.render(...)` 和 `MenuHolder.refresh()` 在入口断言主线程，从异步线程调用直接抛 `IllegalStateException`。异步流程中先用 `scope.sync(...)` 或 `thenSync(...)` 切回主线程再开菜单。
- `MenuSession` 关闭后自动从安装它的父作用域摘除，长生命周期作用域中反复开关菜单不会累积已结束的会话。
- 聊天解析器由异步聊天事件调用，只做纯解析；不要在里面访问世界、背包或其他主线程状态。

## 常见坑

- 点击动作抛出普通异常时，渲染器会记录错误并调用 `MenuErrorHandler`。可以在安装时提供统一的玩家提示，但错误处理器本身也应保持轻量。
- 玩家在提示完成前可能下线：回调里必须再次检查 `isOnline()`，且不要长期保存 `Player` 之外的可变菜单状态。

## 相关页面

- [组件与注解](../annotations.md)：`@Menu` 等注解的完整规则
- [Item](item.md)：菜单物品构建
- [Command](command.md)：聊天面板按钮命令
- [Lang](lang.md)：富文本与 `BukkitAdventure`
- [Core](core.md)：作用域与线程切换
