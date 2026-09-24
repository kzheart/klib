package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Objects;

/** 创建和编辑物品的入口。 */
public final class Items {
    private Items() {
    }

    public static ItemBuilder edit(ItemStack item) {
        return new ItemBuilder(Objects.requireNonNull(item, "item").clone());
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(new ItemStack(Objects.requireNonNull(material, "material")));
    }

    public static ItemBuilder of(String material) {
        return of(resolveMaterial(material));
    }

    /** 创建玩家头颅：1.13+ 为 PLAYER_HEAD，1.12 为 SKULL_ITEM 的数据值 3。 */
    @SuppressWarnings("deprecation")
    public static ItemBuilder playerHead() {
        Material modern = Material.getMaterial("PLAYER_HEAD");
        if (modern != null) {
            return of(modern);
        }
        Material legacy = Material.getMaterial("SKULL_ITEM");
        if (legacy == null) {
            throw new IllegalStateException("Server has neither PLAYER_HEAD nor SKULL_ITEM");
        }
        return new ItemBuilder(new ItemStack(legacy, 1, (short) 3));
    }

    /** 读取 CustomModelData；未设置或服务端不支持时返回 {@code null}。 */
    public static Integer customModelData(ItemStack item) {
        if (InventoryItems.isAir(item) || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        Object present = ItemMetaReflection.invokeOrNull(meta, "hasCustomModelData");
        if (!Boolean.TRUE.equals(present)) {
            return null;
        }
        return (Integer) ItemMetaReflection.invokeOrNull(meta, "getCustomModelData");
    }

    /** 当前服务端的 ItemMeta 是否支持 CustomModelData（1.14+）。 */
    public static boolean supportsCustomModelData() {
        try {
            ItemMeta.class.getMethod("setCustomModelData", Integer.class);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    public static Material resolveMaterial(String input) {
        Objects.requireNonNull(input, "material");
        String name = input.trim();
        int separator = name.indexOf(':');
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        if (material == null) {
            throw new IllegalArgumentException("Unknown material: " + input);
        }
        return material;
    }
}
