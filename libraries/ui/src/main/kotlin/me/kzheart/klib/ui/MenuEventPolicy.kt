package me.kzheart.klib.ui

import org.bukkit.event.inventory.ClickType

/** 与监听器副作用分离的纯 Bukkit 到模型事件决策。 */
internal object MenuEventPolicy {
    @JvmStatic fun clickType(type: ClickType?): MenuClickType = when (type) {
        ClickType.LEFT -> MenuClickType.LEFT
        ClickType.RIGHT -> MenuClickType.RIGHT
        ClickType.SHIFT_LEFT -> MenuClickType.SHIFT_LEFT
        ClickType.SHIFT_RIGHT -> MenuClickType.SHIFT_RIGHT
        ClickType.MIDDLE -> MenuClickType.MIDDLE
        ClickType.DOUBLE_CLICK -> MenuClickType.DOUBLE_CLICK
        ClickType.NUMBER_KEY -> MenuClickType.NUMBER_KEY
        ClickType.DROP -> MenuClickType.DROP
        ClickType.CONTROL_DROP -> MenuClickType.CONTROL_DROP
        else -> MenuClickType.UNKNOWN
    }
    @JvmStatic fun isTopSlot(rawSlot: Int, topSize: Int): Boolean = rawSlot >= 0 && rawSlot < topSize
    @JvmStatic fun cancelClick(model: MenuModel, click: ClickType?, rawSlot: Int, topSize: Int): Boolean =
        model.shouldCancel(clickType(click), isTopSlot(rawSlot, topSize))
    @JvmStatic fun cancelClick(model: MenuModel, click: ClickType?, rawSlot: Int, topSize: Int,
                              dropZoneSlot: Boolean, hasDropZones: Boolean): Boolean {
        val type = clickType(click)
        val top = isTopSlot(rawSlot, topSize)
        return model.shouldCancel(type, top) || dropZoneSlot || hasDropZones && (!top && type.shift() || type == MenuClickType.DOUBLE_CLICK)
    }
    @JvmStatic fun cancelDrag(model: MenuModel, rawSlots: Set<Int?>, topSize: Int): Boolean = model.shouldCancelDrag(rawSlots, topSize)
}
