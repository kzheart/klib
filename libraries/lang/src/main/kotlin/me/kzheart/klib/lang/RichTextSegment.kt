package me.kzheart.klib.lang

/** 不可变片段值与样式复制；Java 声明层保留旧调用方的平台返回类型。 */
internal data class RichTextSegmentValue(
    val text: String,
    val color: MessageColor?,
    val bold: Boolean,
    val italic: Boolean,
    val underlined: Boolean,
    val strikethrough: Boolean,
    val obfuscated: Boolean,
    val hover: TextAction?,
    val click: TextAction?
) {
    fun withText(value: String): RichTextSegmentValue = copy(text = value)
}
