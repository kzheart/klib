package me.kzheart.klib.ui;

import org.bukkit.event.inventory.ClickType;

import java.util.Set;

/** 与监听器副作用分离的纯 Bukkit 到模型事件决策。 */
final class MenuEventPolicy {
    private MenuEventPolicy() {
    }

    static MenuClickType clickType(ClickType type) {
        if (type == null) {
            return MenuClickType.UNKNOWN;
        }
        switch (type) {
            case LEFT:
                return MenuClickType.LEFT;
            case RIGHT:
                return MenuClickType.RIGHT;
            case SHIFT_LEFT:
                return MenuClickType.SHIFT_LEFT;
            case SHIFT_RIGHT:
                return MenuClickType.SHIFT_RIGHT;
            case MIDDLE:
                return MenuClickType.MIDDLE;
            case DOUBLE_CLICK:
                return MenuClickType.DOUBLE_CLICK;
            case NUMBER_KEY:
                return MenuClickType.NUMBER_KEY;
            case DROP:
                return MenuClickType.DROP;
            case CONTROL_DROP:
                return MenuClickType.CONTROL_DROP;
            default:
                return MenuClickType.UNKNOWN;
        }
    }

    static boolean isTopSlot(int rawSlot, int topSize) {
        return rawSlot >= 0 && rawSlot < topSize;
    }

    static boolean cancelClick(MenuModel model, ClickType click, int rawSlot, int topSize) {
        return model.shouldCancel(clickType(click), isTopSlot(rawSlot, topSize));
    }

    static boolean cancelClick(
            MenuModel model,
            ClickType click,
            int rawSlot,
            int topSize,
            boolean dropZoneSlot,
            boolean hasDropZones
    ) {
        MenuClickType type = clickType(click);
        boolean top = isTopSlot(rawSlot, topSize);
        return model.shouldCancel(type, top)
                || dropZoneSlot
                || hasDropZones && (!top && type.shift() || type == MenuClickType.DOUBLE_CLICK);
    }

    static boolean cancelDrag(MenuModel model, Set<Integer> rawSlots, int topSize) {
        return model.shouldCancelDrag(rawSlots, topSize);
    }
}
