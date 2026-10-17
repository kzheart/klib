/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.time.Duration
import java.util.Locale
import java.util.Optional

/** 统一词元转换、时长规范化与参数列表读取。 */
internal object ArgumentReaders {
    @JvmStatic fun integer(value: Any?): Optional<Int> = Optional.ofNullable(value.toString().toIntOrNull())
    @JvmStatic fun longValue(value: Any?): Optional<Long> = Optional.ofNullable(value.toString().toLongOrNull())
    @JvmStatic fun doubleValue(value: Any?): Optional<Double> = Optional.ofNullable(value.toString().toDoubleOrNull())
    @JvmStatic fun booleanValue(value: Any?): Optional<Boolean> = Optional.ofNullable(
        when (value.toString().lowercase(Locale.ENGLISH)) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        })

    @Throws(LocalizedException::class)
    @JvmStatic fun duration(reader: QuestReader): Duration {
        var value = reader.nextToken().uppercase(Locale.ENGLISH)
        if ('T' !in value) {
            value = when {
                'D' in value -> if (value.any { it == 'H' || it == 'M' || it == 'S' }) value.replace("D", "DT") else value
                value.startsWith('P') -> "PT" + value.substring(1)
                else -> "T$value"
            }
        }
        if (!value.startsWith('P')) value = "P$value"
        return try { Duration.parse(value) } catch (_: RuntimeException) { throw LoadError.NOT_DURATION.create(value) }
    }

    @JvmStatic @Throws(LocalizedException::class)
    fun <T> list(reader: QuestReader, elementType: ArgType<T>): List<T> {
        reader.expect("[")
        val result = ArrayList<T>()
        while (reader.hasNext() && reader.peek() != ']') result.add(reader.next(elementType))
        result.trimToSize()
        reader.expect("]")
        return result
    }
}
