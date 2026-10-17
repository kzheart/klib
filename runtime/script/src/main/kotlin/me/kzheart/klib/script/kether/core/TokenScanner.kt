/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.function.IntUnaryOperator

/** 同一词法实现服务于独立词元读取器和动作解析器；错误类型由调用边界选择。 */
internal class TokenScanner @JvmOverloads constructor(
    private val content: CharArray, var index: Int, private val advance: IntUnaryOperator? = null
) {
    private fun skip() { index = advance?.applyAsInt(1) ?: (index + 1) }
    fun quoted(localized: Boolean): TokenBlock {
        val start = index
        return if (content[index] == '\'') {
            skip()
            val begin = index
            while (index < content.size && content[index] != '\'') skip()
            if (index >= content.size) throw notClosed(localized, 1, start)
            TokenBlock(String(content, begin, index - begin), true).also { skip() }
        } else {
            var count = 0
            while (index < content.size && content[index] == '"') { count++; skip() }
            var met = 0
            var cursor = index
            while (cursor < content.size) {
                if (content[cursor] == '"') met++ else if (met >= count) break else met = 0
                cursor++
            }
            if (met < count) throw notClosed(localized, count, start)
            TokenBlock(String(content, index, cursor - count - index), true).also { index = cursor }
        }
    }

    fun actionBlock(): TokenBlock {
        val start = index
        skip()
        val begin = index
        var depth = 1
        var quote = '\u0000'
        while (index < content.size && depth > 0) {
            val current = content[index]
            when {
                quote != '\u0000' -> if (current == quote) quote = '\u0000'
                current == '\'' || current == '"' -> quote = current
                current == '{' -> depth++
                current == '}' -> depth--
            }
            if (depth > 0) skip()
        }
        if (depth != 0) throw KetherLexException.notMatching("}", "end of input", start)
        return TokenBlock(String(content, begin, index - begin), true, true).also { skip() }
    }

    private fun notClosed(localized: Boolean, count: Int, position: Int): RuntimeException =
        if (localized) LoadError.STRING_NOT_CLOSE.create(count) else KetherLexException.stringNotClosed(count, position)

    companion object {
        @JvmStatic fun skipBlank(content: CharArray, position: Int): Int {
            var cursor = position
            while (cursor < content.size) {
                when {
                    Character.isWhitespace(content[cursor]) -> cursor++
                    cursor + 1 < content.size && content[cursor] == '/' && content[cursor + 1] == '/' -> {
                        while (cursor < content.size && content[cursor] != '\n' && content[cursor] != '\r') cursor++
                    }
                    else -> break
                }
            }
            return cursor
        }
        @JvmStatic fun tokenEnd(content: CharArray, position: Int): Int {
            var cursor = position
            while (cursor < content.size && !Character.isWhitespace(content[cursor])) cursor++
            return cursor
        }
        @JvmStatic fun hasLineBreak(content: CharArray, position: Int): Boolean {
            var cursor = position
            while (cursor < content.size) {
                val current = content[cursor]
                if (current == '\n' || current == '\r') return true
                if (Character.isWhitespace(current)) { cursor++; continue }
                return current == '/' && cursor + 1 < content.size && content[cursor + 1] == '/'
            }
            return false
        }
    }
}
