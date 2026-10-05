# Core 模块

所有 Klib 插件的生命周期底座：`KPlugin`、可组合的 `Scope`、绑定作用域的 Bukkit 事件和任务，以及统一日志。

| 模块名 | 制品 |
| --- | --- |
| `core` | `me.kzheart.klib:klib-core` |

其他大多数模块都会间接引入它。只选其他模块时**无需**重复声明 `core`，Gradle 插件会从模块依赖图中自动补齐。

适合直接使用 Core 的场景：

- 插件启用、重载或关闭时，自动释放监听器、任务和其他资源；
- 把某个功能的生命周期隔离在独立子作用域中；
- 安全地执行同步延迟任务、循环任务，或「异步计算后回主线程」；
- 用简洁的 DSL 注册 Bukkit 事件；
- 实现一个可被其他模块发现的作用域能力。

## 快速开始

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

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。仍需按目标服务端添加 `spigot-api` 或 `paper-api` 的 `compileOnly` 依赖。

插件主类继承 `KPlugin`，覆盖无参 `setup()`。**不要再覆盖 `onEnable` 或 `onDisable`**，这两个入口由 Klib 固定管理。

```java
package com.example.myplugin;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.scheduler.Ticks;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class MyPlugin extends KPlugin {
    @Override
    protected void setup() {
        events().register(new JoinListener());
        tasks().every(Ticks.seconds(30), () ->
                logger().debug("heartbeat", "plugin is alive"));
    }

    private final class JoinListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            logger().info(event.getPlayer().getName() + " joined");
        }
    }
}
```

- `root` 指根作用域：在无参 `setup()` 中用 `context().scope()` 取得，或改为覆盖 `setup(Scope root)`。**两种入口不要同时覆盖。**
- 插件关闭时根作用域自动关闭。
- 初始化中途失败时，已安装的资源会被清理，然后插件被禁用。
- 示例里的 `repository`、`applyProfile`、`ArenaRuntime` 等是业务代码，不是 Klib API。插件完整入口见 [快速开始](../../README.md)。

## 服务入口与组件

`KPlugin` 的服务入口都绑定插件生命周期，注册的资源属于根作用域：

| 入口 | 用途 |
| --- | --- |
| `events().register(Listener)` | 复用 Bukkit `@EventHandler` |
| `tasks().register(instance)` | 读取 `@Every` 声明的固定任务 |
| `tasks().every(...)` 等 | 直接创建任务 |
| `commands()` | 命令，见 [Command](command.md) |
| `configs()` | 配置，见 [Config](config.md) |
| `components().install(instance)` | 安装组件，返回可关闭的 `ComponentHandle` |

- `KComponent` 提供相同的服务入口。
- 普通对象可声明 public、无参、返回 void 的 `@OnStart`、`@OnStop`。
- 初始化失败会回滚；关闭时先清理注册的资源，再执行 `@OnStop`。
- 没有自动扫描、注入或隐式线程切换。

完整签名、线程和释放规则见 [组件与注解](../annotations.md)。

## 作用域

### 安装可释放资源

任何实现 `Disposable` 的资源都可以交给作用域持有：

```java
root.install(() -> closeDatabaseConnection());
```

资源按安装顺序的**逆序**释放。先安装被依赖的底层资源，再安装依赖它的上层资源。

### 使用子作用域

子作用域表示一项可整体重建或关闭的业务功能：

```java
root.scope("arena", arena -> {
    ArenaRuntime runtime = arena.install(new ArenaRuntime());

    arena.on(PlayerJoinEvent.class, event ->
            runtime.handleJoin(event.getPlayer()));
    arena.every(Ticks.seconds(1), runtime::tick);
});
```

- 子作用域继承父作用域注册的能力，但资源独立归属。
- 关闭父作用域会连同子作用域一起释放。

### 重建插件资源图

`Scope.rebuild()` 先逆序释放当前作用域的资源，再重新执行创建它时的初始化逻辑。

配置重载通常只更新配置值和业务状态。确实要重建监听器、任务等资源时，把它们放进**不含命令声明**的子作用域：

```java
Scope gameplay = root.scope("gameplay", scope -> {
    ArenaRuntime runtime = scope.install(new ArenaRuntime());
    scope.on(PlayerJoinEvent.class, event -> runtime.handleJoin(event.getPlayer()));
    scope.every(Ticks.seconds(1), runtime::tick);
});
config.onChange(gameplay::rebuild);
```

命令规则：

- 命令在插件启动时一次声明，处理器通过稳定的服务入口读取最新配置和业务状态。
- **不要**把命令放进上面的可重建子作用域，也不要用 `config.onChange(root::rebuild)` 重建含命令的根资源图。
- 命令作用域关闭只保证立即停用执行、补全与权限检查，不保证物理节点立即消失，也不支持再次注册。详见 [Command 模块](command.md#启动注册配置重载与逻辑停用)。

不含命令的资源图：

- 可以用 `root.rebuild()`，也可以从插件实例调用 `rebuild()`。
- 插件的 `rebuild()` 返回是否成功。失败时残留资源会被清理、插件被禁用，**不能**继续使用旧资源图。
- 需要跨重建保留的状态，放在被重建的作用域之外，或从持久化数据重新构造。
- 不要把已被旧作用域释放的对象继续缓存在静态字段中。

### 生命周期锁模型

<details>
<summary>展开：整棵作用域树共享一把锁带来的行为</summary>

整棵作用域树共享同一把生命周期锁，子作用域创建时直接复用父作用域的锁。

- `install`、`remove`、`scope(...)`、`registerCapability`、`findCapability`、`rebuild()` 和 `close()` 在整棵树范围内串行执行。
- 重建或关闭进行期间，其他线程对树上任意作用域的这些调用都会等待。

由此产生的行为：

- `rebuild()` 在持锁状态下执行 `setup`。初始化里的阻塞等待（例如等另一个线程回调再继续）会一直占用这把锁，可能造成**死锁**。初始化应保持非阻塞，耗时工作放到 `async` 或初始化完成之后。
- `Scope.dispose()` 与 `Scope.close()` 等价。`dispose()` 只是让作用域能作为父作用域的一项资源被释放。
- 已经关闭的作用域再次 `close()` 无副作用。
- 关闭或重建尚未完成时再次调用 `close()`（例如在自身的释放回调里关闭同一个作用域）会抛出 `IllegalStateException`。释放回调只负责释放自己的资源。
- 关闭中部分资源抛异常时，其余资源仍继续释放，最后以聚合异常报告失败；作用域被标记为已关闭，不会退回可用状态。

</details>

## 事件

```java
root.on(BlockBreakEvent.class, event -> handleBreak(event));
```

需要控制 Bukkit 事件参数时用完整重载：

```java
root.on(
        BlockBreakEvent.class,
        EventPriority.HIGH,
        true,
        event -> handleBreak(event));
```

- 第三个参数是 Bukkit 的 `ignoreCancelled`。
- 返回的 `Disposable` 可以提前释放。通常无需保存，作用域关闭时自动注销订阅。
- 最后一个同类路由订阅被释放时，底层 Bukkit 监听器也会注销。
- 处理器中的异常会被记录，不影响同一路由的其他订阅继续接收事件。

## 调度

| 方法 | 运行线程 | 返回 | 说明 |
| --- | --- | --- | --- |
| `after(ticks, task)` | Bukkit 同步调度器 | `TaskHandle` | 可用 0 tick；完成后自动从作用域移除 |
| `every(ticks, task)` | Bukkit 同步调度器 | `TaskHandle` | 周期至少 1 tick |
| `sync(task)` | 主线程下一 tick，可从任意线程调用 | `TaskHandle` | 「回主线程」的官方入口 |
| `async(supplier)` | Bukkit 异步任务 | 链式 | 接 `thenSync` / `onError` |
| `syncExecutor()` | 主线程 | `Executor` | 与 JDK `CompletionStage` 组合 |

`TaskHandle` 可通过 `cancel()` 提前取消。一次性任务完成后自动移除，长生命周期作用域里不会累积句柄。

### 同步任务

```java
root.after(Ticks.of(1), () -> logger().info("next tick"));

root.every(Ticks.seconds(5), () -> updateOnlinePlayers());
```

### 异步计算后回主线程

```java
root.async(() -> repository.loadPlayer(playerId))
        .thenSync(profile -> applyProfile(player, profile))
        .onError(error -> logger().error("读取玩家数据失败", error));
```

- 供应器在 Bukkit 异步任务中执行；`thenSync` 与 `onError` 通过同步调度器派发。
- 失败时只调用错误回调；取消时两类回调都不调用。
- 任务已完成后再注册回调，仍会被派发。
- 作用域关闭会取消任务，已排队未执行的同步回调也会跳过。
- 异步取消**不会**强制中断正在运行的供应器。供应器本身仍应设置 I/O 超时并尽快结束。

### 从任意线程回主线程

```java
root.sync(() -> player.sendMessage("数据已加载"));
```

- 可提前取消；作用域关闭后已排队的任务不再执行。
- 等价的旧写法是 `root.after(Ticks.of(0), ...)`，新代码请用 `sync`。

### 桥接 CompletionStage

`klib-data`、`klib-script` 和 `klib-remote` 返回 JDK `CompletionStage`。**它的 `thenApply`、`thenAccept` 等非 `Async` 回调可能由完成阶段的线程执行，也可能在注册回调时直接执行，不保证在 Bukkit 主线程运行**，在其中直接调用 Bukkit API 是典型的崩溃来源。

`Scope.syncExecutor()` 返回投递到主线程的 `Executor`，可直接与 JDK 组合子搭配：

```java
storage.get("profile", playerId)
        .thenAcceptAsync(value -> player.sendMessage("等级 " + decodeLevel(value)),
                root.syncExecutor());
```

- 它与 `Scope.sync(...)` 共用作用域任务句柄：提交时已关闭会跳过，提交后、执行前关闭也会取消回调，不会在失效作用域上产生副作用。
- Bukkit 后端保持同步调度器原有的排队顺序，不因这个适配器多一轮延迟。

`AsyncTasks` 写法更短：作用域关闭后自动跳过回调，并把 `CompletionException` 解包后交给错误回调。

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

多步组合中，**每个**访问 Bukkit 状态的回调都显式使用 `scope.syncExecutor()`。即使阶段由 `AsyncTasks.onSync(stage, scope)` 在主线程完成，完成后从其他线程追加的非 `Async` 回调也不保证在主线程运行。

## 冷却

`Cooldowns<K>` 按任意键记录冷却截止时间，线程安全。它实现 `Disposable`，安装进作用域后随作用域关闭清空。

```java
import me.kzheart.klib.cooldown.Cooldowns;

// 以玩家 UUID 为键：自动安装进作用域，玩家退出时移除其冷却
Cooldowns<UUID> skillCd = Cooldowns.perPlayer(root);

Cooldowns.Attempt attempt = skillCd.tryAcquire(player.getUniqueId(), Duration.ofSeconds(8));
if (!attempt.acquired()) {
    player.sendMessage("冷却中，还剩 " + attempt.remaining().getSeconds() + " 秒");
    return;
}
```

键可以是任意类型。按武器、按技能等场景用复合键，不需要拼字符串：

```java
Cooldowns<WeaponKey> weaponCd = root.install(Cooldowns.create());
weaponCd.set(key, Duration.ofSeconds(5));       // 无条件设置
weaponCd.reduce(key, Duration.ofSeconds(2));    // 缩短，缩到零即结束
weaponCd.extend(key, Duration.ofSeconds(1));    // 延长，未冷却时从现在开始
weaponCd.clearIf(k -> k.owner().equals(playerId));
```

| 规则 | 行为 |
| --- | --- |
| `tryAcquire` | 原子操作。冷却已结束：开始新冷却并返回成功；仍在冷却：不修改，返回剩余时间 |
| 时长为零 | 不开始冷却 |
| 负时长 | 抛 `IllegalArgumentException` |
| 超大时长 | 饱和到 `Long.MAX_VALUE` 毫秒 |
| 过期清理 | 读取时移除，每 256 次写入批量清理；立即清理调用 `purgeExpired()` |
| 持久化 | 只在内存中，不跨重启。需要时由调用方用 `klib-data` 保存截止时间 |
| 测试 | `create(Clock)` 与 `perPlayer(scope, Clock)` 接受自定义时钟 |

## 加权随机、区间与概率

`me.kzheart.klib.random` 包统一了抽奖、掉落和数值浮动中常见的随机逻辑。所有随机方法都有接受 `java.util.Random` 的重载，便于复现与测试。

### WeightedPool

不可变的加权随机池，**构建时**就拒绝非法配置，而不是在抽取时各自猜测：

```java
import me.kzheart.klib.random.WeightedPool;

WeightedPool<Drop> pool = WeightedPool.of(drops, Drop::weight);
Drop one = pool.pick();                        // 或 pick(random)
List<Drop> three = pool.pickDistinct(3);       // 不放回抽取
double chance = pool.chance(0);                // 第 0 项被抽中的概率
```

| 情况 | 行为 |
| --- | --- |
| 负数、NaN、无穷权重 | 抛 `IllegalArgumentException` |
| 池非空但总权重为零 | 同样失败 |
| 权重为零的条目 | 保留，但永远不会被抽中 |
| `pickDistinct` 可抽条目不足 | 返回全部正权重条目 |
| 空池 | 可以创建；`pick` 抛 `IllegalStateException`，`pickDistinct` 返回空列表 |

### IntRange / DoubleRange

闭区间。`parse` 接受的写法：

| 输入 | 含义 |
| --- | --- |
| `5` | 单值 |
| `1-5`、`1~5`、`2 to 8` | 区间 |
| `-5-3` | -5 到 3 |
| `-5~-1` | -5 到 -1 |

上界小于下界时抛出异常。它们已注册为 [Config](config.md) 的内置类型，配置字段可直接声明：

```java
public final class DropConfig {
    IntRange amount = IntRange.of(1, 1);
    DoubleRange bonus = DoubleRange.of(0, 0);
}

int n = config.amount.random();       // 闭区间整数
double b = config.bonus.random();     // [min, max) 内均匀取值
```

### Chance

- `Chance.percent(12.5)` 按百分比判定，`Chance.ratio(0.125)` 按比例判定。
- 小于等于 0 永不命中，大于等于上限必定命中，NaN 或无穷抛出异常。

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

`info`、`success`、`warn`、`error` 都有携带模块名的重载，模块名只影响最近日志缓冲中的来源标记：

```java
logger().warn("arena", "竞技场配置缺少出生点");
```

- 调试日志默认关闭，可按模块或用 `*` 总开关启用。
- 调试输出以 `INFO` 级别发出，由模块开关过滤而非日志级别。服务器不必改日志配置就能看到，代价是无法用日志级别单独屏蔽。
- `recentLines()` 返回最近日志的只读快照，格式为 `时间戳 级别 [模块] 消息`，未带模块名的记为 `core`。适合诊断上报，**不要**当持久日志存储。
- 行首符号默认 `❯✔⚠✖`。GBK 等无法显示这些字符的 Windows 控制台，在启动参数加 `-Dklib.logger.ascii=true`，改用 ASCII 前缀 `>`、`+`、`!`、`x`。该属性在类初始化时读取一次，运行期修改无效。

## 轻量诊断快照

<details>
<summary>展开：DiagnosticSource 与 Remote Incident 接线</summary>

`DiagnosticSource` 是 Core 提供的只读内存快照边界。

- `ExecutorScheduler` 和 `BukkitSchedulerAdapter` 报告调度后端、作用域状态和执行器是否关闭。
- 采集过程不枚举服务器任务，不访问文件、网络或数据库。
- 选择 `remote` 模块后，可用 `KlibDiagnosticContributor` 把这些快照加入 Incident。
- 自定义实现同样只能读取已缓存的轻量状态，**禁止**在 `diagnosticSnapshot()` 中现场执行阻塞 I/O。

完整接线见 [Remote 的排障上下文](remote.md#排障上下文与-contributor)。

</details>

## 线程与生命周期

- `KPlugin.setup`、Bukkit 命令注册以及绝大多数 Bukkit API 操作在服务器主线程完成。命令在插件启动时声明，配置重载不重复注册。
- `after`、`every`、`sync`、`thenSync`、`onError` 的回调运行在同步调度器；`async` 的供应器不在主线程。
- 不要在 `async` 供应器中读写世界、实体、背包等要求主线程的 Bukkit 状态。先完成纯 I/O 或计算，再在 `thenSync` 中应用结果。
- JDK `CompletionStage` 的非 `Async` 回调不保证在 Bukkit 主线程执行。触碰 Bukkit 状态前必须经 `Scope.sync`、`Scope.syncExecutor()` 或 `AsyncTasks` 切回主线程。
- Bukkit 事件处理器的线程由事件本身决定，异步事件的处理器不会自动切回主线程。
- `Scope` 的生命周期操作会串行化，但不要并发触发同一作用域的 `rebuild`、`close` 或资源注册。
- 作用域关闭后，再注册资源、加载能力或访问已关闭的模块运行时会失败。

## 常见坑

- 一个作用域内同一种 capability 类型只能注册一次；子作用域可以覆盖父作用域能力。
- 子作用域名称在同一父作用域中必须唯一。
- 不要手工释放一个资源后仍让它留在作用域中。自定义一次性资源完成时可调用 `scope.remove(this)`。
- `KPlugin.instance()` 和 `rootScope()` 只在插件处于活动状态时可用。
- 清理中部分资源抛异常时，Klib 仍继续释放其余资源，最后以聚合异常报告失败。

## 相关页面

- [Config](config.md)：类型化 YAML、热重载与迁移
- [Lang](lang.md)：可重载消息目录与 Bukkit 消息路由
- [Command](command.md)：由作用域持有的类型化命令树
- [组件与注解](../annotations.md)：组件、`@OnStart`/`@OnStop`、`@Every` 的完整规则
