package me.kzheart.klib.ui.chat

import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment
import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.TextAction

/** 保守字宽估计；按码点折行并完整保留样式与动作。 */
internal object ChatPanelLayout {
    @JvmStatic fun width(segments: List<RichTextSegment>): Int = segments.sumOf { segment ->
        var width = 0
        val value = segment.text()
        var index = 0
        while (index < value.length) {
            val cp = value.codePointAt(index); index += Character.charCount(cp)
            width += advance(cp, segment.bold())
        }
        width
    }
    private fun advance(cp: Int, bold: Boolean): Int {
        val size = when {
            cp == ' '.code -> 4
            "i!.,:;|'".indexOf(cp.toChar()) >= 0 && cp < 128 -> 2
            "ltI[]()".indexOf(cp.toChar()) >= 0 && cp < 128 -> 4
            cp < 128 -> 6
            else -> 9
        }
        return size + if (bold && cp != ' '.code) 1 else 0
    }
    @JvmStatic fun wrap(text: RichText, limit: Int): List<RichText> {
        val rows = ArrayList<RichText>()
        var row = ArrayList<RichTextSegment>()
        var width = 0
        for (segment in text.segments()) {
            val value = segment.text()
            val part = StringBuilder()
            var index = 0
            while (index < value.length) {
                val cp = value.codePointAt(index); index += Character.charCount(cp)
                val size = advance(cp, segment.bold())
                if (cp == '\n'.code || width + size > limit && width > 0) {
                    flush(row, part, segment)
                    rows.add(RichText(row)); row = ArrayList(); width = 0
                    if (cp == '\n'.code) continue
                }
                part.appendCodePoint(cp); width += size
            }
            flush(row, part, segment)
        }
        rows.add(RichText(row))
        return rows
    }
    @JvmStatic fun limited(text: RichText, width: Int, count: Int): List<RichText> {
        val rows = wrap(text, width)
        if (rows.size <= count) return rows
        return ArrayList(rows.subList(0, count)).apply {
            this[count - 1] = RichText(listOf(RichTextSegment("… 完整内容见悬停", MessageColor.GRAY, false,
                TextAction(TextAction.Type.HOVER_TEXT, text.legacyText()), null)))
        }
    }
    private fun flush(row: MutableList<RichTextSegment>, part: StringBuilder, source: RichTextSegment) {
        if (part.isEmpty()) return
        row.add(RichTextSegment(part.toString(), source.color(), source.bold(), source.italic(), source.underlined(),
            source.strikethrough(), source.obfuscated(), source.hover(), source.click())); part.setLength(0)
    }
}
