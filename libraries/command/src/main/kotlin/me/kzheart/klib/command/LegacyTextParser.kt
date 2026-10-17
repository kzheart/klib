package me.kzheart.klib.command

import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment

internal class LegacyTextParser private constructor() {
    companion object {
        private val colors = listOf(MessageColor.BLACK, MessageColor.DARK_BLUE, MessageColor.DARK_GREEN, MessageColor.DARK_AQUA,
            MessageColor.DARK_RED, MessageColor.DARK_PURPLE, MessageColor.GOLD, MessageColor.GRAY, MessageColor.DARK_GRAY,
            MessageColor.BLUE, MessageColor.GREEN, MessageColor.AQUA, MessageColor.RED, MessageColor.LIGHT_PURPLE,
            MessageColor.YELLOW, MessageColor.WHITE)
        @JvmStatic fun parse(source: String): RichText {
            val segments = arrayListOf<RichTextSegment>()
            val text = StringBuilder()
            var color: MessageColor? = null
            val styles = mutableSetOf<Char>()
            fun flush() {
                segments.add(RichTextSegment(text.toString(), color, 'l' in styles, 'o' in styles, 'n' in styles,
                    'm' in styles, 'k' in styles, null, null))
                text.setLength(0)
            }
            var index = 0
            while (index < source.length) {
                val current = source[index]
                if (current == '§' && index + 1 < source.length) {
                    val code = source[index + 1].lowercaseChar()
                    val mapped = colors.getOrNull("0123456789abcdef".indexOf(code))
                    if (mapped != null || code in "lonmkr") {
                        if (text.isNotEmpty()) flush()
                        if (mapped != null || code == 'r') { color = mapped; styles.clear() } else styles.add(code)
                        index += 2
                        continue
                    }
                }
                text.append(current)
                index++
            }
            if (text.isNotEmpty() || segments.isEmpty()) flush()
            return RichText(segments)
        }
    }
}
