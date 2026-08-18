# Core 模块

状态：稳定公开模块
模块名：`core`
制品：`me.kzheart.klib:klib-core`

`klib-core` 是所有 Klib 插件的生命周期底座。它提供 `KPlugin`、可组合的 `Scope`、与作用域绑定的 Bukkit 事件和任务，以及统一日志。其他大多数模块都会间接引入它。

## 何时使用

以下情况应直接使用 Core：

- 希望插件启用、重载或关闭时自动释放监听器、任务和其他资源；
- 希望把某个功能的生命周期隔离在独立子作用域中；
- 需要安全地执行同步延迟任务、循环任务或“异步计算后回主线程”；
- 希望用简洁的 DSL 注册 Bukkit 事件；
- 正在实现一个可被其他模块发现的作用域能力。

即使只选择其他 Klib 模块，也无需重复声明 `core`；Gradle 插件会从模块依赖图中自动补齐它。

## 接入

推荐使用 Klib Gradle 插件。插件会解析模块闭包、打包并重定位所需实现：

```kotlin
plugins {
    id("me.kzheart.klib") version "<gradle-plugin-version>"
}

klib {
    name("MyPlugin")
    main("com.example.myplugin.MyPlugin")
    version(project.version.toString())
    targetPackage("com.example.myplugin")
    modules {
        core()
    }
}
```

仍需按目标服务端添加 `spigot-api` 或 `paper-api` 的 `compileOnly` 依赖。

高级场景也可以直接声明制品：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-core:<klib-version>")
}
```

直接依赖时，业务项目需要自行处理运行时打包、重定位和 `plugin.yml`；不要假设服务端已经安装 Klib。

## 快速开始

插件主类继承 `KPlugin`，只实现 `setup`。不要再覆盖 `onEnable` 或 `onDisable`，这两个入口由 Klib 固定管理。

```java
package com.example.myplugin;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;
import org.bukkit.event.player.PlayerJoinEvent;

public final class MyPlugin extends KPlugin {
    @Override
    protected void setup(Scope root) {
        root.on(PlayerJoinEvent.class, event ->
                logger().info(event.getPlayer().getName() + " joined"));

        root.every(Ticks.seconds(30), () ->
                logger().debug("heartbeat", "plugin is alive"));
    }
}
```

`setup` 中注册的资源属于根作用域。插件关闭时，根作用域会自动关闭；如果初始化中途失败，已经安装的资源也会被清理，然后插件被禁用。

## 组织功能生命周期

### 安装可释放资源

任何实现 `Disposable` 的资源都可以交给作用域持有：

```java
root.install(() -> closeDatabaseConnection());
```

资源按安装顺序的逆序释放。因此应先安装被依赖的底层资源，再安装依赖它的上层资源。

### 使用子作用域

子作用域适合表示一项可整体重建或关闭的业务功能：

```java
root.scope("arena", arena -> {
    ArenaRuntime runtime = arena.install(new ArenaRuntime());

    arena.on(PlayerJoinEvent.class, event ->
            runtime.handleJoin(event.getPlayer()));
    arena.every(Ticks.seconds(1), runtime::tick);
});
```

子作用域会继承父作用域注册的能力，但自己的资源仍独立归属。关闭父作用域会连同子作用域一起释放。

### 重建插件资源图

`root.rebuild()` 会先逆序释放当前资源，再重新执行创建根作用域时的初始化逻辑。使用 `KPlugin` 时，通常通过 `root::rebuild` 响应配置变化：

```java
config.onChange(root::rebuild);
```

也可以从插件实例调用 `rebuild()`。它返回是否成功；重建失败时，残留资源会被清理且插件会被禁用，不能继续使用旧资源图。

所有需要跨重建保留的状态都应放在作用域之外，或者从持久化数据重新构造。不要把已经被旧作用域释放的对象继续缓存到静态字段中。

### 生命周期锁模型

整棵作用域树共享同一把生命周期锁：子作用域创建时直接复用父作用域的锁。因此 `install`、`remove`、`scope(...)`、`registerCapability`、`findCapability`、`rebuild()` 和 `close()` 在整棵树范围内串行执行，重建或关闭进行期间，其他线程对树上任意作用域的这些调用都会等待。

由此产生几条需要注意的行为：

- `rebuild()` 在持锁状态下执行 `setup`。初始化逻辑里的阻塞等待（例如等待另一个线程回调后再继续）会一直占用这把锁，可能造成死锁；初始化应保持非阻塞，耗时工作放到 `async` 或初始化完成之后。
- `Scope.dispose()` 与 `Scope.close()` 等价，`dispose()` 只是让作用域能作为父作用域的一项资源被释放。
- 关闭是可重入安全但不是可重入调用：已经关闭的作用域再次 `close()` 无副作用，而在关闭或重建尚未完成时再次调用 `close()`（例如在自身的释放回调里关闭同一个作用域）会抛出 `IllegalStateException`。释放回调只负责释放自己的资源即可。
- 关闭过程中即使部分资源抛出异常，其余资源仍会继续释放，最后以聚合异常报告失败；此时作用域被标记为已关闭，不会退回可用状态。

## 事件

最常用的事件注册形式是 `Scope.on`：

```java
root.on(BlockBreakEvent.class, event -> handleBreak(event));
```

需要控制 Bukkit 事件参数时使用完整重载：

```java
root.on(
        BlockBreakEvent.class,
        EventPriority.HIGH,
        true,
        event -> handleBreak(event));
```

第三个参数是 Bukkit 的 `ignoreCancelled`。返回的 `Disposable` 可以提前释放；通常无需保存它，因为作用域关闭时会自动注销订阅。当最后一个同类路由订阅被释放时，底层 Bukkit 监听器也会注销。

事件处理器中的异常会被记录，不会阻止同一路由的其他订阅继续接收事件。

## 调度

### 同步任务

`after` 和 `every` 使用 Bukkit 同步调度器：

```java
root.after(Ticks.of(1), () -> logger().info("next tick"));

root.every(Ticks.seconds(5), () -> updateOnlinePlayers());
```

`every` 的周期至少为 1 tick；`after` 可以使用 0 tick。它们返回 `TaskHandle`，可通过 `cancel()` 提前取消。一次性任务完成后会自动从作用域中移除，避免在长生命周期作用域里累积句柄。

### 异步计算后回主线程

```java
root.async(() -> repository.loadPlayer(playerId))
        .thenSync(profile -> applyProfile(player, profile))
        .onError(error -> logger().error("读取玩家数据失败", error));
```

异步供应器在 Bukkit 异步任务中执行，`thenSync` 与 `onError` 回调通过同步调度器派发。任务失败时只调用错误回调；任务取消时两类回调都不会调用。即使任务已经完成，再注册对应回调仍会被派发。

作用域关闭会取消其任务；已经排队但尚未执行的同步回调也会跳过。异步取消不会强制中断正在运行的供应器，因此供应器本身仍应设置 I/O 超时并尽快结束。

### 从任意线程回主线程

`Scope.sync(Runnable)` 把任务投递到服务器主线程的下一 tick，可以从任意线程调用，是"回主线程"的官方入口：

```java
root.sync(() -> player.sendMessage("数据已加载"));
```

它返回 `TaskHandle`，可提前取消；作用域关闭后已排队的任务不再执行。等价的旧写法是 `root.after(Ticks.of(0), ...)`，新代码请使用 `sync`。

### 桥接 CompletionStage

`klib-data`、`klib-script` 和 `klib-remote` 返回 JDK `CompletionStage`。**它的 `thenApply`、`thenAccept` 等非 `Async` 回调运行在完成该阶段的线程（JDBC、文件 I/O 或诊断线程），不会自动切回主线程**，在其中直接调用 Bukkit API 是典型的崩溃来源。

`Scope.syncExecutor()` 返回投递到主线程的 `Executor`，可直接与 JDK 组合子搭配：

```java
storage.get("profile", playerId)
        .thenAcceptAsync(value -> player.sendMessage("等级 " + decodeLevel(value)),
                root.syncExecutor());
```

`AsyncTasks` 提供更短的写法，并在作用域关闭后自动跳过回调、把 `CompletionException` 解包后交给错误回调：

```java
import me.kzheart.klib.scheduler.AsyncTasks;

AsyncTasks.thenSync(
        storage.get("profile", playerId),
        root,
        value -> player.sendMessage("等级 " + decodeLevel(value)),
        error -> logger().error("读取玩家数据失败", error));
```

完整回路（异步读库 → 回主线程 → 更新玩家）：

```java
public void loadAndApply(final Player player) {
    CompletionStage<Optional<byte[]>> loading =
            sessionStage.thenCompose(session -> session.get("profile", player.getUniqueId().toString()));

    AsyncTasks.thenSync(loading, root,
            stored -> {
                // 主线程：可以安全访问玩家与世界状态
                player.setLevel(stored.isPresent() ? decodeLevel(stored.get()) : 0);
            },
            error -> logger().error("读取玩家数据失败", error));
}
```

需要多步组合又不想每步都传执行器时，用 `AsyncTasks.onSync(stage, scope)` 得到一个在主线程完成的等价阶段，之后的非 `Async` 回调即运行在主线程。

## 日志

`KPlugin.logger()` 返回 `KLogger`：

```java
logger().info("开始加载竞技场");
logger().success("竞技场加载完成");
logger().warn("未找到可选依赖");
logger().error("保存失败", exception);

logger().setDebug("arena", true);
logger().debug("arena", "players=" + players.size());
```

调试日志默认关闭，可按模块或用 `*` 总开关启用。调试输出以 `INFO` 级别发出，由模块开关而非日志级别过滤；这样服务器不必修改日志配置就能看到调试信息，代价是无法用日志级别单独屏蔽它们。

`info`、`success`、`warn` 和 `error` 都有携带模块名的重载，模块名只影响最近日志缓冲中的来源标记：

```java
logger().warn("arena", "竞技场配置缺少出生点");
```

`recentLines()` 返回最近日志的只读快照，格式为 `时间戳 级别 [模块] 消息`，未携带模块名的日志记为 `core`。它适合诊断上报；不要把它当作持久日志存储。

行首符号默认使用 `❯✔⚠✖`。GBK 等无法表示这些字符的 Windows 控制台可以在启动参数中加入 `-Dklib.logger.ascii=true`，改用 ASCII 前缀 `>`、`+`、`!`、`x`。该属性在类初始化时读取一次，运行期修改无效。

## 轻量诊断快照

`DiagnosticSource` 是 Core 提供的只读内存快照边界。`ExecutorScheduler` 和
`BukkitSchedulerAdapter` 会报告调度后端、作用域状态和执行器是否关闭；采集过程不会枚举服务器任务，
也不会访问文件、网络或数据库。

选择 `remote` 模块后，可用 `KlibDiagnosticContributor` 把这些快照加入 Incident。自定义实现同样只能读取
已经缓存的轻量状态，禁止在 `diagnosticSnapshot()` 中现场执行阻塞 I/O。完整接线见
[Remote 的排障上下文](remote.md#排障上下文与-contributor)。

## 生命周期与线程约束

- `KPlugin.setup`、Bukkit 命令注册以及绝大多数 Bukkit API 操作应在服务器主线程完成。
- `after`、`every`、`sync`、`thenSync` 和 `onError` 的回调运行在同步调度器；`async` 的供应器不在主线程。
- 不要在 `async` 供应器中读写世界、实体、背包等要求主线程的 Bukkit 状态；先完成纯 I/O 或计算，再在 `thenSync` 中应用结果。
- JDK `CompletionStage` 的 `thenApply`/`thenAccept`/`thenCompose` 等非 `Async` 回调运行在完成该阶段的线程，**不是主线程**；触碰 Bukkit 状态前必须经 `Scope.sync`、`Scope.syncExecutor()` 或 `AsyncTasks` 切回主线程。
- Bukkit 事件处理器运行在哪个线程由事件本身决定；异步事件的处理器不会自动切回主线程。
- `Scope` 的生命周期操作会串行化，但不要并发触发同一作用域的 `rebuild`、`close` 或资源注册。
- 作用域关闭后，再注册资源、加载能力或访问已关闭的模块运行时会失败。

## 注意事项

- 一个作用域内同一种 capability 类型只能注册一次；子作用域可以覆盖父作用域能力。
- 子作用域名称在同一父作用域中必须唯一。
- 不要手工释放一个资源后仍让它长期留在作用域中；自定义一次性资源完成时可调用 `scope.remove(this)`。
- `KPlugin.instance()` 和 `rootScope()` 只在插件处于活动状态时可用。
- 清理过程中即使部分资源抛出异常，Klib 仍会继续释放其余资源，最后以聚合异常报告失败。

## 相关模块

- [Config](config.md)：类型化 YAML、热重载与迁移。
- [Lang](lang.md)：可重载消息目录与 Bukkit 消息路由。
- [Command](command.md)：由作用域持有的类型化命令树。
- 完整接线方式见本页“快速开始”和“完整示例”。
