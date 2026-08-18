package me.kzheart.klib.ui;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/** 渲染到菜单槽位中的不可变物品与动作对。 */
public final class MenuEntry {
    private final ItemStack item;
    private final MenuAction action;
    private final String clickSound;

    private MenuEntry(ItemStack item, MenuAction action, String clickSound) {
        this.item = Objects.requireNonNull(item, "item").clone();
        this.action = Objects.requireNonNull(action, "action");
        this.clickSound = clickSound;
    }

    public static MenuEntry of(ItemStack item) {
        return new MenuEntry(item, MenuAction.none(), null);
    }

    public static MenuEntry of(ItemStack item, MenuAction action) {
        return new MenuEntry(item, action, null);
    }

    public ItemStack item() {
        return item.clone();
    }

    public MenuAction action() {
        return action;
    }

    /**
     * 点击时由桥接层播放的可选 Bukkit {@code Sound} 枚举名称；
     * 为空表示采用桥接默认值（UI 按钮点击声）。
     */
    public Optional<String> clickSound() {
        return Optional.ofNullable(clickSound);
    }

    /** 返回使用给定点击音效枚举名称的本条目副本。 */
    public MenuEntry withClickSound(String soundName) {
        Objects.requireNonNull(soundName, "soundName");
        return new MenuEntry(item, action, soundName);
    }
}
