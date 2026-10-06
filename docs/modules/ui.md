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

聊天面板用于字段、开关和列表编辑，交互方式接近 Adyeshach 的 NPC 编辑器：整页一条消息、一行多个 `[按钮]`、点击后按实时状态刷新。

| 类型 | 职责 |
| --- | --- |
| `ChatPanel` | 页面定义：id、标题、权限、guard 和每次显示都会调用的页面函数 |
| `ChatPanelView` | 页面函数里的构建器：分组、行、按钮、页脚 |
| `ChatPanelButton` | 回调、命令、预填、复制按钮，可带悬停、当前值和 (R) 重置 |
| `ChatPanelSession` | 玩家会话：跳转、返回、状态行、输入 |
| `BukkitChatPanels` | 启动时声明的按钮命令、路由、刷新和告示牌输入 |

在 `KPlugin.setup()` 中先安装 Command，再复用同一份聊天提示服务安装面板：

```java
import me.kzheart.klib.command.CommandModule;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.ui.chat.BukkitChatPanels;
import me.kzheart.klib.ui.chat.ChatPanel;
import me.kzheart.klib.ui.chat.ChatPanelButton;
import me.kzheart.klib.ui.chat.ChatPanelInput;
import me.kzheart.klib.ui.prompt.BukkitChatPrompts;
import org.bukkit.entity.Player;

CommandModule.install(this);
BukkitChatPrompts prompts = BukkitChatPrompts.install(context().scope(), this);
BukkitChatPanels panels = BukkitChatPanels.install(
        context().scope(), this, "myplugin_panel", prompts);

ChatPanel flight(Player target) {
    return ChatPanel.builder("flight", RichText.plain("飞行设置"))
            .permission("myplugin.flight")
            .guard(viewer -> target.isOnline())
            .content(view -> view
                    .group("飞行")
                    .buttons(
                            ChatPanelButton.action("fly", RichText.plain("允许飞行"),
                                    session -> target.setAllowFlight(!target.getAllowFlight()))
                                    .value(target.getAllowFlight() ? "开" : "关"),
                            ChatPanelButton.action("speed", RichText.plain("速度"),
                                    session -> session.input(ChatPanelInput.of(Speeds::parse)
                                            .hint(RichText.plain("输入 0~1 的速度"))
                                            .current(String.valueOf(target.getFlySpeed())),
                                            value -> {
                                                target.setFlySpeed(value);
                                                session.status("已设置飞行速度");
                                            }))
                                    .value(String.valueOf(target.getFlySpeed()))
                                    .hover(RichText.plain("点击输入新的速度"))
                                    .reset(session -> target.setFlySpeed(0.1f))))
            .build();
}

commands().register("flight", root -> root.playerOnly()
        .permission("myplugin.flight")
        .executes(call -> panels.open((Player) call.sender(), flight((Player) call.sender()))));
```

- `Speeds::parse` 是业务的纯解析函数，返回 `Optional<Float>`。
- 安装调用必须位于启动主线程；按钮命令名应以插件为前缀，**不能与其他插件共享同一个裸标签**。
- 默认发送复用 Lang 的 Bukkit 富文本桥接；自定义宿主可通过 `ChatPanelOptions.builder().sender(...)` 传入 `BiConsumer<Player, RichText>`。

### 页面函数与刷新

- 页面函数在打开、每次点击、翻页和输入完成后**重新调用**，按钮上的值总是实时状态。
- 动作执行后，面板在下一 tick 重画当前页；多次请求合并为一次。动作里调用 `session.open(...)` 或 `session.input(...)` 时显示新的页面或输入状态。
- 动作里直接调用 `panels.open(...)` 重新打开时立即发送，不再额外重画；本次动作设置的状态行会保留。
- 整页作为**一条消息**发送，并补满固定行数（默认 20 行），旧面板被完整顶出可见区。
- 布局自上而下：标题行、正文、状态行、导航行。未展开的聊天栏只显示末尾约 10 行，所以结果提示和导航始终可见。
- 正文超出容量时分页，分组尽量整组留在同一页。

| `ChatPanelView` 方法 | 作用 |
| --- | --- |
| `subtitle(text)` | 标题后追加 `› 副标题` |
| `group(title)` | 开始一个分组，标题显示为 `名称 ···` |
| `buttons(...)` | 一行多个按钮，按 `ChatPanel.Builder.perLine`（默认 4）或指定数量换行 |
| `line(text, buttons...)` | 一行文字后接按钮 |
| `text(text)` / `blank()` | 纯文本行 / 空行 |
| `footer(buttons...)` | 追加到导航行 |

### 按钮与稳定地址

按钮命令为 `/<命令> <页面 id> <按钮 id>`，不含一次性令牌。点击时框架按实时状态重建该页面再查找按钮，聊天记录里的旧按钮同样可用。

| 工厂 | 行为 |
| --- | --- |
| `action(id, label, callback)` | 调用业务回调 |
| `command(label, text)` | 以玩家权限执行命令后刷新面板 |
| `suggest(label, text)` | 预填聊天框 |
| `copy(label, text)` | 复制到剪贴板；旧版客户端不支持时改用 `suggest` |

- 按钮渲染为 `[名称 当前值 (R)]`：`value(...)` 显示当前值，`reset(callback)` 添加 (R) 重置，`hover(...)` 设置悬停说明。
- 页面 id 与按钮 id 只能包含字母、数字和 `_ . : + -`。同一会话中同 id 的页面视为同一页面，后打开的定义替换先前的定义。
- **id 应由目标决定**，如 `lore.3.edit`。列表内容可能变化时，把内容摘要放进 id，避免旧按钮作用到移位后的新内容。
- 旧按钮的处理：

| 情况 | 结果 |
| --- | --- |
| 页面和按钮都在 | 正常执行，并切换到该页面 |
| 按钮在实时页面中已不存在 | 状态行提示，重画当前页 |
| 页面已不在会话中，或一次性页面已使用 | 状态行提示，重画当前页 |
| 缺少按钮权限 | 状态行提示，不执行 |
| 页面权限或 guard 不满足 | 聊天提示；若是当前页则关闭会话 |
| 会话已关闭或空闲过期 | 聊天提示重新打开 |

- 每次点击都重新检查页面权限、guard 与按钮权限；无权限按钮不显示。所有丢弃都会提示玩家，并在 `chat-panel` 调试模块记录。
- 确认框使用 `ChatPanel.Builder.once()`：每次打开都是新地址，首个动作执行后整页失效；动作未跳转时自动返回上一页。

### 状态行、会话与返回

- `session.status(...)` 把结果写入状态行，直到下一次操作；不要在动作里直接 `sendMessage`，否则会被随后的整页刷新顶掉。
- 动作之外的业务代码可调用 `panels.notice(player, text)`：玩家开着面板时写入状态行，否则直接发送。
- `session.open(panel)` 进入子页面并记录返回路径，导航行显示 `[返回]`；`session.back()` 返回上一页。
- `panels.open(player, panel)` 从外部打开：复用会话、清空返回路径并立即发送；权限或 guard 不满足时提示玩家并返回空。
- `[关闭]` 或 `session.dispose()` 发送一屏空行和“面板已关闭”，把面板文字顶走。
- 会话按空闲时间过期（默认 15 分钟，每次操作续期），由 `ChatPanelOptions.builder().idleMillis(...)` 调整。每名玩家只有一个会话。

### 面板内输入

在动作中调用 `session.input(...)`；面板保持显示，状态行显示提示、`[预填当前值]` 和 `[取消输入]`：

```java
session.input(ChatPanelInput.text(64)
                .hint(RichText.plain("输入新的名称"))
                .current(currentName),
        value -> {
            rename(value);
            session.status("已修改名称");
        });
```

- 提交、取消或超时后自动回到发起输入的页面；回调里再次 `input(...)` 或 `open(...)` 时按新的状态显示。
- 取消或超时在状态行提示；需要额外处理时使用带 `cancelled` 回调的重载。
- 解析器在异步聊天线程调用，只做纯解析；回调回到所属 Scope 的主线程，执行前重新检查页面 guard 和发起按钮的权限。
- 点击面板上的其他按钮会放弃进行中的输入。关闭面板只取消自己启动的提示。

### 告示牌输入

- 服务端提供 Paper 虚拟告示牌 API 时，导航行显示 `[输入：聊天]` / `[输入：告示牌]` 切换按钮，偏好按玩家记住到插件关闭。
- 选择告示牌时，当前值按每行 15 个字符预填前三行，提交时拼接前三行；第四行为提示。
- 当前值超过 45 个字符时自动改用聊天输入。多行或很长的内容用 `ChatPanelInput.sign(false)` 固定使用聊天。
- 告示牌只发送给该玩家，不修改世界方块。可通过 `ChatPanelOptions.builder().signInput(false)` 关闭，或传入自定义 `ChatPanelSignInput`。

### 选项、快捷键与错误

| `ChatPanelOptions.Builder` | 默认 | 说明 |
| --- | --- | --- |
| `lines(int)` | 20 | 每页总行数 |
| `idleMillis(long)` | 15 分钟 | 空闲过期时间 |
| `sender(...)` | Lang 富文本 | 自定义发送 |
| `signInput(...)` | 自动检测 | 关闭或替换告示牌输入 |
| `text(ChatPanelText, RichText)` | 中文默认文案 | 替换提示和内置按钮文字 |

- `hotkeys(onSave, onCancel)` 显式启用 F 保存、潜行+F 取消；默认不接管副手交换。
- 到期、退出或 Scope 关闭只撤销面板及提示，**不自动保存业务数据**。
- 动作抛出异常时记录日志并在状态行显示失败提示；`ChatPanel.Builder.errorHandler(...)` 可改为业务提示。
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
