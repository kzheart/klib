package me.kzheart.klib.lang

import java.util.Collections

/** 管线生成、不依赖 Adventure 的富消息。 */
class RichText(segments: List<RichTextSegment>) {
    private val segments = Collections.unmodifiableList(ArrayList(segments))
    fun segments(): List<RichTextSegment> = segments
    fun plainText(): String = segments.joinToString("") { it.text() }

    fun legacyText(): String = buildString {
        var previous: RichTextSegment? = null
        for (segment in segments) {
            val changed = previous?.let { !sameStyle(it, segment) } ?: true
            if (previous != null && changed) append("§r")
            if (changed) appendStyle(this, segment)
            append(segment.text())
            previous = segment
        }
    }

    companion object {
        @JvmStatic fun plain(text: String): RichText = RichText(listOf(RichTextSegment.plain(text)))
        private fun appendStyle(output: StringBuilder, segment: RichTextSegment) {
            segment.color()?.let { output.append('§').append(it.legacyCode()) }
            if (segment.bold()) output.append("§l")
            if (segment.italic()) output.append("§o")
            if (segment.underlined()) output.append("§n")
            if (segment.strikethrough()) output.append("§m")
            if (segment.obfuscated()) output.append("§k")
        }
        private fun sameStyle(left: RichTextSegment, right: RichTextSegment): Boolean =
            left.color() == right.color() && left.bold() == right.bold() &&
                left.italic() == right.italic() && left.underlined() == right.underlined() &&
                left.strikethrough() == right.strikethrough() && left.obfuscated() == right.obfuscated()
    }
}
