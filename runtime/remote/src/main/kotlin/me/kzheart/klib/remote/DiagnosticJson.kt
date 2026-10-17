package me.kzheart.klib.remote

/** 统一结构遍历；有界写入器在追加前核算 UTF-8，超限整体拒绝。 */
class DiagnosticJson private constructor() {
    private class Writer(private val limit: Int?) {
        private val output = StringBuilder()
        private var bytes = 0
        fun result(): String = output.toString()
        fun append(value: Any?) {
            when (value) {
                null -> raw("null")
                is Double -> raw(if (value.isFinite()) value.toString() else "null")
                is Float -> raw(if (value.isFinite()) value.toString() else "null")
                is Boolean, is Number -> raw(value.toString())
                is Map<*, *> -> {
                    ascii('{')
                    value.entries.forEachIndexed { index, entry ->
                        if (index > 0) ascii(',')
                        quoted(entry.key.toString()); ascii(':'); append(entry.value)
                    }
                    ascii('}')
                }
                is Iterable<*> -> {
                    ascii('[')
                    value.forEachIndexed { index, entry -> if (index > 0) ascii(','); append(entry) }
                    ascii(']')
                }
                else -> quoted(value.toString())
            }
        }
        private fun quoted(value: String) {
            if (limit == null) { JsonStrings.appendQuoted(output, value); return }
            ascii('"')
            var index = 0
            while (index < value.length) {
                val current = value[index++]
                val escaped = JsonStrings.escape(current)
                when {
                    escaped != null -> raw(escaped)
                    current < '\u0020' -> {
                        ensure(6)
                        output.append("\\u00").append(HEX[current.code ushr 4]).append(HEX[current.code and 15])
                        bytes += 6
                    }
                    Character.isHighSurrogate(current) && index < value.length && Character.isLowSurrogate(value[index]) -> {
                        ensure(4); output.append(current).append(value[index++]); bytes += 4
                    }
                    else -> {
                        val size = when { current <= '\u007f' -> 1; current <= '\u07ff' -> 2; else -> 3 }
                        ensure(size); output.append(current); bytes += size
                    }
                }
            }
            ascii('"')
        }
        private fun raw(value: String) { value.forEach { ascii(it) } }
        private fun ascii(value: Char) { ensure(1); output.append(value); bytes++ }
        private fun ensure(additional: Int) {
            if (limit != null && bytes > limit - additional) throw IllegalArgumentException("JSON exceeds configured UTF-8 byte limit")
        }
    }
    companion object {
        private const val HEX = "0123456789abcdef"
        @JvmStatic fun write(value: Any?): String = Writer(null).apply { append(value) }.result()
        @JvmStatic fun write(value: Any?, maxUtf8Bytes: Int): String {
            require(maxUtf8Bytes > 0) { "maxUtf8Bytes must be positive" }
            return Writer(maxUtf8Bytes).apply { append(value) }.result()
        }
    }
}
