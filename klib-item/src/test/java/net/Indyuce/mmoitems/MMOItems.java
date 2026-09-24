package net.Indyuce.mmoitems;

import net.Indyuce.mmoitems.api.Type;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 测试替身：只保留 klib 反射调用的 MMOItems 6.x 方法形状。 */
public final class MMOItems {
    public static final short MARKER = 77;
    public static MMOItems plugin = new MMOItems();

    @SuppressWarnings("deprecation")
    public ItemStack getItem(String type, String id) {
        return "SWORD".equals(type) && "FLAME".equals(id) ? new ItemStack(Material.DIAMOND_SWORD, 1, MARKER) : null;
    }

    @SuppressWarnings("deprecation")
    public static Type getType(ItemStack item) {
        return item.getDurability() == MARKER ? new Type("SWORD") : null;
    }

    @SuppressWarnings("deprecation")
    public static String getID(ItemStack item) {
        return item.getDurability() == MARKER ? "FLAME" : null;
    }
}
