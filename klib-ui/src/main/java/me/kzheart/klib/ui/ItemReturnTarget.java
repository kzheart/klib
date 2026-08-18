package me.kzheart.klib.ui;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 归还菜单持有的物品，并报告仍需调用方处理的物品堆。 */
@FunctionalInterface
public interface ItemReturnTarget {
    List<ItemStack> returnItems(List<ItemStack> items);
}
