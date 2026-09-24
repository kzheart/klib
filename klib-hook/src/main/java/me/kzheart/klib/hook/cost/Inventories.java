package me.kzheart.klib.hook.cost;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Map;

/** 发放物品，背包放不下的部分掉落在玩家脚下。 */
final class Inventories {
    private Inventories() {
    }

    static void give(Player player, Collection<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack item : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }
}
