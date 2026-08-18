# klib-ui

`klib-ui` 用于构建物品栏菜单、分页、物品投放区和聊天输入流程。它把点击、拖拽、数字键、双击、关闭归还以及异步聊天事件集中在统一监听器中，业务代码只描述模型和动作。

## 接入模块

使用 klib Gradle 插件：

```kotlin
klib {
    modules {
        ui()
    }
}
```

`ui` 会自动带入 `core` 和 `item`，因此同样需要声明 CodeMC 仓库以解析 Item-NBT-API，配置见
[Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。直接依赖时加入：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-ui:<klib-version>")
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156")
}
```

直接依赖还需保证 `klib-core`、`klib-item` 及物品标签所需运行时依赖可用。

## 创建并打开菜单

菜单流程分为三步：构建 `MenuTemplate`、编译为不可变 `MenuModel`、交给一个作用域持有的 `MenuRenderer` 打开。

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

菜单行数必须是 1 到 6；使用布局时行数必须与菜单行数一致，每行恰好 9 个字符，空格表示空槽。也可以用 `slot(...)` 或 `MenuCompiler.compileSlots(...)` 直接绑定槽位。模板编译后得到不可变模型，可以为多个玩家重复使用。

`MenuEntry` 保存物品快照和点击动作。框架会拦截已编译条目的移动，并统一处理双击、数字键、拖拽和 shift-click，业务动作不应再自行修改菜单中的展示物品。

## 从配置编译菜单

配置模块把 YAML 解码为普通 `Map<String, Object>` 后，可用 `MenuCompiler.compileYaml(...)`。支持 `title`、`rows`、`cancel-clicks`、`layout`、`items` 和 `slots`。业务通过 `YamlItemResolver` 把配置中的物品 ID 解析成 `MenuEntry`，因此配置层不需要知道 Bukkit 或第三方物品 API。

## 分页

`Paginator<T>` 会复制数据并把请求页码限制到合法范围：

```java
Paginator<Listing> paginator = new Paginator<Listing>(listings, 45);
Page<Listing> page = paginator.page(requestedPage);

for (Listing listing : page.values()) {
    // 将当前页条目编译到菜单槽位
}
```

分页索引从 0 开始；即使数据为空，`pageCount()` 也返回 1。上一页和下一页按钮应根据 `Page` 的当前索引和总页数决定是否展示。

## 接收玩家投放的物品

需要让玩家把物品放入菜单时，在 `open(...)` 的会话配置回调中添加 `DropZoneController`。投放区持有收到物品的克隆，支持放置、shift 插入、拖拽和取回。

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

菜单关闭、被其他菜单替换或插件作用域关闭时，会话会排空投放区，并把物品交给打开菜单时配置的归还目标。`MenuRenderer` 默认先归还玩家背包，溢出时掉落在玩家位置。不要把真实玩家物品作为普通 `MenuEntry` 展示；可取回物品应放进投放区。

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

prompt.completionSync().thenAccept(outcome -> {
    if (outcome.status() == PromptStatus.ANSWERED && player.isOnline()) {
        int amount = outcome.value().get().intValue();
        openConfirmMenu(player, amount);
    }
});
```

同一玩家启动新提示会取消旧提示。框架会取消对应聊天消息，不会让业务插件再维护 `AsyncPlayerChatEvent` 监听器。`completionSync()` 会把完成结果切回作用域的同步调度器，适合继续访问玩家和打开菜单。

## 生命周期与错误边界

- `MenuRenderer` 和 `BukkitChatPrompts` 都必须安装到 `Scope`。作用域关闭时监听器、打开的菜单、刷新任务和未完成提示会统一清理。
- 菜单创建、打开、渲染和玩家背包操作必须在 Bukkit 主线程进行。`MenuRenderer.open(...)`、`MenuRenderer.render(...)` 和 `MenuHolder.refresh()` 会在入口断言主线程，从异步线程调用直接抛出 `IllegalStateException`；异步流程中先用 `scope.sync(...)` 或 `thenSync(...)` 切回主线程再开菜单。
- `MenuSession` 关闭后会自动从安装它的父作用域摘除，因此长生命周期作用域中反复开关菜单不会累积已结束的会话。
- 聊天解析器由异步聊天事件调用，只做纯解析；不要在解析器中访问世界、背包或其他主线程状态。
- 点击动作抛出普通异常时，渲染器会记录错误并调用 `MenuErrorHandler`。可以在安装时提供统一的玩家提示，但错误处理器本身也应保持轻量。
- 玩家在提示完成前可能下线；回调必须再次检查 `isOnline()`，且不要长期保存 `Player` 之外的可变菜单状态。

分页菜单与聊天输入的完整组合方式见本页“完整示例”。
