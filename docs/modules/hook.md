# klib-hook

把可选插件依赖包装成显式状态和稳定接口。第三方插件缺失或链接失败时拿到明确的空实现，业务代码里不用再散落插件检测和 `NoClassDefFoundError` 处理。

| 模块名 | 制品 | 自动带入 |
| --- | --- | --- |
| `hook` | `me.kzheart.klib:klib-hook` | `core` |

| 能力 | 入口 |
| --- | --- |
| 软依赖状态（可用 / 空实现 / 失败） | `Hook<T>`、`Hooks.orNoop(...)`、`DependencyReport` |
| 经济桥接：Vault、PlayerPoints、XConomy | `CurrencyHooks`，统一为 `BigDecimal` 的余额、扣款、发放、格式化 |
| 按顺序组合多种货币，失败补偿有明细 | `CompositeCurrency` |
| 随 `Scope` 重建和关闭的 PlaceholderAPI 扩展 | `Papi.registerBukkit(...)` |
| 从配置解析「金币 + 点券 + 道具 + 权限」消耗，失败自动退还；发放命令、物品、货币奖励 | `Costs`、`Rewards` |

## 快速开始

```kotlin
klib {
    modules {
        hook()
    }
    softdepend("Vault", "PlaceholderAPI")
}
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

- `klib-hook` 编译时使用 Bukkit API 和 PlaceholderAPI，它们**不会**作为运行时库打入 Klib。
- 使用某个集成时，要在 `plugin.yml` 声明 `softdepend`，并确保服务器实际装了对应插件。

最小示例（插件完整入口见 [快速开始](../../README.md)）：

```java
Hook<Currency> economy = root.install(CurrencyHooks.vault(getServer()));

for (String line : DependencyReport.builder().add(economy).build().lines()) {
    logger().info(line);
}

if (!economy.available()) {
    logger().warn("Vault 不可用，经济功能已关闭");
}
```

## 可选依赖状态

`Hook<T>` 同时保存适配值和解析状态。安装进当前作用域后，重建或停服时统一释放。

| 状态 | 含义 | `value()` |
| --- | --- | --- |
| `AVAILABLE` | 已解析真实依赖 | 真实适配器 |
| `NOOP` | 依赖未安装，或检测器返回 `null` | 空实现 |
| `FAILED` | 初始化抛出 `RuntimeException` 或 `LinkageError` | 仍是空实现 |

- `FAILED` 的 `detail()` 带异常类全名，message 非空时再附加 message，方便定位版本不兼容导致的 `LinkageError`。
- `detail()` 文案**不是稳定契约**，不要解析它来判断分支。
- `Hooks.orNoop(...)` 适合实现自己的软依赖边界。
- **空实现不代表操作成功。** 例如 `NoopCurrency`：余额为零，扣款和发放都返回失败结果；`format` 固定用 `Locale.ROOT` 的数字格式，不随服务端默认区域变化。

## 经济接口

### Vault

从 Bukkit 服务管理器自动发现：

```java
Hook<Currency> hook = root.install(CurrencyHooks.vault(getServer()));
Currency currency = hook.value();

CurrencyResult result = currency.take(player, new BigDecimal("25.50"));
if (!result.success()) {
    logger().warn("扣款失败：" + result.message());
}
```

| 入口 | 校验 |
| --- | --- |
| `CurrencyHooks.vault(Server)` | 自动发现；校验 Economy API 类型和服务注册 |
| `CurrencyHooks.vault(service)`（传入已取得的 Economy service，类型 `Object`） | 只判断是否为 `null`（`null` → `NOOP`），**不校验**对象是否真的实现了 Vault Economy 接口 |

- 两条路径都以 `BigDecimal` 作为 Klib 边界，调用 Vault 时才转成 `double`。
- 无法经 `double` 往返而保持精确的金额会被**拒绝**，不会静默舍入。

### PlayerPoints 与 XConomy

适配器不在公开 API 中暴露第三方类型，调用方传入对应插件提供的 API 对象：

```java
Hook<Currency> points = root.install(CurrencyHooks.playerPoints(playerPointsApi));
Hook<Currency> money = root.install(CurrencyHooks.xConomy(xConomyApi));
```

适配器通过反射兼容这两个插件常见的方法形状。

| 情况 | 结果 |
| --- | --- |
| API 对象为 `null` | `NOOP` |
| 构造适配器时抛异常，或 `NoClassDefFoundError` 等链接错误 | `FAILED` |
| API 对象缺少预期方法 | 仍为 `AVAILABLE`（构造期只检查非空，不探测方法形状），失败在调用时才出现 |

调用时的失败表现：`take` / `give` 返回失败的 `CurrencyResult`；`balance` 等直接反射读取的方法抛 `IllegalStateException`。

### 组合支付

`CompositeCurrency` 按列表顺序规划并扣除多种余额。整数货币按 `minorUnit()` 向下取整份额，余数交给后续货币：

```java
Currency combined = new CompositeCurrency(
        "shop",
        Arrays.asList(points.value(), economy.value()));

CurrencyResult result = combined.take(player, new BigDecimal("120"));
if (!result.success() && !result.compensated()) {
    for (CurrencyResult.Uncompensated debit : result.uncompensated()) {
        logger().error(
                "组合支付补偿失败：" + debit.currencyId() + " " + debit.amount(),
                result.cause());
    }
}
```

- **组合支付不是数据库事务。** 后续扣款失败时，Klib 逆序调用 `give` 补偿已完成的扣款，但第三方插件仍可能拒绝补偿。
- 必须检查 `compensated()` 和 `uncompensated()`；高价值交易要保留业务审计。
- 组合只发生在 `balance` 和 `take`。`give` 和 `format` 使用列表中的**第一种**货币。

## PlaceholderAPI

`Papi.registerBukkit` 先检测 PlaceholderAPI。未安装或链接失败时仍返回可释放的空操作注册，不会让插件启动失败：

```java
PapiRegistration registration = Papi.registerBukkit(
        root,
        this,
        "myplugin",
        placeholders -> {
            placeholders.key("online", player -> getServer().getOnlinePlayers().size());
            placeholders.keyCached(
                    "rank",
                    Duration.ofSeconds(10),
                    player -> rankOf(player));
            placeholders.prefixed(
                    "balance_",
                    (player, currencyId) -> balanceOf(player, currencyId));
        });

logger().info(registration.isAvailable()
        ? "PlaceholderAPI 扩展已注册"
        : "PlaceholderAPI 未安装");
```

`rankOf`、`balanceOf` 是业务查询。

| 方法 | 作用 |
| --- | --- |
| `key(name, resolver)` | 精确键 |
| `keyCached(name, ttl, resolver)` | 精确键，结果按 TTL 缓存 |
| `prefixed(prefix, (player, rest) -> ...)` | 前缀键，第二个参数是前缀后的余串 |

解析规则：

- 解析器参数类型是 `OfflinePlayer`，**不是** `Player`。业务查询按离线玩家身份处理，并考虑没有玩家上下文的调用。
- 标识转为小写，只能包含字母、数字和下划线。
- 精确键优先于前缀键；多个前缀同时匹配时用最长前缀。
- 解析器返回 `null` 时输出空字符串；数字最多保留两位小数。
- 缓存以玩家 UUID 和前缀余串为键，每个解析器最多 256 项。

默认注册在 PlaceholderAPI 自身 reload 后**不会**保留。确实需要时，显式构造 `new BukkitPapiRegistrar(plugin, true)`，再通过 `Papi.register(...)` 注册。

## 消耗与奖励

`me.kzheart.klib.hook.cost` 把配置里的消耗和奖励条目解析成可执行的计划。

- 条目格式 `类型:参数`，末尾可追加 `" | 描述"` 覆盖面向玩家的描述。
- 类型需要显式注册，Klib **不预设** `money` 对应哪种货币。

### 注册类型

```java
import me.kzheart.klib.hook.cost.*;
import me.kzheart.klib.item.ExternalItems;

ExternalItems items = ExternalItems.detect();   // 来自 klib-item，可选
Hook<Currency> vault = root.install(CurrencyHooks.vault(getServer()));
Hook<Currency> points = root.install(CurrencyHooks.playerPoints(playerPointsApi));

Costs costs = Costs.builder()
        .defaults()                                               // perm、level
        .type("money", CostTypes.currency(vault.value(), "金币"))
        .type("points", CostTypes.currency(points.value(), "点券"))
        .type("item", CostTypes.items(items::matcher))
        .type("kether", CostTypes.condition((player, script) -> engine
                .evalCondition(script, ScriptContext.builder().sender(player).build())
                .toCompletableFuture().getNow(Boolean.FALSE)))
        .build();

Rewards rewards = Rewards.builder()
        .defaults(getServer())                                    // console、player、op、msg
        .type("money", RewardTypes.currency(vault.value(), "金币"))
        .type("item", RewardTypes.items((ref, amount) -> items.create(ref, amount).orElse(null)))
        .build();
```

`playerPointsApi` 由 PlayerPoints 插件提供。

### 配置与执行

```yaml
cost:
  - "money:500"
  - "points:20"
  - "item:mi:MATERIAL:SOUL_GEM*3 | 灵魂宝石 ×3"
  - "perm:waypoint.vip | 需要 VIP"
  - "kether:check player level >= 30 | 等级达到 30"
reward:
  - "console:give %player% diamond 1"
  - "item:ni:heal_potion*2"
  - "msg:&a传送点已解锁"
```

```java
CostPlan cost = costs.parse(config.cost);        // 条目非法时抛出 IllegalArgumentException
RewardPlan reward = rewards.parse(config.reward);

for (CostLine line : cost.check(player)) {       // 渲染 lore：✔ 金币 500 / ✘ 灵魂宝石 ×3
    lore.add((line.satisfied() ? "&a✔ " : "&c✘ ") + line.description());
}

CostResult result = cost.charge(player);
if (!result.success()) {
    player.sendMessage("条件不足：" + result.failedCost());
    if (!result.compensated()) {
        logger().error("退还失败，需要人工处理：" + result.refundFailures());
    }
    return;
}
RewardResult granted = reward.grant(player);
if (!granted.success()) {
    logger().warn("部分奖励发放失败：" + granted.failures());
}
```

### 内置类型

消耗：

| 工厂 | 参数 | 行为 |
| --- | --- | --- |
| `CostTypes.currency(currency, label)` | 金额，例如 `500` | 扣款；退还时调用 `give` |
| `CostTypes.items(matchers[, names])` | `引用*数量`，数量默认 1 | 整体扣除背包物品；退还时归还原物品，放不下则掉落 |
| `CostTypes.level()` | 等级数 | 扣除经验等级 |
| `CostTypes.permission()` | 权限节点 | 只检查 |
| `CostTypes.condition(predicate)` | 原样交给谓词 | 只检查 |

奖励：

| 工厂 | 说明 |
| --- | --- |
| `RewardTypes.consoleCommand(server)` | 控制台执行命令 |
| `RewardTypes.playerCommand()` | 玩家执行命令 |
| `RewardTypes.opCommand()` | 临时 OP 执行命令 |
| `RewardTypes.message()` | 发送消息 |
| `RewardTypes.currency(currency, label)` | 发放货币 |
| `RewardTypes.items(creator[, names])` | 发放物品 |

- 命令和消息会替换 `%player%`、`%uuid%`。需要 PlaceholderAPI 时，用接受 `BiFunction<Player, String, String>` 的重载。
- 本包不依赖 `klib-item` 或 `klib-script`：物品通过 `Function<String, Predicate<ItemStack>>` 接入，脚本通过谓词接入。

### 扣除、退还与发放规则

- `charge` 先检查**全部**消耗，任一不满足就不改任何状态。全部满足后依次扣除；某项扣除失败或抛异常时，逆序退还已扣部分。
- 退还失败记录在 `refundFailures()`，此时 `compensated()` 为 `false`，需要人工处理。
- 检查中抛出的异常视为「不满足」，异常信息在 `CostLine.error()` 和 `CostResult.message()` 中。
- `RewardPlan.grant` 某项失败后继续发放其余奖励。控制台命令未被处理（`dispatchCommand` 返回 `false`）视为失败。

## 线程与生命周期

- `Hook` 和 `PapiRegistration` 都是幂等的 `Disposable`。安装进负责它们的 `Scope`，不要长期保留已关闭作用域产生的句柄。
- Klib 不替经济插件切换线程。余额读取、扣款、发放和 Bukkit 对象访问，要遵守对应插件和 Bukkit 的主线程要求。
- 消耗与奖励的检查、扣除、发放都会访问玩家状态，**在 Bukkit 主线程调用**。
- PlaceholderAPI 解析器在 PlaceholderAPI 发起请求的线程执行，**不要在里面阻塞网络或数据库**。较重的计算先异步刷新自己的快照，解析器只读快照，或者用短 TTL 缓存。

## 常见坑

- `CompositeCurrency` 只提供进程内的尽力补偿，不提供跨插件原子性。
- `softdepend` 只影响加载顺序：不会安装第三方插件，也不能证明其 API 版本可用。启动报告应保留 `FAILED` 和 `NOOP` 的区别。
- `condition` 的谓词应同步返回。上例中的 Kether 脚本如果含 `wait` 等异步动作，`getNow` 会返回 `false`。
- `opCommand` 临时授予 OP，并在 `finally` 中撤销，只能用于受信任的配置；能用 `console` 时优先用 `console`。
- PlayerPoints / XConomy 的 API 对象方法不匹配时状态仍是 `AVAILABLE`，问题要到调用时才暴露。

## 类型导入

<details>
<summary>展开：主要类型的全限定名</summary>

| 类型 | 全限定名 |
| --- | --- |
| Hook | `me.kzheart.klib.hook.Hook` |
| Currency | `me.kzheart.klib.hook.economy.Currency` |
| CurrencyHooks | `me.kzheart.klib.hook.economy.CurrencyHooks` |
| CompositeCurrency | `me.kzheart.klib.hook.economy.CompositeCurrency` |
| Papi | `me.kzheart.klib.hook.papi.Papi` |
| PapiRegistration | `me.kzheart.klib.hook.papi.PapiRegistration` |
| Costs | `me.kzheart.klib.hook.cost.Costs` |
| CostTypes | `me.kzheart.klib.hook.cost.CostTypes` |
| CostPlan | `me.kzheart.klib.hook.cost.CostPlan` |
| CostResult | `me.kzheart.klib.hook.cost.CostResult` |
| CostLine | `me.kzheart.klib.hook.cost.CostLine` |
| Rewards | `me.kzheart.klib.hook.cost.Rewards` |
| RewardTypes | `me.kzheart.klib.hook.cost.RewardTypes` |
| RewardPlan | `me.kzheart.klib.hook.cost.RewardPlan` |
| RewardResult | `me.kzheart.klib.hook.cost.RewardResult` |

</details>

## 相关页面

- [Core](core.md)：作用域与 `Disposable`
- [Item](item.md)：`ExternalItems`，给 `CostTypes.items` / `RewardTypes.items` 提供物品匹配和创建
- [Script](script.md)：Kether 条件
