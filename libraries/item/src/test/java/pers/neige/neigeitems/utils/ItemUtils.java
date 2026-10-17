package pers.neige.neigeitems.utils;

import pers.neige.neigeitems.manager.ItemManager;
import org.bukkit.inventory.ItemStack;

/** 测试替身。 */
public final class ItemUtils {
    private ItemUtils() {
    }

    @SuppressWarnings("deprecation")
    public static String getItemId(ItemStack item) {
        return item != null && item.getDurability() == ItemManager.MARKER ? "heal_potion" : null;
    }
}
