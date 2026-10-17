/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.Optional
import java.util.function.Function

/** 将词元转换为请求的 Java 类型。 */
class CoerceType<T> internal constructor(
    private val function: Function<Any?, Optional<T>>,
    private val type: String
) : ArgType<T> {
    @Throws(LocalizedException::class)
    override fun read(reader: QuestReader?): T {
        val token = reader!!.nextToken()
        return function.apply(token).orElseThrow(LocalizedException.supply("not_$type", token))
    }
}
