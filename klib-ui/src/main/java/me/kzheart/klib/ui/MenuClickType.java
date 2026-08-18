package me.kzheart.klib.ui;

/** 库级点击类型；Bukkit 桥接将 {@code ClickType} 映射到这些类型。 */
public enum MenuClickType {
    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    MIDDLE,
    DOUBLE_CLICK,
    NUMBER_KEY,
    DROP,
    CONTROL_DROP,
    UNKNOWN;

    public boolean shift() {
        return this == SHIFT_LEFT || this == SHIFT_RIGHT;
    }
}
