package me.kzheart.klib.ui

/** 库级点击类型。 */
enum class MenuClickType {
    LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, MIDDLE, DOUBLE_CLICK, NUMBER_KEY, DROP, CONTROL_DROP, UNKNOWN;
    fun shift(): Boolean = this == SHIFT_LEFT || this == SHIFT_RIGHT
}
