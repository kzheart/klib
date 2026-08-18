package me.kzheart.klib.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 字符映射、显式槽位和 YAML 模板共用的不可变输出。 */
public final class MenuModel {
    private final String title;
    private final int rows;
    private final boolean cancelClicks;
    private final Map<Integer, MenuEntry> entries;

    MenuModel(String title, int rows, boolean cancelClicks, Map<Integer, MenuEntry> entries) {
        this.title = Objects.requireNonNull(title, "title");
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("rows must be between 1 and 6");
        }
        this.rows = rows;
        this.cancelClicks = cancelClicks;
        int size = rows * 9;
        Map<Integer, MenuEntry> copy = new LinkedHashMap<Integer, MenuEntry>();
        for (Map.Entry<Integer, MenuEntry> entry : entries.entrySet()) {
            int slot = entry.getKey().intValue();
            if (slot < 0 || slot >= size) {
                throw new IllegalArgumentException("slot is outside menu: " + slot);
            }
            copy.put(Integer.valueOf(slot), Objects.requireNonNull(entry.getValue(), "entry"));
        }
        this.entries = Collections.unmodifiableMap(copy);
    }

    public String title() {
        return title;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    public boolean cancelClicks() {
        return cancelClicks;
    }

    public Map<Integer, MenuEntry> entries() {
        return entries;
    }

    public Optional<MenuEntry> entry(int slot) {
        return Optional.ofNullable(entries.get(Integer.valueOf(slot)));
    }

    public boolean shouldCancel(boolean clickedTopInventory) {
        return cancelClicks && clickedTopInventory;
    }

    /**
     * 点击的完整取消语义。顶部物品栏点击始终取消；当底部物品栏点击仍可能将物品移入或移出
     * 顶部物品栏（Shift 点击移动和双击聚集）时也会取消。投放区白名单由桥接层应用。
     */
    public boolean shouldCancel(MenuClickType type, boolean clickedTopInventory) {
        Objects.requireNonNull(type, "type");
        if (!cancelClicks) {
            return false;
        }
        if (clickedTopInventory) {
            return true;
        }
        return type.shift() || type == MenuClickType.DOUBLE_CLICK;
    }

    /** 取消原始槽位触及顶部物品栏的任何拖拽。 */
    public boolean shouldCancelDrag(Set<Integer> rawSlots, int topSize) {
        Objects.requireNonNull(rawSlots, "rawSlots");
        if (!cancelClicks) {
            return false;
        }
        for (Integer slot : rawSlots) {
            if (slot != null && slot.intValue() >= 0 && slot.intValue() < topSize) {
                return true;
            }
        }
        return false;
    }
}
