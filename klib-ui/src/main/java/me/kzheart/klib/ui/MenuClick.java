package me.kzheart.klib.ui;

import org.bukkit.entity.Player;

import java.util.Objects;

/** 传递给菜单动作的稳定点击输入。 */
public final class MenuClick {
    private final Player player;
    private final int slot;
    private final MenuClickType type;
    private final Runnable refresh;

    public MenuClick(Player player, int slot) {
        this(player, slot, MenuClickType.LEFT);
    }

    public MenuClick(Player player, int slot, MenuClickType type) {
        this(player, slot, type, () -> { throw new IllegalStateException("Refresh requires an annotated menu"); });
    }

    MenuClick(Player player, int slot, MenuClickType type, Runnable refresh) {
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.player = Objects.requireNonNull(player, "player");
        if (slot < 0) {
            throw new IllegalArgumentException("slot must not be negative");
        }
        this.slot = slot;
        this.type = Objects.requireNonNull(type, "type");
    }

    public void refresh() { refresh.run(); }

    public Player player() {
        return player;
    }

    public int slot() {
        return slot;
    }

    public MenuClickType type() {
        return type;
    }
}
