package me.kzheart.klib.ui.drop;

import java.util.Map;
import org.bukkit.inventory.ItemStack;

/** 投放输入的 Java 平台返回契约；快照与校验由 Kotlin 实现。
 * 旧 Kotlin 调用 if (action.item() != null) action.item().amount 依赖平台类型；TAKE 仍无物品快照。
 */
public final class InventoryAction {
    public enum Type { PLACE, SHIFT_INSERT, DRAG, TAKE }
    private final InventoryActionValue state;
    private InventoryAction(InventoryActionValue state) { this.state = state; }
    public static InventoryAction place(int slot, ItemStack item, int amount) { return new InventoryAction(InventoryActionValue.place(slot, item, amount)); }
    public static InventoryAction shiftInsert(ItemStack item) { return new InventoryAction(InventoryActionValue.shiftInsert(item)); }
    public static InventoryAction drag(ItemStack item, Map<Integer, Integer> amounts) { return new InventoryAction(InventoryActionValue.drag(item, amounts)); }
    public static InventoryAction take(int slot, int amount) { return new InventoryAction(InventoryActionValue.take(slot, amount)); }
    public Type type() { return state.type(); }
    public int slot() { return state.slot(); }
    public ItemStack item() { return state.item(); }
    public int amount() { return state.amount(); }
    public Map<Integer, Integer> dragAmounts() { return state.dragAmounts(); }
}
