# Item 模块

Java 8 可用的物品构建、类型化标签、背包扣除/发放和物品编码。适合「创建带业务标记的物品」「安全扣除或发放物品」「把物品保存为字符串」这类任务。

| 模块名 | 制品 |
| --- | --- |
| `item` | `me.kzheart.klib:klib-item` |

## 快速开始

```kotlin
klib {
    modules {
        item()
    }
}
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

**额外依赖 Item-NBT-API**：

- 旧版服务端读写标签时需要它，默认构建会把它一并放入最终产物。
- 该制品只在 CodeMC 仓库发布，构建脚本**必须**声明该仓库，配置见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。
- 不用 Klib Gradle 插件时，自己把 `klib-item` 和 Item-NBT-API 打入插件，或在运行环境提供：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-item:<klib-version>")
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156")
    runtimeOnly("de.tr7zw:item-nbt-api:2.12.4")
}
```

最小示例：

```java
import me.kzheart.klib.item.Items;
import me.kzheart.klib.item.TagKey;
import org.bukkit.inventory.ItemStack;

public final class ToolItems {
    private static final TagKey<String> TOOL_TYPE =
            TagKey.string("myplugin:tool_type");
    private static final TagKey<Integer> DURABILITY =
            TagKey.integer("myplugin:durability");

    private ToolItems() {
    }

    public static ItemStack miningTool() {
        return Items.of("IRON_PICKAXE")
                .name("&6采集工具")
                .lore("&7用于采集矿物", "&7剩余耐久: &f64")
                .tag(TOOL_TYPE, "mining")
                .tag(DURABILITY, Integer.valueOf(64))
                .build();
    }
}
```

## 构建物品

| 调用 | 作用 |
| --- | --- |
| `Items.of(...)` | 新建物品 |
| `Items.edit(...)` | 克隆后编辑已有物品 |
| `build()` | 返回独立的 `ItemStack` |
| `Items.resolveMaterial(...)` | 解析材质名：接受大小写差异、短横线和带命名空间的名称；未知材质直接抛异常 |

- 名称和 lore 支持 `&` 颜色代码。
- 数量必须在材质可堆叠范围内。

### 模型、光效与头颅

```java
ItemStack sword = Items.of("DIAMOND_SWORD")
        .customModelData(1001)
        .unbreakable(true)
        .glow(true)
        .build();

Integer model = Items.customModelData(sword);   // 未设置或服务端不支持时为 null

ItemStack head = Items.playerHead()
        .skullOwner(player.getUniqueId())
        .name("&b" + player.getName())
        .build();
```

| 方法 | 版本差异与规则 |
| --- | --- |
| `customModelData(...)` | 1.14 以前没有对应属性，调用被忽略；需要区分时先调 `Items.supportsCustomModelData()`。传 `null` 清除 |
| `glow(true)` | 1.20.5+ 用原生光效覆盖；更早版本添加一级 `LURE` 附魔并隐藏附魔标记 |
| `glow(false)` | 只移除由 `glow(true)` 添加的这组附魔与标记 |
| `Items.playerHead()` | 1.13+ 创建 `PLAYER_HEAD`；1.12 创建数据值为 3 的 `SKULL_ITEM` |
| `skullOwner(...)` | 物品必须是玩家头颅，否则抛 `IllegalArgumentException`；传 `UUID` 时通过 `Bukkit.getOfflinePlayer` 解析 |

## 读取和更新标签

- 标签键必须是 `namespace:path` 形式。
- 内置类型：字符串、整数、长整数、双精度数、布尔值、字节数组。

```java
ItemStack item = player.getInventory().getItemInMainHand();
if (TOOL_TYPE.has(item) && "mining".equals(TOOL_TYPE.get(item))) {
    Integer durability = DURABILITY.get(item);
    if (durability != null && durability.intValue() > 0) {
        DURABILITY.set(item, Integer.valueOf(durability.intValue() - 1));
    }
}
```

| 方法 | 标签缺失时 |
| --- | --- |
| `get(...)` | 返回 `null` |
| `find(...)` | 返回空 `Optional` |
| `getOrDefault(...)` | 返回默认值 |

```java
int durability = DURABILITY.getOrDefault(item, Integer.valueOf(0)).intValue();

TOOL_TYPE.find(item).ifPresent(type -> player.sendMessage("工具类型：" + type));
```

存储位置：

- 支持 Persistent Data Container 的服务端：写入 PDC。
- 1.12 等旧版服务端：通过 Item-NBT-API 写入根 NBT。
- 升级服务器后读取旧标签时，适配器会尽力把标签迁移到 PDC。

## 背包中的扣除与发放

`InventoryItems` 按 `ItemStack.isSimilar(...)` 比较物品，名称、lore、附魔和标签都参与匹配。

```java
ItemStack price = Items.of("DIAMOND").amount(3).build();
if (!InventoryItems.take(player.getInventory(), price, 3)) {
    player.sendMessage("钻石不足");
    return;
}

ItemStack reward = ToolItems.miningTool();
InventoryItems.give(player, reward);
```

| 方法 | 行为 |
| --- | --- |
| `take(...)` | 先确认总量足够，再一次性修改背包；**不会**只扣一部分 |
| `give(...)` | 用 Bukkit 背包接口发放；放不下的在玩家位置自然掉落，掉落物副本作为返回值 |

## 保存和传输物品

`ItemCodec` 可以编码单个物品、物品数组、完整背包或位置：

```java
String encoded = ItemCodec.encode(item, true);
ItemStack restored = ItemCodec.decodeItem(encoded);
```

- 编码值包含 Minecraft 数据版本，可选 GZIP。
- 跨服务器版本解码时记录数据版本不一致警告；Minecraft 仍可能升级或拒绝其中的物品。
- 解码器限制输入和解压后的大小，并用类白名单约束 Java 反序列化。
- 即便如此，编码值仍是业务数据，**不要**把任意超大外部输入直接交给解码器。
- `ItemCodec` 不是跨 Minecraft 数据版本的稳定数据库格式。长期保存时要保留迁移或无法解码时的降级策略。

## 外部物品系统

`ExternalItems` 用统一的 `prefix:id` 引用生成和识别外部物品。内置五个反射适配器，业务插件不需要在编译期依赖这些插件：

| 前缀 | 插件 | ID 形式 | 识别已有物品 |
| --- | --- | --- | --- |
| `zap` | ZaphkielPlus | 物品 ID，例如 `zap:heal_potion` | 支持 |
| `mi` | MMOItems 6.x | `TYPE:ID`，例如 `mi:SWORD:FLAME_BLADE` | 支持 |
| `ni` | NeigeItems | 物品 ID，例如 `ni:heal_potion` | 支持 |
| `ia` | ItemsAdder | `namespace:id`，例如 `ia:ruby:gem` | 支持 |
| `mm` | MythicMobs 4.x / 5.x | 物品 ID | 仅 5.x 支持 |

没有前缀或前缀为 `minecraft` 的引用视为原版材质，例如 `DIAMOND`、`minecraft:diamond`。

探测是显式调用，不会在类加载时自动发生。**在目标插件启用之后调用**，并在 `plugin.yml` 中把它们声明为 `softdepend`：

```java
import me.kzheart.klib.item.ExternalItems;

ExternalItems items = ExternalItems.detect();
items.report().forEach(state -> logger().info("外部物品：" + state));
// zap(ZaphkielPlus)=AVAILABLE、mi(MMOItems)=AVAILABLE、ni(NeigeItems)=MISSING: 未安装或未启用、mm(MythicMobs)=FAILED: ...

List<ItemStack> potions = items.createBatch("zap:heal_potion", 3, player);
Optional<ItemRef> ref = items.identify(player.getInventory().getItemInMainHand());
boolean isBlade = items.matches("mi:SWORD:FLAME_BLADE", item);
Predicate<ItemStack> matcher = items.matcher("mi:MATERIAL:SOUL_GEM");   // 预先解析，可重复使用
ItemSpec spec = items.spec("ia:ruby:gem");                             // 与 ItemSpec 组合
```

| 情况 | 行为 |
| --- | --- |
| `parse`、`matcher`、`spec` 遇到未知前缀或不存在的材质 | 抛 `IllegalArgumentException`，加载配置时就能发现拼写错误 |
| 内置前缀对应的插件未安装 | `create` 返回空、`matches` 返回 `false`，不抛异常 |
| 查看各前缀状态 | `report()` 给出每个前缀的状态与失败原因 |
| 原版引用匹配 | 只匹配**不被任何已注册外部系统识别**的物品，MMOItems 的钻石不会被当作普通钻石扣除 |

生成规则：

- `create(reference, amount, player)` 传入玩家上下文。
- `createBatch(reference, amount, player)` 逐件生成，全部成功后返回只读列表；任一 ID 缺失返回空列表；生成异常继续传播。
- 批量发放时，调用方先验证容量再统一发放，并自行限定批量上限。
- **Zap 的 `create` 只接受数量 1**，多件必须用 `createBatch`，避免复制实例 UUID 和随机属性。
- 识别按 Zap 优先。Zap 插件停用后，现有适配器仍可只读识别身份，但停止生成。

### 接入其他物品系统

- 实现 `ExternalItemSource`（前缀、插件名、`create(id)`、`identify(item)`），再通过 `with(...)` 注册。
- `ExternalItems` 实例不可变，`with(source)` 返回追加了该来源的新实例。前缀 `minecraft` 保留给原版材质。
- `ExternalItems` 同时实现旧的 `ExternalItemProvider`，可以直接传给 `ItemSpec.Builder.external(...)`。

## 线程与生命周期

- `ItemBuilder` 和标签操作处理 `ItemStack`；涉及在线玩家背包或世界掉落时，在 Bukkit 主线程执行。
- `Items.edit(...)`、`build()`、标签桥接和编码接口会克隆或创建值，但仍不要在其他线程同时修改同一个原始 `ItemStack`。
- `InventoryItems.give(...)` 背包溢出时会访问玩家世界并生成掉落物，只能用于在线玩家的同步流程。
- 外部物品的生成与识别会调用外部插件，遵守这些插件自身的线程要求，通常在主线程调用。

## 常见坑

- 不要直接依赖标签的具体桥接实现（PDC / NBT）。
- 不要把同一个标签名称同时定义成不同 Java 类型。
- `InventoryItems` 用 `isSimilar` 匹配，名称、lore、附魔或标签不同的物品不会被算作同一种。
- 构建脚本漏声明 CodeMC 仓库时，Item-NBT-API 无法解析。
- `ItemCodec` 编码值不适合作为跨版本的长期存储格式。

## 相关页面

- [构建与打包](../../README.md)：模块依赖、仓库与打包
- [Core](core.md)：作用域、调度与回主线程
