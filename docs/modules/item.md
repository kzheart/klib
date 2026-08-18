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

需要接入 MMOItems、NeigeItems、ItemsAdder 等系统时，实现 `ExternalItemProvider`，把“按提供器和 ID 创建物品”以及“识别已有物品”隔离在适配器中。klib 不会自动发现这些插件，也不会把具体第三方 API 泄漏到通用物品逻辑里。

## 生命周期与线程边界

- `ItemBuilder` 和标签操作会处理 `ItemStack`；涉及在线玩家背包或世界掉落时，应在 Bukkit 主线程执行。
- `Items.edit(...)`、`build()`、标签桥接和编码接口会克隆或创建值，仍不要在其他线程同时修改同一个原始 `ItemStack`。
- `InventoryItems.give(...)` 在背包溢出时访问玩家世界并生成掉落物，只能用于在线玩家的同步流程。
- `ItemCodec` 不是跨 Minecraft 数据版本的稳定数据库格式。长期保存时应保留迁移或无法解码时的降级策略。

完整的标签、发放和编码组合用法见本页“完整示例”。
