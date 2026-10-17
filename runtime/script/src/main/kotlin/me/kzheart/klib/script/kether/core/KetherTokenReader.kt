/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.Objects

/** 将所有 Kether 解析器共用的词法子集转换为词元。 */
class KetherTokenReader(source: String?) : AbstractStringReader(Objects.requireNonNull(source, "source")!!.toCharArray()) {
    override fun nextToken(): String = nextTokenBlock().token

    fun nextTokenBlock(): TokenBlock {
        skipBlank()
        if (index >= content.size) throw KetherLexException.endOfInput(index)
        if (peek() != '{' && peek() != '"' && peek() != '\'') return TokenBlock(super.nextToken(), false)
        val scanner = TokenScanner(content, index)
        return try {
            if (peek() == '{') scanner.actionBlock() else scanner.quoted(false)
        } finally { index = scanner.index }
    }
}
