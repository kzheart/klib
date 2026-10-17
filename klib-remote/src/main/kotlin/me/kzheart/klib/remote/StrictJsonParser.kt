package me.kzheart.klib.remote

/** 受深度与节点预算限制的响应解析器；拒绝重复字段、浮点数及尾随内容。 */
class StrictJsonParser private constructor(private val source: String) {
    private var index = 0
    private var nodes = 0
    private fun value(depth: Int): Any? {
        whitespace()
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES || index >= source.length) invalid()
        return when (source[index]) {
            '{' -> obj(depth + 1)
            '[' -> array(depth + 1)
            '"' -> string()
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            'n' -> { literal("null"); null }
            else -> integer()
        }
    }
    private fun obj(depth: Int): Map<String, Any?> {
        index++
        val result = LinkedHashMap<String, Any?>()
        whitespace()
        if (consume('}')) return result
        while (true) {
            whitespace()
            if (index >= source.length || source[index] != '"') invalid()
            val key = string()
            whitespace()
            expect(':')
            val value = value(depth)
            if (result.containsKey(key)) invalid()
            result[key] = value
            whitespace()
            if (consume('}')) return result
            expect(',')
        }
    }
    private fun array(depth: Int): List<Any?> {
        index++
        val result = ArrayList<Any?>()
        whitespace()
        if (consume(']')) return result
        while (true) {
            result.add(value(depth))
            whitespace()
            if (consume(']')) return result
            expect(',')
        }
    }
    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val current = source[index++]
            if (current == '"') return result.toString()
            if (current == '\\') {
                if (index >= source.length) invalid()
                result.append(when (val escaped = source[index++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> unicode()
                    else -> invalid()
                })
            } else {
                if (current < '\u0020') invalid()
                result.append(current)
            }
        }
        invalid()
    }
    private fun unicode(): Char {
        if (index > source.length - 4) invalid()
        var value = 0
        repeat(4) {
            val digit = Character.digit(source[index++], 16)
            if (digit < 0) invalid()
            value = value * 16 + digit
        }
        return value.toChar()
    }
    private fun integer(): Long {
        val start = index
        if (consume('-') && index >= source.length) invalid()
        if (consume('0')) {
            if (index < source.length && Character.isDigit(source[index])) invalid()
        } else {
            if (index >= source.length || source[index] !in '1'..'9') invalid()
            while (index < source.length && Character.isDigit(source[index])) index++
        }
        if (index < source.length && source[index] in ".eE") invalid()
        return source.substring(start, index).toLongOrNull() ?: invalid()
    }
    private fun literal(expected: String) {
        if (!source.regionMatches(index, expected, 0, expected.length)) invalid()
        index += expected.length
    }
    private fun whitespace() { while (index < source.length && source[index] in " \n\r\t") index++ }
    private fun consume(expected: Char): Boolean = if (index < source.length && source[index] == expected) { index++; true } else false
    private fun expect(expected: Char) { if (!consume(expected)) invalid() }
    private fun invalid(): Nothing = throw IllegalArgumentException("invalid JSON")
    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_NODES = 8192
        @JvmStatic fun parse(source: String?): Any? {
            require(source != null) { "JSON must not be null" }
            val parser = StrictJsonParser(source)
            val value = parser.value(0)
            parser.whitespace()
            if (parser.index != source.length) parser.invalid()
            return value
        }
    }
}
