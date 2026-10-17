package pers.neige.neigeitems.manager;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 测试替身：模拟 Kotlin object 编译出的 INSTANCE 形状。 */
public final class ItemManager {
    public static final short MARKER = 88;
    public static final ItemManager INSTANCE = new ItemManager();

    private ItemManager() {
    }

    @SuppressWarnings("deprecation")
    public ItemStack getItemStack(String id) {
        return "heal_potion".equals(id) ? new ItemStack(Material.POTION, 1, MARKER) : null;
    }
}
