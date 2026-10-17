package me.kzheart.klib.lang

import java.util.Locale

/** 带旧版具名常量并能在 1.12 上确定性降级的 RGB 颜色。 */
class MessageColor private constructor(private val name: String, private val rgb: Int, private val code: Char?) {
    fun rgb(): Int = rgb
    fun isLegacy(): Boolean = code != null
    fun legacyCode(): Char = nearestLegacy().code!!
    fun nearestLegacy(): MessageColor = LEGACY.minBy { color ->
        val dr = ((rgb shr 16 and 255) - (color.rgb shr 16 and 255)).toLong()
        val dg = ((rgb shr 8 and 255) - (color.rgb shr 8 and 255)).toLong()
        val db = ((rgb and 255) - (color.rgb and 255)).toLong()
        dr * dr + dg * dg + db * db
    }
    override fun equals(other: Any?): Boolean = other is MessageColor && rgb == other.rgb
    override fun hashCode(): Int = rgb
    override fun toString(): String = name

    companion object {
        @JvmField val BLACK = MessageColor("black", 0x000000, '0')
        @JvmField val DARK_BLUE = MessageColor("dark_blue", 0x0000AA, '1')
        @JvmField val DARK_GREEN = MessageColor("dark_green", 0x00AA00, '2')
        @JvmField val DARK_AQUA = MessageColor("dark_aqua", 0x00AAAA, '3')
        @JvmField val DARK_RED = MessageColor("dark_red", 0xAA0000, '4')
        @JvmField val DARK_PURPLE = MessageColor("dark_purple", 0xAA00AA, '5')
        @JvmField val GOLD = MessageColor("gold", 0xFFAA00, '6')
        @JvmField val GRAY = MessageColor("gray", 0xAAAAAA, '7')
        @JvmField val DARK_GRAY = MessageColor("dark_gray", 0x555555, '8')
        @JvmField val BLUE = MessageColor("blue", 0x5555FF, '9')
        @JvmField val GREEN = MessageColor("green", 0x55FF55, 'a')
        @JvmField val AQUA = MessageColor("aqua", 0x55FFFF, 'b')
        @JvmField val RED = MessageColor("red", 0xFF5555, 'c')
        @JvmField val LIGHT_PURPLE = MessageColor("light_purple", 0xFF55FF, 'd')
        @JvmField val YELLOW = MessageColor("yellow", 0xFFFF55, 'e')
        @JvmField val WHITE = MessageColor("white", 0xFFFFFF, 'f')
        private val LEGACY = listOf(BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE,
            GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE)
        @JvmStatic fun rgb(rgb: Int): MessageColor {
            require(rgb in 0..0xFFFFFF) { "rgb must be between 0x000000 and 0xFFFFFF" }
            return MessageColor(String.format(Locale.ROOT, "#%06X", rgb), rgb, null)
        }
        @JvmStatic fun fromLegacy(code: Char): MessageColor? = LEGACY.firstOrNull { it.code == code.lowercaseChar() }
        @JvmStatic fun fromTag(tag: String): MessageColor? =
            if (tag.matches(Regex("#[0-9a-fA-F]{6}"))) rgb(tag.substring(1).toInt(16))
            else LEGACY.firstOrNull { it.name == tag.lowercase(Locale.ROOT).replace('-', '_') }
    }
}
