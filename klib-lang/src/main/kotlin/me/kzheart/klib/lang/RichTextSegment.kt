package me.kzheart.klib.lang

/** 不可变的已渲染文本片段。可选样式和事件在类型中明确表达。 */
class RichTextSegment(
    private val text: String,
    private val color: MessageColor?,
    private val bold: Boolean,
    private val italic: Boolean,
    private val underlined: Boolean,
    private val strikethrough: Boolean,
    private val obfuscated: Boolean,
    private val hover: TextAction?,
    private val click: TextAction?
) {
    constructor(text: String, color: MessageColor?, bold: Boolean, hover: TextAction?, click: TextAction?) :
        this(text, color, bold, false, false, false, false, hover, click)

    fun text(): String = text
    fun color(): MessageColor? = color
    fun bold(): Boolean = bold
    fun italic(): Boolean = italic
    fun underlined(): Boolean = underlined
    fun strikethrough(): Boolean = strikethrough
    fun obfuscated(): Boolean = obfuscated
    fun hover(): TextAction? = hover
    fun click(): TextAction? = click
    fun withText(value: String): RichTextSegment =
        RichTextSegment(value, color, bold, italic, underlined, strikethrough, obfuscated, hover, click)

    companion object {
        @JvmStatic fun plain(text: String): RichTextSegment = RichTextSegment(text, null, false, null, null)
    }
}
