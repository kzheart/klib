package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 创建和编辑物品的入口；Java 声明保留可空平台返回类型。 */
public final class Items {
    private Items() { }

    public static ItemBuilder edit(ItemStack item) { return ItemsSupport.edit(item); }
    public static ItemBuilder of(Material material) { return ItemsSupport.of(material); }
    public static ItemBuilder of(String material) { return ItemsSupport.of(material); }
    public static ItemBuilder playerHead() { return ItemsSupport.playerHead(); }
    /** 未设置或服务端不支持时返回 {@code null}。 */
    public static Integer customModelData(ItemStack item) { return ItemsSupport.customModelData(item); }
    public static boolean supportsCustomModelData() { return ItemsSupport.supportsCustomModelData(); }
    public static Material resolveMaterial(String input) { return ItemsSupport.resolveMaterial(input); }
}
