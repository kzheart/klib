package me.kzheart.klib.ui;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 确保未归还物品不会被静默丢弃的关闭结果。 */
public final class MenuCloseResult {
    private final CloseReason reason;
    private final List<ItemStack> unreturned;

    MenuCloseResult(CloseReason reason, List<ItemStack> unreturned) {
        this.reason = reason;
        List<ItemStack> copy = new ArrayList<ItemStack>(unreturned.size());
        for (ItemStack item : unreturned) {
            copy.add(item.clone());
        }
        this.unreturned = Collections.unmodifiableList(copy);
    }

    public CloseReason reason() {
        return reason;
    }

    /**
     * 这些物品堆是关闭时为本结果创建的防御性副本；修改前请先克隆。
     */
    public List<ItemStack> unreturned() {
        return unreturned;
    }
}
