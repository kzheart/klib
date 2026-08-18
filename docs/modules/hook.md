# klib-hook

状态：稳定公开模块
模块名：`hook`
制品：`me.kzheart.klib:klib-hook`

`klib-hook` 把可选插件依赖包装成显式状态和稳定接口。目前内置经济桥接支持 Vault、
PlayerPoints 与 XConomy，并提供由 `Scope` 管理的 PlaceholderAPI 注册 DSL。依赖不存在或链接失败时，
调用方可以得到明确的空实现，而不是在业务代码中散落插件检测和 `NoClassDefFoundError` 处理。

## 何时使用

- 需要接入 Vault、PlayerPoints 或 XConomy，但不希望第三方插件缺失时阻止自己的插件启动；
- 需要把经济操作统一为 `BigDecimal` 余额、扣款、发放和格式化接口；
- 需要按顺序组合多种货币，并得到失败补偿的明细；
- 需要注册随 `Scope` 重建和关闭的 PlaceholderAPI 扩展。

## 接入

推荐由 Klib Gradle 插件选择模块；`core` 会作为依赖闭包自动加入：

```kotlin
klib {
    modules {
        hook()
    }
    softdepend("Vault", "PlaceholderAPI")
}
```

如果不使用 Klib Gradle 插件，可直接依赖发布物，但需要由自己的构建负责把 Klib 及其运行时依赖打入
插件 JAR并重定位：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-hook:<klib-version>")
}
```

`klib-hook` 编译时使用 Bukkit API 和 PlaceholderAPI；它们不会作为普通运行时库打入 Klib。使用对应
集成时，还应在 `plugin.yml` 中声明 `softdepend`，并确保服务器实际安装相应插件。

## 可选依赖状态

`Hook<T>` 同时保存适配值和解析状态。把句柄安装进当前作用域，重建或停服时就会统一释放：

```java
Hook<Currency> economy = root.install(CurrencyHooks.vault(getServer()));

for (String line : DependencyReport.builder().add(economy).build().lines()) {
    logger().info(line);
}

if (!economy.available()) {
    logger().warn("Vault 不可用，经济功能已关闭");
}
```

状态含义如下：

| 状态 | 含义 |
| --- | --- |
| `AVAILABLE` | 已解析真实依赖，`value()` 返回真实适配器 |
| `NOOP` | 依赖未安装或检测器返回 `null`，`value()` 返回空实现 |
| `FAILED` | 初始化抛出 `RuntimeException` 或 `LinkageError`，`value()` 仍返回空实现 |

`FAILED` 的 `detail()` 会带上异常类全名，并在 message 非空时附加 message，便于定位版本不兼容导致的
`LinkageError`；具体文案不作为稳定契约，不要解析后用于判断分支。

`Hooks.orNoop(...)` 适合实现自己的软依赖边界。空实现不代表操作成功；例如 `NoopCurrency` 的余额为
零，扣款和发放都会返回失败结果，`format` 固定使用 `Locale.ROOT` 的数字格式，不随服务端默认区域变化。

## 经济接口

### Vault

Vault 可直接从 Bukkit 服务管理器发现：

```java
Hook<Currency> hook = root.install(CurrencyHooks.vault(getServer()));
Currency currency = hook.value();

CurrencyResult result = currency.take(player, new BigDecimal("25.50"));
if (!result.success()) {
    logger().warn("扣款失败：" + result.message());
}
```

也可以把已经取得的 Economy service 作为 `Object` 传给 `CurrencyHooks.vault(service)`。这一重载只判断入参是否为
`null`（为 `null` 时状态为 `NOOP`），不校验对象是否真的实现了 Vault Economy 接口；对 Economy API 类型和服务注册的
校验只发生在 `CurrencyHooks.vault(Server)` 的自动发现分支。两条路径都使用 `BigDecimal` 作为 Klib 边界，调用 Vault
时才转换为 `double`。无法经过 `double` 往返而保持精确的金额会被拒绝，不会静默舍入。

### PlayerPoints 与 XConomy

这两个适配器不在公开 API 中暴露第三方类型，调用方传入插件提供的 API 对象：

```java
Hook<Currency> points = root.install(CurrencyHooks.playerPoints(playerPointsApi));
Hook<Currency> money = root.install(CurrencyHooks.xConomy(xConomyApi));
```

适配器通过反射兼容其常见方法形状。API 对象为 `null` 时状态为 `NOOP`；构造适配器时抛出异常或
`NoClassDefFoundError` 等链接错误时状态为 `FAILED`。构造期只检查入参非空，不探测方法形状，因此 API 对象缺少
预期方法时状态仍为 `AVAILABLE`，失败在实际调用时体现：`take`/`give` 返回失败的 `CurrencyResult`，
`balance` 等直接反射读取的方法抛出 `IllegalStateException`。

### 组合支付

`CompositeCurrency` 按列表顺序规划并扣除多种余额。整数货币通过 `minorUnit()` 向下取整份额，余数交给
后续货币：

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

组合支付不是数据库事务。后续扣款失败时 Klib 会逆序调用 `give` 补偿已完成扣款，但第三方插件仍可能
拒绝补偿；必须检查 `compensated()` 和 `uncompensated()`，并为高价值交易保留业务审计。
`CompositeCurrency.give` 和 `format` 使用列表中的第一种货币；组合仅发生在 `balance` 和 `take`。

## PlaceholderAPI

`Papi.registerBukkit` 会先检测 PlaceholderAPI。未安装或链接失败时仍返回可释放的空操作注册，不会让
插件启动失败：

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

标识会转为小写，并且只能包含字母、数字和下划线。精确键优先于前缀键；多个前缀同时匹配时使用最长
前缀。解析器返回 `null` 会变成空字符串，数字使用最多两位小数。缓存以玩家 UUID和前缀余串为键，
每个解析器最多保留 256 项。

默认注册不会在 PlaceholderAPI 自身 reload 后持久保留。确有需要时可显式构造
`new BukkitPapiRegistrar(plugin, true)` 并通过 `Papi.register(...)` 注册。

## 线程、生命周期与边界

- `Hook` 和 `PapiRegistration` 都是幂等 `Disposable`；应安装进负责它们的 `Scope`，不要长期保留已关闭
  作用域产生的句柄。
- Klib 不替经济插件切换线程。余额读取、扣款、发放以及 Bukkit 对象访问应遵守对应插件和 Bukkit 的
  主线程要求。
- PlaceholderAPI 解析器在 PlaceholderAPI 发起请求的线程执行；解析器中不要阻塞网络或数据库。需要
  较重计算时先异步刷新自己的快照，再在解析器中读取，或使用短 TTL缓存。
- `CompositeCurrency` 只提供进程内的尽力补偿，不提供跨插件原子性。
- `softdepend` 只影响加载顺序，不会安装第三方插件，也不会证明其 API 版本可用；启动报告应保留
  `FAILED` 与 `NOOP` 的区别。
