# klib-compat

状态：稳定公开模块
模块名：`compat`、`compat-v1_12`、`compat-v1_20`、`compat-v1_21`、`compat-v26`
基础制品：`me.kzheart.klib:klib-compat`

`klib-compat` 提供不依赖 Bukkit 类型的版本解析、适配器选择和能力查询，以及按玩家发送的数据包侧边栏
`Sidebars`。四个实现模块只描述已经验证的基准差异；业务代码面向 `TextBridge`、`NbtBridge`、
`MaterialBridge`、`InventoryBridge` 和 `SidebarBridge`，查询能力而不是直接判断服务端版本号。

## 它是什么，不是什么

`klib-compat` 是**能力声明注册表与版本选择工具**：把“哪个版本区间由哪个适配器负责、该适配器声明了
哪些能力”整理成可查询、可自检的数据结构。

除侧边栏外，它**不会**替你执行 Bukkit/NMS 适配动作。`TextBridge`、`NbtBridge`、`MaterialBridge` 和
`InventoryBridge` 只回答布尔形式的能力问题，不封装物品、文本或容器操作；`SidebarBridge` 是唯一真正
发送数据包的能力，由 [数据包侧边栏](#数据包侧边栏) 中的 `Sidebars` 使用。Klib 各模块真正的运行时版本适配（例如 `klib-item` 内部的物品标签桥接、
`klib-lang` 的文本渲染差异）在各自模块内部完成，并不经过 `CompatResolver`；打包或不打包
`compat-v*` 都不会改变这些模块的行为。

因此适用场景是：

- 插件**自身**需要按版本分支时，用统一口径查询“当前服务端属于哪个已验证基准、是否支持某项能力”；
- 启动自检或问题排查时，输出所有已打包适配器的能力矩阵；
- 需要按同样的模型登记自定义的版本相关能力；
- 需要按玩家显示、不替换玩家 Bukkit 记分板的侧边栏；
- 正在增加有真实服务器回归证据的新版本适配器。

不适用场景：期待引入本模块后，Bukkit API 的版本差异被自动抹平。

## 接入

只使用契约和自定义提供者时选择基础模块：

```kotlin
klib {
    modules {
        compat()
    }
}
```

需要仓库内全部版本实现时，应显式选择四个实现。Gradle 插件会按模块图自动补入 `compat`（compat 系列
不依赖 `core`，不会因此引入 `core`）：

```kotlin
klib {
    modules {
        compatV1_12()
        compatV1_20()
        compatV1_21()
        compatV26()
    }
}
```

不使用 Klib Gradle 插件时，可直接依赖发布物，并由自己的打包流程处理内嵌与重定位：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-compat:<klib-version>")
    implementation("me.kzheart.klib:klib-compat-v1_12:<klib-version>")
    implementation("me.kzheart.klib:klib-compat-v1_20:<klib-version>")
    implementation("me.kzheart.klib:klib-compat-v1_21:<klib-version>")
    implementation("me.kzheart.klib:klib-compat-v26:<klib-version>")
}
```

这些模块的发布依赖中没有 Bukkit、NMS 或其他第三方运行时库。侧边栏在运行时通过反射访问服务端内部类；
`Sidebars` 编译期使用 Spigot API 与 `klib-core`，使用它的插件需要同时选择 `core()`。

## 创建解析器

推荐使用 `CompatProviders` 自动装配已打包的仓库内置实现：

```java
import me.kzheart.klib.compat.CompatProvider;
import me.kzheart.klib.compat.CompatProviders;
import me.kzheart.klib.compat.CompatResolver;

CompatResolver resolver = CompatProviders.resolver();
CompatProvider provider = resolver.resolve(getServer().getBukkitVersion());
logger().info("兼容实现：" + provider.id() + " / " + provider.version());
```

只解析一次时可用一步到位的入口：

```java
CompatProvider provider = CompatProviders.resolve(getServer().getBukkitVersion());
```

`CompatProviders` 按固定的实现类名列表用 `Class.forName` 探测，没打包的实现直接跳过，不使用
`ServiceLoader`，也不需要 `META-INF/services` 资源；类名字符串常量会被 Klib Gradle 插件的类文件
重定位一并改写，因此在 shade + relocate 产物中同样有效。发现结果不缓存，建议在插件启用时装配一次
并自行持有 `CompatResolver`。一个内置实现都没打包时 `resolver()` / `resolve(String)` 抛出
`IllegalStateException`，消息里列出期望的实现类名。

由于本模块不依赖 Bukkit，版本字符串默认由调用方传入。运行在 Bukkit 上时可以让它自己探测：

```java
// 反射读取 org.bukkit.Bukkit.getBukkitVersion()；不在 Bukkit 运行时返回 Optional.empty()
Optional<String> detected = CompatProviders.detectServerVersion();

// 探测 + 解析一步完成；探测不到时抛 IllegalStateException
CompatProvider current = CompatProviders.resolveCurrent();
```

需要自定义参与解析的集合（例如混入自己的提供者，或刻意排除某个实现）时仍可手动构造：

```java
CompatResolver resolver = new CompatResolver(Arrays.asList(
        new V1_12CompatImplementation(),
        new V1_20CompatImplementation(),
        new V1_21CompatImplementation(),
        new V26CompatImplementation()));
```

`ServerVersion.parse` 会在字符串中查找第一个 `主版本.次版本[.补丁]`，因此可直接解析常见 Bukkit
版本文本；省略补丁时按 `0` 处理。无法解析或没有适配器覆盖时会抛出
`IllegalArgumentException`，不要静默假定最新版能力。无匹配时的异常消息会列出每个已注册提供者的
基准版本及落选原因（基准高于服务端 / 未声明支持该版本），可据此判断是漏打包实现模块还是版本落在
覆盖空隙。

解析器选择“基准版本不高于当前服务器、且声明支持当前服务器”的最新实现。构造期校验以下约束，任一
不满足都抛 `IllegalArgumentException`：

- 提供者集合非空；
- 每个提供者的 ID 唯一；
- 每个提供者的基准版本唯一；
- 每个提供者必须 `supports` 自己的基准版本（否则该实现永远无法被选中）。

## 解析区间

下表是当前四个实现通过 `CompatResolver` 时的实际解析结果。区间同时受 `supports` 和“基准版本不得
高于当前版本”约束。

这里的**适配器基准版本**指实现类中 `SERVER_VERSION` 常量声明的支持起点，它决定解析区间的下界；
`ServerVersion` 省略补丁号时按 `0` 补齐，所以 `"1.21"` 与 `1.21.0` 等价。它与根 `README.md` 里的
**实测联机版本**（该版本线上真实跑过回归的服务端版本，例如 1.21 线是 1.21.4）是两个概念：基准版本
可以低于实测版本，解析行为以下表的代码常量为准。

| 实现 | 适配器基准版本（`SERVER_VERSION`） | 实测联机版本 | `supports` 声明范围 | 实际可解析范围 |
| --- | --- | --- | --- | --- |
| `compat-v1_12` | `1.12.2` | 1.12.2 | `[1.12.2, 1.20.0)` | `[1.12.2, 1.20.0)` |
| `compat-v1_20` | `1.20.4` | 1.20.4 | `[1.20.0, 1.21.0)` | `[1.20.4, 1.21.0)` |
| `compat-v1_21` | `1.21`（即 1.21.0） | 1.21.4 | `[1.21.0, 26.2.0)` | `[1.21.0, 26.2.0)` |
| `compat-v26` | `26.2`（即 26.2.0） | 26.2 | 同为 26.x 且不低于 26.2 | `[26.2.0, 27.0.0)` |

因此当前没有实现覆盖低于 1.12.2、1.20.0–1.20.3 或 27.x 及更高版本。26.0/26.1 会落到
`compat-v1_21`；这是源码中明确保留的最近实现回退。新增覆盖范围前应先有真实服务器回归证据，不能
仅靠版本号猜测行为。

## 内部能力矩阵

四个实现当前都公开完整的五项能力，但返回值或实现方式不同：

| 实现 | 十六进制颜色 | PersistentDataContainer | 旧材质名 | 运行时更新容器标题 | 侧边栏 |
| --- | --- | --- | --- | --- | --- |
| `compat-v1_12` | 否 | 否 | 是 | 否 | 队伍前缀，显示红色分数 |
| `compat-v1_20` | 是 | 是 | 否 | 是 | 分数显示文本，隐藏分数 |
| `compat-v1_21` | 是 | 是 | 否 | 是 | 分数显示文本，隐藏分数 |
| `compat-v26` | 是 | 是 | 否 | 是 | 分数显示文本，隐藏分数 |

调用方通过类型化能力键访问，不要强制转换到版本实现：

```java
boolean hexColors = provider.capability(Capabilities.TEXT)
        .map(TextBridge::supportsHexColors)
        .orElse(false);

boolean legacyMaterialNames = provider.capability(Capabilities.MATERIAL)
        .map(MaterialBridge::usesLegacyNames)
        .orElseThrow(() -> new IllegalStateException("缺少材质能力"));
```

如果要在启动时审计所有实现，可读取能力快照：

```java
CompatCapabilityMatrix matrix = resolver.capabilityMatrix();
for (CompatCapabilityMatrix.Row row : matrix.rows()) {
    logger().info(row.providerId() + " text=" + row.has(Capabilities.TEXT));
}
```

## 数据包侧边栏

`me.kzheart.klib.compat.sidebar.Sidebars` 按玩家维护侧边栏：标题与行只以数据包形式发给目标玩家的
客户端，不读取、不替换也不修改玩家的 Bukkit `Scoreboard`，玩家原有的队伍、计分项和其他插件设置的
记分板保持不变。内容更新只发送与上一次不同的标题和行。实现改写自 TabooLib 的 `NMSScoreboard`，
归属见 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。

需要的模块：`core()`，以及覆盖目标服务端版本的 `compat-v*`（Gradle 插件会自动补入 `compat`）：

```kotlin
klib {
    modules {
        core()
        compatV1_21()
    }
}
```

```java
import me.kzheart.klib.KPlugin;
import me.kzheart.klib.compat.sidebar.Sidebars;
import me.kzheart.klib.scheduler.Ticks;
import org.bukkit.entity.Player;

public final class QuestPlugin extends KPlugin {
    @Override
    protected void setup() {
        Sidebars sidebars = Sidebars.install(context().scope());
        tasks().every(Ticks.seconds(1), () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                sidebars.show(player, "§6任务追踪",
                        "§f击杀僵尸 §a" + kills(player) + "/10",
                        "§f收集木头 §a5/5");
            }
        });
    }

    private int kills(Player player) {
        return 3;
    }
}
```

| 方法 | 行为 |
| --- | --- |
| `Sidebars.install(Scope)` | 用 `CompatProviders.resolveCurrent()` 选出当前版本的实现并要求其公开 `Capabilities.SIDEBAR`；没打包对应实现或版本不受支持时抛 `IllegalStateException` / `IllegalArgumentException` |
| `Sidebars.install(Scope, SidebarBridge)` | 使用指定实现，例如已经自行解析的 `provider.capability(Capabilities.SIDEBAR)` |
| `show(Player, String title, String... lines)` / `show(Player, String, List<String>)` | 首次调用创建侧边栏，之后只发送差异；`lines` 自上而下排列，可以为空；离线玩家被忽略 |
| `hide(Player)` | 移除侧边栏；之前未显示时返回 `false` |
| `isShown(Player)` | 当前登录会话是否显示着本实例的侧边栏 |
| `maxLines()` | 实现可完整显示的最大行数，当前为 15；超过时 `show` 抛 `IllegalArgumentException` 且不发送数据包 |

需要在不支持的服务端上降级时，先查询能力再安装：

```java
CompatProviders.resolveCurrent().capability(Capabilities.SIDEBAR)
        .map(bridge -> Sidebars.install(context().scope(), bridge))
        .orElse(null);
```

生命周期与线程：

- `install` 把实例安装到作用域，并通过作用域事件能力监听 `PlayerQuitEvent`：玩家退出时直接丢弃其
  状态，不发送数据包；同一 UUID 重新登录后的首次 `show` 会重新创建侧边栏。
- 作用域关闭时向仍在线的玩家移除侧边栏，之后再调用 `show` 抛 `IllegalStateException`。
- 方法可从任意线程调用，同一玩家的更新按调用顺序串行发送。
- 每个 `Sidebars` 实例使用随机的计分项名，多个插件或实例互不覆盖内部状态；但客户端同一时间只显示一个
  侧边栏，后发送显示请求的一方生效。

文本与限制：

- 标题和行使用 `§` 颜色代码（`§x` 十六进制颜色在 1.16 起生效），不解析 `&`、MiniMessage 或 JSON 文本，
  需要时先自行转换；行文本不应包含换行。
- `compat-v1_12` 覆盖的 1.12.2 至 1.19.x 每行使用一个队伍前缀显示文本，右侧会显示客户端默认的红色
  分数；1.12 的行文本超过 16 个字符时按颜色延续拆到后缀，前缀与后缀合计最多 32 个字符，标题最多
  32 个字符，超出部分截断。1.13 起没有这些长度限制。
- 1.20.4 起每行是带自定义显示文本的分数，计分项使用空白数字格式，不显示右侧分数。
- 玩家自己的 Bukkit 记分板之后把计分项放到侧边栏槽位时会覆盖本侧边栏；`hide` 只清空客户端的侧边栏
  槽位，不会替其他记分板恢复显示。

实服验证（Paper 服务端 + 真实客户端，覆盖创建、差异更新、增删行、15 行上限、移除与重新登录）：

| 实现 | 已验证服务端 | 说明 |
| --- | --- | --- |
| `compat-v1_12` | 1.12.2、1.16.5、1.18.2 | 1.16.5 只验证了数据包往返和客户端日志，未截图核对显示 |
| `compat-v1_20` | 1.20.4 | 1.20.5、1.20.6 按与 1.21 相同的数据包结构实现，未实服验证 |
| `compat-v1_21` | 1.21.1、1.21.4、1.21.11、26.1.2 | |
| `compat-v26` | 26.2 | |

1.13 至 1.15、1.17、1.19、1.20.5 至 1.20.6 以及非 Paper 的 Spigot 服务端没有实服验证；1.20.0 至 1.20.3
没有任何实现覆盖（见 [解析区间](#解析区间)），`resolveCurrent()` 会直接失败。反射在首次发送时解析，
遇到无法识别的服务端结构会抛出带服务端版本的 `IllegalStateException`，不会静默降级。

## 自定义提供者

实现 `CompatProvider`，或继承 `AbstractCompatProvider` 并传入不可变能力映射。能力键由 ID和 Java 类型
共同确定；自定义键应保存为共享常量，避免不同模块创建外观相同但实际不相等的键。

```java
public final class CustomCompat extends AbstractCompatProvider {
    public CustomCompat() {
        super("custom-1_20", "1.20.6", capabilities());
    }

    @Override
    public boolean supports(ServerVersion version) {
        return version.compareTo(ServerVersion.of(1, 20, 6)) >= 0
                && version.compareTo(ServerVersion.of(1, 21, 0)) < 0;
    }

    private static Map<Capability<?>, Object> capabilities() {
        Map<Capability<?>, Object> values = new HashMap<Capability<?>, Object>();
        values.put(Capabilities.TEXT, (TextBridge) () -> true);
        return values;
    }
}
```

## 生命周期与边界

- 解析器、版本对象、提供者和能力矩阵都是纯 Java 对象，不创建线程，也不需要释放；`Sidebars` 由作用域
  管理释放。
- 除 `SidebarBridge` 外，桥接能力只是能力描述，不会替调用方执行 Bukkit/NMS 操作；业务模块仍需遵守
  相关 API 的线程约束。
- `compat` 基础模块不会自动带入任何版本实现；只打包基础模块时 `CompatProviders.discover()` 返回空
  列表，`CompatProviders.resolver()` 抛 `IllegalStateException`，直接构造实现类则会在运行时缺类。
- 能力缺失通过 `Optional.empty()` 表达。是否允许降级应由业务决定，不要无条件调用 `get()`。
- 版本范围是经过测试的支持边界，不是对未来版本的兼容承诺。
