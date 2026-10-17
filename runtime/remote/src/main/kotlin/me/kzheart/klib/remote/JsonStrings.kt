package me.kzheart.klib.remote

/** JSON 字符串转义与反转义的共同边界。 */
class JsonStrings private constructor() {
    companion object {
        private val HEX = "0123456789abcdef"
        @JvmStatic fun appendQuoted(output: StringBuilder, value: String) {
            output.append('"')
            for (character in value) {
                val escaped = escape(character)
                if (escaped != null) output.append(escaped)
                else if (character < '\u0020') output.append("\\u00").append(HEX[character.code ushr 4]).append(HEX[character.code and 15])
                else output.append(character)
            }
            output.append('"')
        }
        internal fun escape(character: Char): String? = when (character) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\b' -> "\\b"
            '\u000c' -> "\\f"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> null
        }
        @JvmStatic @Throws(RemoteProtocolException::class)
        fun unescape(value: String): String {
            val result = StringBuilder()
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character != '\\') { result.append(character); continue }
                if (index >= value.length) throw RemoteProtocolException("Invalid JSON string escape")
                result.append(when (val escaped = value[index++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        if (index + 4 > value.length) throw RemoteProtocolException("Truncated JSON unicode escape")
                        var code = 0
                        repeat(4) {
                            val digit = Character.digit(value[index++], 16)
                            if (digit < 0) throw RemoteProtocolException("Invalid JSON unicode escape")
                            code = (code shl 4) or digit
                        }
                        code.toChar()
                    }
                    else -> throw RemoteProtocolException("Unsupported JSON escape in collector response")
                })
            }
            return result.toString()
        }
    }
}
