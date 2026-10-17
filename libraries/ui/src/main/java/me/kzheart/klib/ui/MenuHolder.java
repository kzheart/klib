package me.kzheart.klib.ui;

import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Bukkit 平台返回契约；挂载状态与会话操作由 Kotlin 实现。
 * 旧 Kotlin 调用 val inventory: Inventory = holder.inventory（或 getInventory()）依赖平台类型；挂载前仍返回 null。
 */
public final class MenuHolder implements InventoryHolder {
    private final MenuHolderState state;
    final MenuRenderer renderer;
    final Player viewer;
    final Map<Integer, ItemStack> renderedItems;
    MenuHolder(Player viewer, MenuSession session, MenuRenderer renderer) {
        state = new MenuHolderState(viewer, session, renderer);
        this.viewer = state.viewer;
        this.renderer = state.renderer;
        this.renderedItems = state.renderedItems;
    }
    void attach(Inventory inventory) { state.attach(inventory); }
    public UUID playerId() { return state.playerId(); }
    public MenuSession session() { return state.session(); }
    public MenuModel model() { return state.model(); }
    public void refresh(MenuModel model) { state.refresh(this, model); }
    public void refresh() { state.refresh(this); }
    public MenuCloseResult close(CloseReason reason) { return state.close(this, reason); }
    @Override public Inventory getInventory() { return state.getInventory(); }
}
