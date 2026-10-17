package me.kzheart.klib.ui;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 当 {@link ItemReturnTarget} 失败或报告溢出时，菜单持有物品的最终兜底去向。
 * Bukkit 桥接会将物品掉落在玩家脚下。
 */
@FunctionalInterface
public interface OverflowSink {
    /**
     * 尽可能接收物品，并准确返回仍无持久去向的物品堆。
     */
    List<ItemStack> returnItems(List<ItemStack> items);
}
