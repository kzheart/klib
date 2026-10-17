# Item 模块

Java 8 可用的物品构建、类型化标签、背包扣除/发放和物品编码。适合「创建带业务标记的物品」「安全扣除或发放物品」「把物品保存为字符串」这类任务。

| 模块名 | 制品 |
| --- | --- |
| `item` | `me.kzheart.klib:klib-item` |

## 快速开始

```kotlin
klib { modules { item() } }
```

依赖、直接引用制品和打包方式见 [构建与打包](../../README.md)。

标签由模块内的原生实现读写，不需要 Item-NBT-API、额外 NBT 插件或 TabooLib 运行时，也无需为标签功能添加 CodeMC 仓库。

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
- 没有 PDC 的旧版服务端：通过模块内的原生桥写入根 NBT。
- PDC 缺失且原生桥支持当前布局时，读取旧标签并尝试迁移；确认 PDC 写回成功后才删除旧键。迁移写回失败仍返回本次已读到的旧值。
- 原生桥不支持当前布局时，已有 PDC 标签仍可正常读写、检查和删除；不会为了回退而加载第三方 NBT 库。
- 在支持的平台上，显式读取或删除的 NBT 操作、类型错误继续抛出，不把损坏数据伪装成标签缺失。

`TagKey` 适合插件自己的少量业务标记，业务代码不需要选择桥接实现。需要嵌套、列表或全部 NBT 数值类型时，使用下面的原生标签入口。

## 原生 NBT 标签

`me.kzheart.klib.item.tag` 提供服务端无关的标签树与 `NativeItemTags`。物品入口负责平台探测和写回；业务只编辑独立快照，不接触 NMS 类名或反射句柄。

```java
import me.kzheart.klib.item.tag.NativeItemTags;
import me.kzheart.klib.item.tag.NbtCompound;
import me.kzheart.klib.item.tag.NbtList;
import me.kzheart.klib.item.tag.NbtTag;

// 在 Bukkit 主线程执行；item 是已有的非空气物品
NbtCompound tags = NativeItemTags.read(item);
tags.put("myplugin:level", NbtTag.intValue(5));
tags.put("myplugin:runes", NbtList.of(NbtTag.string("fire"), NbtTag.string("ice")));
NativeItemTags.write(item, tags);
```

Kotlin 使用相同入口和类型，也可用下标编辑：

```kotlin
import me.kzheart.klib.item.tag.NativeItemTags
import me.kzheart.klib.item.tag.NbtTag

val tags = NativeItemTags.read(item)
tags["myplugin:level"] = NbtTag.intValue(5)
NativeItemTags.write(item, tags)
```

### 标签类型与复制

| NBT 类型 | 构造方式 |
| --- | --- |
| Byte / Short / Int / Long / Float / Double | `NbtTag.byteValue(...)`、`shortValue(...)`、`intValue(...)`、`longValue(...)`、`floatValue(...)`、`doubleValue(...)` |
| String | `NbtTag.string(...)` |
| ByteArray / IntArray / LongArray | `NbtTag.bytes(...)`、`ints(...)`、`longs(...)` |
| List | `NbtList.of(...)`；元素必须同型，空列表没有元素类型 |
| Compound | `new NbtCompound()`；用 `put` / `get` / `remove` 编辑 |

- 这 12 种是可存储的数据类型；END 只用作二进制结束标记。布尔值应明确编码为 Byte，不存在独立的 Boolean NBT 类型。
- `NbtTag.from(value)` 接受精确的标量、数组或已有 `NbtTag`；不自动缩窄数字，不把 `null` 当作删除，也不自动转换任意 Map/List。
- 数组输入、取出和 `copy()` 都复制。复合值写入时深复制，`copy()` 可取得独立树；从已有树取得的嵌套复合值仍属于该树。
- `entries()`、`keys()`、`values()` 不允许修改集合结构；需要独立的嵌套值时仍应调用 `copy()`。

### 根标签与现代 custom_data

| `NativeItemTags.storage()` | 读取和替换的范围 |
| --- | --- |
| `ItemTagStorage.LEGACY_ROOT` | 旧物品的根 NBT，可能包含显示、附魔及插件数据 |
| `ItemTagStorage.CUSTOM_DATA` | 现代物品的 `minecraft:custom_data`，其他 data components 保持原样 |

`write(item, tags)` **完整替换当前槽位**。通常使用 `read → edit → write` 保留已有内容及其中的 PDC；直接写入新的空 compound 会清空该槽位，现代服务端同时移除空 custom_data 组件。

- `supports()` 只确认当前服务端布局与原生编解码能力，不保证任意输入都能写入；不支持时 `storage()` 和非空物品读写明确失败。
- 读取空气物品返回空 compound，向空气物品写入会失败。读写受 16 MiB 二进制大小与 512 层嵌套深度限制。
- 本入口不编辑全部 data components，不序列化完整物品，也不提供 DataFixer 或跨 Minecraft 数据版本升级。持久化完整物品仍见下方 `ItemCodec`，并遵守其迁移限制。

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

`ExternalItems` 用统一的 `prefix:id` 引用生成、预览和识别外部物品。五个来源支持自动探测，CraftEngine 需显式注册就绪判据；业务插件不需要在编译期依赖这些插件：

| 前缀 | 插件 | ID 形式 | 识别已有物品 |
| --- | --- | --- | --- |
| `zap` | ZaphkielPlus | 物品 ID，例如 `zap:heal_potion` | 支持 |
| `mi` | MMOItems 6.x | `TYPE:ID`，例如 `mi:SWORD:FLAME_BLADE` | 支持 |
| `ni` | NeigeItems | 物品 ID，例如 `ni:heal_potion` | 支持 |
| `ia` | ItemsAdder | `namespace:id`，例如 `ia:ruby:gem` | 支持 |
| `mm` | MythicMobs 4.x / 5.x | 物品 ID | 仅 5.x 支持 |
| `ce` | CraftEngine，显式注册 | `namespace:id`，例如 `ce:ruby:gem` | 支持 |

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

### 安全预览

```java
ItemPreview preview = items.preview("zap:heal_potion", player);
if (preview.status() == ItemPreview.Status.READY) {
    ItemStack icon = preview.item().get();
    // 只放入展示槽；不能把 icon 当作发货结果
} else {
    logger().info("无法预览：" + preview.status() + "，" + preview.detail());
}
```

`player` 可以为 `null`。Java 和 Kotlin 共用同一个入口与 `ItemPreview`；成功结果在存入和取出时都克隆物品。

| `ItemPreview.Status` | 含义 |
| --- | --- |
| `READY` | 有可展示样本，`item()` 包含独立副本 |
| `UNSUPPORTED` | 来源未提供安全预览能力；不会改调真实生成 |
| `NOT_READY` | 来源未挂钩、停用，或显式加载状态尚未就绪 |
| `UNKNOWN_ID` | 来源已就绪，但没有该定义 |
| `FAILED` | 预览调用或挂钩失败；`detail()` 与 `failure()` 保留诊断信息 |

- 原版材质直接构建展示样本；Zap 只调用其真实 `preview(String, Player)` API，不分配实例身份，也不调用真实生成。
- 不支持专用预览的其他来源返回 `UNSUPPORTED`。自定义 `ExternalItemSource` 的默认实现同样不会回退到 `create`。
- 未知前缀或非法材质仍在解析时抛异常；已解析引用的提供者调用异常转换为 `FAILED`。非 `READY` 结果没有物品。
- `report()` 的 `AVAILABLE` 表示适配器已挂钩，不承诺内容已加载或支持安全预览。

### CraftEngine 薄适配

CraftEngine 的公开加载表在首次加载前可能为空，合法空配置也可能为空。Klib 不以表是否为空猜测就绪，也不自行注册加载事件或维护第二套状态机。

```java
AtomicBoolean contentLoaded = new AtomicBoolean(false);
ExternalItems items = ExternalItems.detect().with(
        ExternalItemSources.craftEngine(craftEnginePlugin, contentLoaded::get));
// 由业务已确认的加载完成信号更新 contentLoaded；初次加载、重载及停用时保持准确
```

- 以官方 `CraftEngineReloadEvent` 等可靠信号确认内容完成加载；注册较晚时也需确认当前状态，不能因为插件已启用就置为就绪。
- `create` 在插件停用或判据为 `false` 时返回空；就绪后找不到定义也返回空。`preview` 分别报告 `NOT_READY` 与 `UNKNOWN_ID`。
- 已存在定义的 `preview` 返回 `UNSUPPORTED`。无玩家 `buildBukkitItem()` 也会执行物品处理器，不能视为无副作用的预览；这条路径不会调用任何构建重载。
- 真实 `create` 使用官方 `CraftEngineItems.byId(String)` 返回的公开定义，按玩家是否存在选择 `buildBukkitItem(Player)` 或无参重载；每次重新查询定义，避免重载后保留旧定义。
- `identify` 通过官方 `getCustomItemId(ItemStack)` 读取身份，不读私有标签或生成样本。停用或定义暂不可用时仍保留只读识别；若上游读取失败则传播异常，不把故障物品当作原版材料。
- 适配器构造时校验所需公开方法形状。定义构建方法位于官方稳定 `api` 包之外，集中在这一个适配器内反射；API 变化时明确失败，不承诺所有 CraftEngine 版本通用。

所适配的[官方物品入口](https://github.com/Xiao-MoMi/craft-engine/blob/b3079c5ac170fff2cb2a4c4dad184771a236cd1b/bukkit/src/main/java/net/momirealms/craftengine/bukkit/api/CraftEngineItems.java)与[公开定义构建路径](https://github.com/Xiao-MoMi/craft-engine/blob/b3079c5ac170fff2cb2a4c4dad184771a236cd1b/bukkit/src/main/java/net/momirealms/craftengine/bukkit/item/BukkitItemDefinition.java)是该边界的依据。

物品身份匹配不等于交易验证。唯一实例、绑定、交付快照、库存同步 token、支付与补偿继续由业务及对应平台处理，预览不会获得这些权限。

### 接入其他物品系统

- 实现 `ExternalItemSource`（前缀、插件名、`create(id)`、`identify(item)`），再通过 `with(...)` 注册。
- `ExternalItems` 实例不可变，`with(source)` 返回追加了该来源的新实例。前缀 `minecraft` 保留给原版材质。
- `ExternalItems` 同时实现旧的 `ExternalItemProvider`，可以直接传给 `ItemSpec.Builder.external(...)`。

## Kotlin 物品声明

```kotlin
import me.kzheart.klib.item.*
import org.bukkit.Material

val icon = item(Material.STONE) { amount = 2; name = "&a石头" }
val copy = icon.editItem { amount = 3 }
val level = TagKey.integer("demo:level")
val tagged = item(Material.STONE) {
    amount = 1
    lore = listOf("&7可升级")
    this[level] = 5
}
val current: Int? = tagged[level]
```

`amount`、`name`、`lore`、`customModelData`、`unbreakable` 是可读写属性。若外层局部变量同名，用 `this.amount = value` 明确指定接收者。`this[key] = value` 按 `TagKey<T>` 推断值类型，读到缺失标签返回 `null`，删除用 `removeTag(key)`。

对普通 `ItemStack` 使用 `stack[key] = value` 会修改该接收者；需要副本时用 `editItem`。

`item` 与 `editItem` 共用 `ItemBuilder` 的校验与版本处理。编辑不会更改原物品，返回值也不共享 builder 的可变物品。

构建器、类型化标签、原生标签和外部物品适配由同一套 Kotlin 实现承担，Java 继续使用静态工厂、构造器和 Builder。`ItemTagBridge`、`ExternalItemSource` 等 Java SPI 保留，便于现有自定义实现继续使用 Java lambda 与平台类型契约。

## 线程与生命周期

- `ItemBuilder`、标签与 `NativeItemTags` 的物品读写在 Bukkit 主线程执行。独立的 `NbtCompound` / `NbtList` 值树不访问 Bukkit，但不要跨线程并发修改同一树。
- `Items.edit(...)`、`build()`、标签桥接和编码接口会克隆或创建值，但仍不要在其他线程同时修改同一个原始 `ItemStack`。
- `InventoryItems.give(...)` 背包溢出时会访问玩家世界并生成掉落物，只能用于在线玩家的同步流程。
- 外部物品的生成、预览与识别会调用外部插件，遵守这些插件自身的线程要求，通常在主线程调用。预览入口不会自动切换线程。
- CraftEngine 的 `contentReady` 判据由调用方拥有，并在同一线程读取或提供线程安全可见性。更换插件实例或类加载器后，重新创建适配器与 `ExternalItems`；内容重载无需缓存旧定义。

## 常见坑

- 不要直接依赖标签的具体桥接实现（PDC / NBT）。
- 不要把同一个标签名称同时定义成不同 Java 类型。
- `InventoryItems` 用 `isSimilar` 匹配，名称、lore、附魔或标签不同的物品不会被算作同一种。
- 不要把 `CUSTOM_DATA` 当作完整物品的根 NBT；直接用空树写回会删除该槽位中的现有数据。
- `ItemCodec` 编码值不适合作为跨版本的长期存储格式。
- 展示物品不能用于发货；安全预览不支持时应使用业务选择的占位图标，不能悄悄改为真实生成。

## 相关页面

- [构建与打包](../../README.md)：模块依赖、仓库与打包
- [Core](core.md)：作用域、调度与回主线程
