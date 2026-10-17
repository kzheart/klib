package me.kzheart.klib.lang

/** 由库定义的悬停或点击表达式。 */
class TextAction(private val type: Type, private val value: String) {
    enum class Type {
        HOVER_TEXT, HOVER_ITEM, HOVER_ENTITY, RUN_COMMAND, SUGGEST_COMMAND,
        OPEN_URL, OPEN_FILE, CHANGE_PAGE, COPY_TO_CLIPBOARD
    }
    fun type(): Type = type
    fun value(): String = value
}
