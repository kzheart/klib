/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.Locale

/** Kether 加载失败的稳定分类。 */
enum class LoadError {
    STRING_NOT_CLOSE, NOT_MATCH, UNKNOWN_ACTION, NOT_DURATION, EOF, BLOCK_ERROR, UNHANDLED;

    fun create(vararg arguments: Any?): LocalizedException =
        LocalizedException.of(this, "load-error." + name.lowercase(Locale.ROOT).replace('_', '-'), *arguments)
}
