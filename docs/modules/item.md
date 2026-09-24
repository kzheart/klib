# klib-item

`klib-item` 为 Bukkit 插件提供 Java 8 可用的物品构建、类型化标签、背包操作和传输编码。它适合处理“创建一个带业务标记的物品”“安全扣除或发放物品”“把物品保存为字符串”等任务。

## 接入模块

使用 klib Gradle 插件时，在模块列表中加入 `item`：

```kotlin
klib {
    modules {
        item()
    }
}
```

插件会自动加入并内嵌 `item` 所需的 klib 模块及运行时依赖。当前 `klib-item` 在旧版服务端读写标签时需要 Item-NBT-API；默认构建会把它一并放入最终产物。该制品只在 CodeMC 仓库发布，构建脚本必须声明该仓库，配置见 [Klib Gradle 插件仓库](https://github.com/kzheart/klib-gradle-plugin)。

若不使用 klib Gradle 插件，可直接依赖：

```kotlin
dependencies {
    implementation("me.kzheart.klib:klib-item:<klib-version>")
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156")
    runtimeOnly("de.tr7zw:item-nbt-api:2.12.4")
}
```

直接依赖时，调用方负责把 `klib-item` 和 Item-NBT-API 打入插件或在运行环境提供它们。

## 构建带业务标签的物品

`Items` 是常用入口。`Items.of(...)` 新建物品，`Items.edit(...)` 克隆后编辑已有物品，`build()` 返回独立的 `ItemStack`。

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

名称和 lore 支持 `&` 颜色代码。数量必须处于材质可堆叠范围内；`Items.resolveMaterial(...)` 接受大小写差异、短横线以及带命名空间的材质名，未知材质会直接抛出异常。

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

- `customModelData(...)` 在 1.14 以前的服务端没有对应属性，调用会被忽略；需要区分时先调用 `Items.supportsCustomModelData()`。传入 `null` 清除。
- `glow(true)` 在 1.20.5 及以上使用原生光效覆盖；更早版本添加一级 `LURE` 附魔并隐藏附魔标记。`glow(false)` 只移除由 `glow(true)` 添加的这组附魔与标记。
- `Items.playerHead()` 在 1.13+ 创建 `PLAYER_HEAD`，在 1.12 创建数据值为 3 的 `SKULL_ITEM`。`skullOwner(...)` 要求物品是玩家头颅，否则抛出 `IllegalArgumentException`；传入 `UUID` 时通过 `Bukkit.getOfflinePlayer` 解析。

## 读取和更新标签

标签键必须采用 `namespace:path` 形式。内置类型包括字符串、整数、长整数、双精度数、布尔值和字节数组。

```java
ItemStack item = player.getInventory().getItemInMainHand();
if (TOOL_TYPE.has(item) && "mining".equals(TOOL_TYPE.get(item))) {
    Integer durability = DURABILITY.get(item);
    if (durability != null && durability.intValue() > 0) {
        DURABILITY.set(item, Integer.valueOf(durability.intValue() - 1));
    }
}
```

`get(...)` 在标签缺失时返回 `null`。需要显式的空值语义时改用 `find(...)` 或 `getOrDefault(...)`：

```java
int durability = DURABILITY.getOrDefault(item, Integer.valueOf(0)).intValue();

TOOL_TYPE.find(item).ifPresent(type -> player.sendMessage("工具类型：" + type));
```

在支持 Persistent Data Container 的服务端，标签写入 PDC；1.12 等旧版服务端通过 Item-NBT-API 写入根 NBT。升级服务器后读取旧标签时，适配器会尽力把标签迁移到 PDC。不要直接依赖具体桥接实现，也不要把同一个名称同时定义成不同 Java 类型。

## 背包中的扣除与发放

`InventoryItems` 按 `ItemStack.isSimilar(...)` 比较物品，因此名称、lore、附魔和标签都会参与业务物品的匹配。

```java
ItemStack price = Items.of("DIAMOND").amount(3).build();
if (!InventoryItems.take(player.getInventory(), price, 3)) {
    player.sendMessage("钻石不足");
    return;
}

ItemStack reward = ToolItems.miningTool();
InventoryItems.give(player, reward);
```

`take(...)` 会先确认总量足够，再一次性修改背包，不会出现只扣除一部分的结果。`give(...)` 使用 Bukkit 背包接口发放，放不下的物品会在玩家位置自然掉落，并把掉落物的副本作为返回值交给调用方。

## 保存和传输物品

`ItemCodec` 可以编码单个物品、物品数组、完整背包或位置：

```java
String encoded = ItemCodec.encode(item, true);
ItemStack restored = ItemCodec.decodeItem(encoded);
```

编码值包含 Minecraft 数据版本并可选用 GZIP。跨服务器版本解码时会记录数据版本不一致警告，Minecraft 仍可能升级或拒绝其中的物品。解码器限制输入和解压后的大小，并使用类白名单约束 Java 反序列化；即便如此，也应把编码值视为业务数据，不应把任意超大外部输入直接交给解码器。

## 外部物品系统

`ExternalItems` 用统一的 `prefix:id` 引用生成和识别外部物品。内置四个反射适配器，业务插件不需要在编译期依赖这些插件：

| 前缀 | 插件 | ID 形式 | 识别已有物品 |
| --- | --- | --- | --- |
| `mi` | MMOItems 6.x | `TYPE:ID`，例如 `mi:SWORD:FLAME_BLADE` | 支持 |
| `ni` | NeigeItems | 物品 ID，例如 `ni:heal_potion` | 支持 |
| `ia` | ItemsAdder | `namespace:id`，例如 `ia:ruby:gem` | 支持 |
| `mm` | MythicMobs 4.x / 5.x | 物品 ID | 仅 5.x 支持 |

没有前缀或前缀为 `minecraft` 的引用视为原版材质，例如 `DIAMOND`、`minecraft:diamond`。

探测是显式调用，不会在类加载时自动发生。应在目标插件启用之后调用，并在 `plugin.yml` 中把它们声明为 `softdepend`：

```java
import me.kzheart.klib.item.ExternalItems;

ExternalItems items = ExternalItems.detect();
items.report().forEach(state -> logger().info("外部物品：" + state));
// mi(MMOItems)=AVAILABLE、ni(NeigeItems)=MISSING: 未安装或未启用、mm(MythicMobs)=FAILED: ...

ItemStack potion = items.create("ni:heal_potion", 3).orElse(null);
Optional<ItemRef> ref = items.identify(player.getInventory().getItemInMainHand());
boolean isBlade = items.matches("mi:SWORD:FLAME_BLADE", item);
Predicate<ItemStack> matcher = items.matcher("mi:MATERIAL:SOUL_GEM");   // 预先解析，可重复使用
ItemSpec spec = items.spec("ia:ruby:gem");                             // 与 ItemSpec 组合
```

行为边界：

- `parse`、`matcher` 和 `spec` 在遇到未知前缀或不存在的材质时抛出 `IllegalArgumentException`，便于在加载配置时发现拼写错误。
- 内置前缀对应的插件未安装时，`create` 返回空、`matches` 返回 `false`，不会抛出异常；`report()` 给出每个前缀的状态与失败原因。
- 原版引用只匹配**不被任何已注册外部系统识别**的物品，因此 MMOItems 的钻石不会被当作普通钻石扣除。
- 实例不可变。`with(source)` 返回追加了自定义 `ExternalItemSource` 的新实例；前缀 `minecraft` 保留给原版材质。
- 生成与识别会调用外部插件，遵守这些插件自身的线程要求，通常应在主线程调用。

接入其他物品系统时实现 `ExternalItemSource`（前缀、插件名、`create(id)`、`identify(item)`），再通过 `with(...)` 注册。
`ExternalItems` 同时实现了旧的 `ExternalItemProvider`，可以直接传给 `ItemSpec.Builder.external(...)`。

## 生命周期与线程边界

- `ItemBuilder` 和标签操作会处理 `ItemStack`；涉及在线玩家背包或世界掉落时，应在 Bukkit 主线程执行。
- `Items.edit(...)`、`build()`、标签桥接和编码接口会克隆或创建值，仍不要在其他线程同时修改同一个原始 `ItemStack`。
- `InventoryItems.give(...)` 在背包溢出时访问玩家世界并生成掉落物，只能用于在线玩家的同步流程。
- `ItemCodec` 不是跨 Minecraft 数据版本的稳定数据库格式。长期保存时应保留迁移或无法解码时的降级策略。

