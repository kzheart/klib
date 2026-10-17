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

标签由模块内的原生实现读写，无需额外 NBT 插件或运行时。

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
| `flags(...)` | 直接遵循 Bukkit 的物品元数据语义；标志能否保留取决于对应数据组件是否存在。不要假设 `HIDE_ATTRIBUTES` 在没有显式属性数据时一定持久化；标签写入不会替调用方补造属性修饰符 |
| `Items.playerHead()` | 1.13+ 创建 `PLAYER_HEAD`；1.12 创建数据值为 3 的 `SKULL_ITEM` |
| `skullOwner(...)` | 物品必须是玩家头颅，否则抛 `IllegalArgumentException`；传 `UUID` 时通过 `Bukkit.getOfflinePlayer` 解析 |

## 读取和更新标签

- 标签键必须是 `namespace:path` 形式。
- 内置类型：字符串、整数、长整数、双精度数、布尔值、字节数组。

```java
ItemStack item = player.getInventory().getItemInMainHand().clone();
if (TOOL_TYPE.has(item) && "mining".equals(TOOL_TYPE.get(item))) {
    Integer durability = DURABILITY.get(item);
    if (durability != null && durability.intValue() > 0) {
        DURABILITY.set(item, Integer.valueOf(durability.intValue() - 1));
        player.getInventory().setItemInMainHand(item);
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

`me.kzheart.klib.item.tag` 提供服务端无关的类型化标签树、路径编辑和编解码。`NativeItemTags` 把树连接到物品；业务使用公开入口，不接触 NMS 类名或反射句柄。

常用字段直接用 `getInt` / `putInt` 等方法，不需要先取 `Object` 再强转。下面修改已有非空气物品；在 Bukkit 主线程调用，背包物品的完整写回流程见下一节。

```java
import me.kzheart.klib.item.tag.NativeItemTags;
import me.kzheart.klib.item.tag.NbtCompound;

NbtCompound tags = NativeItemTags.read(item);
Integer previous = tags.getInt("myplugin:level"); // 缺失为 null
int level = tags.getInt("myplugin:level", 0);     // 仅缺失时用默认值
tags.putInt("myplugin:level", level + 1)
    .putBoolean("myplugin:bound", true);
NativeItemTags.write(item, tags);
```

Kotlin 使用同一组方法：

```kotlin
import me.kzheart.klib.item.tag.NativeItemTags

val tags = NativeItemTags.read(item)
val previous: Int? = tags.getInt("myplugin:level")
val level = tags.getInt("myplugin:level", 0)
tags.putInt("myplugin:level", level + 1).putBoolean("myplugin:bound", true)
NativeItemTags.write(item, tags)
```

### 安全读取与背包写回

少量业务标记仍可优先用 `TagKey<T>`，不必管理整棵树。它的 `find` / `getOrDefault` 和原生树的默认重载都只处理缺失值，不吞掉类型错误；Java 不要直接拆箱可能为 `null` 的返回值。

| 读取方式 | 缺失 / 类型规则 |
| --- | --- |
| `tags.getInt(key)` 等 `getX` | 缺失返回 nullable；存在但错型时抛异常 |
| `tags.getInt(key, fallback)` 等默认重载 | 仅缺失时返回传入默认值，不将错型转换为默认值 |
| `tag.asInt()`、`asString()`、`asCompound()`、`asList()` 等 | 精确类型读取；例如 Short 不会自动变 Int，标量也不会自动包成 List |
| `tag.asNumber()` | 只接受六种数值类型；由调用方显式选择 `intValue()` / `toInt()` 等转换，不解析字符串 |
| `getBoolean` / `asBoolean` | 只读 Byte：0 为 false，非 0 为 true；不读字符串或其他数值类型 |
| `getUuid` / `asUuid` | 读取规范 UUID 字符串；错误类型或非法文本会失败 |

修改玩家背包时，在主线程执行“取出 → 克隆 → 修改 → 显式写回”，不要依赖某个平台恰好返回能影响原槽位的对象：

```java
import me.kzheart.klib.item.tag.NativeItemTags;
import me.kzheart.klib.item.tag.NbtCompound;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

ItemStack current = player.getInventory().getItem(0);
if (current != null && current.getType() != Material.AIR) {
    ItemStack changed = current.clone();
    NbtCompound update = NativeItemTags.read(changed);
    update.putInt("myplugin:level", update.getInt("myplugin:level", 0) + 1);
    update.remove("myplugin:obsolete");
    NativeItemTags.write(changed, update);
    player.getInventory().setItem(0, changed);
}
```

Kotlin 对应使用 `player.inventory.getItem(0)`、`current.clone()` 与 `player.inventory.setItem(0, changed)`。删除 `TagKey` 用 `key.remove(changed)`；删除原生键用 `tags.remove(name)` 后再 `write`。`null` 不是删除指令，修改克隆也不会自动更新原槽。

### 标签类型与复制

| 数据 | 构造 / 流式写入 | 精确读取 |
| --- | --- | --- |
| Byte / Short / Int / Long / Float / Double | `NbtTag.byteValue(...)` 等；`putByte` / `putShort` / `putInt` / `putLong` / `putFloat` / `putDouble` | 对应 `asX` / `getX` |
| String | `NbtTag.string(...)`；`putString` | `asString` / `getString` |
| ByteArray / IntArray / LongArray | `NbtTag.bytes/ints/longs(...)`；`putByteArray/putIntArray/putLongArray` | `asByteArray/asIntArray/asLongArray`；对应 `getX` |
| List | `NbtList.of(...)` 或 `new NbtList()`；`putList` | `asList` / `getList` |
| Compound | `new NbtCompound()`；`putCompound` | `asCompound` / `getCompound` |
| Boolean / UUID 便利值 | `NbtTag.booleanValue/uuidValue(...)`；`putBoolean/putUuid` | `asBoolean/asUuid`；`getBoolean/getUuid` |

- 可存储类型仍是 12 种；END 只作二进制结束标记。Boolean 编码为 Byte 0/1，UUID 编码为规范字符串，不增加新的 NBT 类型。
- 数组输入和读取均复制；`put` / `putX` / 列表写入深复制传入值。已取得的嵌套 compound/list 属于当前树，编辑会影响该树；需要独立子树时用 `copy()`。
- `entries()`、`keys()`、`values()` 和迭代器不允许修改集合结构；它们不把嵌套值变成不可变对象。默认重载在缺失时直接返回传入的默认对象，不替调用方复制该默认数组或容器。
- `NbtTag.from(value)` 只接受精确标量、数组或已有标签，仍不自动转换 Boolean、UUID、Map、List 或 null。
- 递归转换显式用 `NbtTag.ofMap(map)` / `ofList(list)`：保留数值类型，Map 键必须是 String，拒绝 null、循环容器与混型列表。Boolean/UUID 先包装成对应 `NbtTag`；转换深度以根为 0，最多 512，嵌入的标签子树也计入。
- `value()` 只用于标量/数组；compound/list 使用各自的访问方法，直接调用其 `value()` 会失败。树的 `toString()` 用于展示，存储格式使用下面的 `NbtCodec`。

### 路径与列表编辑

普通 `get` / `put` / 下标访问始终把键当作字面值；只有 `getPath` / `putPath` / `removePath` / `containsPath` 解析路径。下面先创建已有列表，再修改第一个元素：

```java
import me.kzheart.klib.item.tag.NbtCompound;
import me.kzheart.klib.item.tag.NbtList;
import me.kzheart.klib.item.tag.NbtTag;

NbtCompound tags = new NbtCompound();
tags.putList("runes", NbtList.of(new NbtCompound().putString("name", "fire")));
tags.putPath("runes[0].name", NbtTag.string("ice"));
String name = tags.getPath("runes[0].name").asString(); // 上面已创建该路径
tags.putInt("literal.key", 7);
int literal = tags.getPath("literal\\.key").asInt();
```

```kotlin
import me.kzheart.klib.item.tag.NbtCompound
import me.kzheart.klib.item.tag.NbtList
import me.kzheart.klib.item.tag.NbtTag

val tags = NbtCompound()
tags.putList("runes", NbtList.of(NbtCompound().putString("name", "fire")))
tags.putPath("runes[0].name", NbtTag.string("ice"))
val name: String? = tags.getPath("runes[0].name")?.asString()
tags.putInt("literal.key", 7)
val literal: Int? = tags.getPath("literal\\.key")?.asInt()
```

| 操作 | 规则 |
| --- | --- |
| 路径语法 | 点分非空键、`[0]` 这样的非负列表下标；反斜线只转义 `. [ ]` 和反斜线。空键只能用普通键访问 |
| `getPath` / `containsPath` | 缺失键返回 null / false；已有节点错型仍抛异常，列表越界也抛异常 |
| `putPath` | 可创建缺失的 compound 层级；不创建列表、不扩展现有列表、不补索引空洞。路径或类型校验失败不修改原树 |
| `removePath` | 缺失键返回 null；不自动删除空祖先。删除列表元素会让后续下标前移 |
| `list.add` / `set` / `removeAt` / `clear` | 输入复制；非空列表必须保持同型，即使只有一个元素也不能用 `set` 改变类型。清空后可重新选择元素类型 |

路径最多 512 个键/下标段；这是路径本身的预算，不代表插入子树一定满足整体二进制或文本深度限制。列表的 `values()` 是只读结构视图，编辑其嵌套 compound 仍会修改原列表。

### 根标签与现代 custom_data

| `NativeItemTags.storage()` | `read` / `write` 读取和替换的范围 |
| --- | --- |
| `ItemTagStorage.LEGACY_ROOT` | 旧物品的 tag 根，可能包含显示、附魔及插件数据；不含完整物品的材质与数量 envelope |
| `ItemTagStorage.CUSTOM_DATA` | 现代物品的 `minecraft:custom_data`，其他 data components 保持原样 |

`write(item, tags)` **完整替换当前槽位**。通常使用 `read → edit → write` 保留已有内容及其中的 PDC；直接写入新的空 compound 会清空该槽位，现代服务端同时移除空 custom_data 组件。

- `supports()` 只探测基础 tag/custom_data 布局与编解码，不保证任意输入可写，也不代表完整物品模式可用；完整模式单独延迟解析，不改变基础模式的可用性。
- 基础模式不支持时，`storage()` 和非空物品读写明确失败。读取空气返回空 compound，向空气写入失败。
- 读写受 16 MiB 二进制大小与 512 层深度限制；`read` / `write` 不编辑其他 components，也不序列化完整物品。完整持久化树使用下面的 `readItem` / `createItem`。

### NBT 二进制、SNBT 与 typed JSON

`NbtCodec` 只处理标签树，无需 Bukkit 服务端。Java 与 Kotlin 使用同一组静态入口；三种格式不能混读：

| 格式 | 入口与根类型 | 边界 |
| --- | --- | --- |
| 标准 NBT bytes | `toBytes(NbtCompound)` / `fromBytes(bytes)` | 未压缩、命名根 compound；输出根名为空，输入根名读取后不进入树；不自动识别 GZIP |
| SNBT | `toSnbt(NbtTag)` / `fromSnbt(text)` | 可读写任意标签，解析后按需要 `asCompound()`；采用传统 SNBT 语法 |
| 类型后缀 JSON | `toJson(NbtTag)` / `fromJson(text)` | 12 种标签的后缀格式，保留字面键和空数组；不是普通 JSON 数字或 Minecraft item codec JSON |

`toBytes` / `fromBytes` 对非法、截断、超出预算的二进制数据或超长 modified UTF-8 值抛出 `IllegalArgumentException`；底层 `IOException` 会包装为该异常并保留 cause。Java 调用者无需添加 checked exception 的 `catch` 或 `throws`。

```java
import me.kzheart.klib.item.tag.NbtCodec;
import me.kzheart.klib.item.tag.NbtCompound;

NbtCompound data = new NbtCompound().putInt("level", 5).putString("name", "demo");
byte[] bytes = NbtCodec.toBytes(data);
NbtCompound binaryCopy = NbtCodec.fromBytes(bytes);
String snbt = NbtCodec.toSnbt(data);
NbtCompound textCopy = NbtCodec.fromSnbt(snbt).asCompound();
String json = NbtCodec.toJson(data);
NbtCompound jsonCopy = NbtCodec.fromJson(json).asCompound();
```

```kotlin
import me.kzheart.klib.item.tag.NbtCodec
import me.kzheart.klib.item.tag.NbtCompound

val data = NbtCompound().putInt("level", 5).putString("name", "demo")
val binaryCopy = NbtCodec.fromBytes(NbtCodec.toBytes(data))
val textCopy = NbtCodec.fromSnbt(NbtCodec.toSnbt(data)).asCompound()
val jsonCopy = NbtCodec.fromJson(NbtCodec.toJson(data)).asCompound()
```

上例 JSON 为 `{"level":"5i","name":"demot"}`：数值使用 b/s/i/l/f/d 后缀，String 使用 t，数组使用 b]/i]/l]。后缀严格小写，列表和 compound 仍用 JSON array/object；普通 JSON number、boolean、null 不会被自动转成 NBT。

#### 格式与预算细节

<details>
<summary>展开：文本语法、数值精度、深度与大小上限</summary>

- 二进制输入/输出总量最多 16 MiB，根深度为 0、最大 512；每个 key 和 String 值按 modified UTF-8 编码最多 65,535 字节。总量上限不代表单个字符串可达 16 MiB，字节上限也不等于字符数。没有内置压缩入口。
- SNBT/JSON 的输入和输出按 UTF-8 最多 16 MiB，根深度为 0、最大 512；标签与数组元素合计最多 1,048,576 个，根也计一个。数字主体最多 128 个字符。
- 文本拒绝重复 compound 键、混型列表和尾随内容；数值语法成立后，整数越界、浮点溢出和非零值下溢为零会失败，正常有限浮点数遵循 IEEE 754 舍入。
- SNBT 支持数字后缀、`[B;1b]` / `[I;1]` / `[L;1L]`、单双引号与容器尾随逗号。`true` / `false` 读为 Byte 1/0；无后缀 `1e3` 与前导零的 `01` / `01b` 是 String，`1.0e3` 是 Double。不要依赖其他服务端版本专属的语法扩展。
- SNBT 引号中只转义对应引号和反斜线，不解释 JSON 的 `\n` / `\u` 转义；控制字符原样保留。写入拒绝非有限浮点数和孤立 UTF-16 surrogate；`NaNf` / `Infinityd` 等非有限数值 token 也被拒绝，作为文本保存时须加引号。
- Typed JSON 支持标准 JSON 转义，拒绝尾随逗号；空 String 编为 `"t"`，空数组编为 `"b]"` / `"i]"` / `"l]"`。NaN/Infinity 用带 f/d 后缀的字符串保留类型，不保证 NaN payload 位；转义的孤立 UTF-16 surrogate 可保留，原始孤立 surrogate 不接受。
- 纯文本 codec 自身保留有限 Float/Double 与负零；经过服务端 NBT 或物品 codec 后，宿主可能规范化数值或默认组件，不保证逐 bit 相同。

</details>

### 完整物品快照与重建

`readItem(item)` 导出当前服务端的完整持久化树；`createItem(snapshot)` 返回一个**新的** `ItemStack`。它们与仅处理 tag/custom_data 的 `read` / `write` 分开，不用布尔开关混淆两种范围。

```java
import me.kzheart.klib.item.tag.NativeItemTags;
import me.kzheart.klib.item.tag.NbtCodec;
import me.kzheart.klib.item.tag.NbtCompound;
import org.bukkit.inventory.ItemStack;

// Bukkit 主线程；item 是已有非空气物品，player 是目标玩家
NbtCompound snapshot = NativeItemTags.readItem(item);
String saved = NbtCodec.toSnbt(snapshot);
ItemStack restored = NativeItemTags.createItem(NbtCodec.fromSnbt(saved).asCompound());
player.getInventory().setItem(0, restored);
```

```kotlin
import me.kzheart.klib.item.tag.NativeItemTags
import me.kzheart.klib.item.tag.NbtCodec

val snapshot = NativeItemTags.readItem(item)
val saved = NbtCodec.toBytes(snapshot)
val restored = NativeItemTags.createItem(NbtCodec.fromBytes(saved))
player.inventory.setItem(0, restored)
```

- 旧格式包含材质、数量、Damage/tag 等持久化字段；现代格式通过服务端 registry-aware codec 处理 id、count 和可持久化 components。树的字段形状属于当前服务端，不是 Klib 的跨版本统一 schema。
- 现代快照是持久化表示，可能省略默认组件或使用组件差量；它不保证枚举每一种可用组件、所有默认值或不可持久化的运行状态。
- `minecraft:custom_data` 使用精确 NBT 读写保留原类型；Byte / Int / Long 列表与同值数组保持区别，不由宿主组件 codec 将列表规范化成数组。
- `readItem` 不修改原物品，`createItem` 不修改输入树或已有物品；返回值不会自动进入背包，仍须显式 `setItem`。不要用依次修改 type/amount/meta 模拟完整重建。
- 空气物品不能导出；重建要求非空 String `id` 和当前 schema 的正数数量：旧格式 `Count` 为 Byte，现代 `count` 为 Int，仅 custom_data 树不能直接传入。
- 空气/未知物品结果、数量与请求不一致和服务端 codec 报告的解析失败会抛异常，不退回原物品或接受 partial 结果；未知/多余字段如何处理仍由宿主 codec 决定。
- 基础 `supports()` 成功不保证完整模式可用。完整模式遇到不支持的宿主布局会明确失败；也受 16 MiB / 512 层边界约束。
- 没有 DataFixer，不执行跨 Minecraft 数据版本升级，也不自动附加来源 DataVersion。长期持久化时另存来源数据版本并制定迁移策略；Bukkit 物品、数组和背包的既有存储入口仍见下方 `ItemCodec`，两者编码不可混读。

### 能力与边界

| 范围 | Klib 入口 | 行为 |
| --- | --- | --- |
| 标签与便捷取值 | 12 种值、`asX`、compound `getX/putX`、Boolean/UUID | 精确类型读取；只有缺失时使用默认值，错类型明确失败 |
| 深路径与列表编辑 | `getPath/putPath/removePath`、可变 `NbtList` | 支持转义字面键与列表索引；列表保持同型，输入深复制 |
| 文本与二进制 | `NbtCodec` | 离线 SNBT、标准未压缩 NBT bytes、类型后缀 JSON；各格式边界见上文 |
| 业务标签槽位 | `NativeItemTags.read/write` | 读取 tag / custom_data，整槽替换并显式写回 |
| 完整持久化物品 | `readItem/createItem` | 处理当前服务端完整持久化树；可用性与字段解释由宿主决定 |
| 冒险模式破坏 / 放置限制 | 无独立 helper | 能编辑完整树，不代表提供了统一跨版本语义 helper |

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

- `ItemBuilder`、标签与 `NativeItemTags` 的物品读写在 Bukkit 主线程执行。独立的 `NbtCompound` / `NbtList` 值树和 `NbtCodec` 不访问 Bukkit，但不要跨线程并发修改同一树。
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
