package me.kzheart.klib.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;
import java.util.UUID;

/** 单个菜单会话中携带身份信息的 Bukkit 物品栏持有者。 */
public final class MenuHolder implements InventoryHolder {
    private final UUID playerId;
    private final MenuSession session;
    final MenuRenderer renderer;
    private Inventory inventory;

    MenuHolder(UUID playerId, MenuSession session, MenuRenderer renderer) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.session = Objects.requireNonNull(session, "session");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("menu inventory is already attached");
        }
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public UUID playerId() {
        return playerId;
    }

    public MenuSession session() {
        return session;
    }

    public MenuModel model() {
        return session.model();
    }

    /** 重新渲染模型条目和当前投放区内容。 */
    public void refresh() {
        renderer.render(this);
    }

    /** 关闭会话，并且仅归还一次所有投放区物品。 */
    public MenuCloseResult close(CloseReason reason) {
        return renderer.close(this, reason);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
