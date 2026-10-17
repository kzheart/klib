package me.kzheart.klib.script.kether.core

/** Malformed source at a stable UTF-16 cursor position. */
class KetherLexException private constructor(message: String, @get:JvmName("positionProperty") @get:JvmSynthetic val position: Int) : IllegalArgumentException("$message at character $position") {
    fun getPosition(): Int = position
    companion object {
        private const val serialVersionUID: Long = 1L
        @JvmStatic fun endOfInput(position: Int): KetherLexException = KetherLexException("Unexpected end of Kether input", position)
        @JvmStatic fun stringNotClosed(quotes: Int, position: Int): KetherLexException = KetherLexException("Kether string opened with $quotes quote(s) is not closed", position)
        @JvmStatic fun notMatching(expected: String?, actual: String?, position: Int): KetherLexException = KetherLexException("Expected '$expected' but got '$actual'", position)
    }
}
