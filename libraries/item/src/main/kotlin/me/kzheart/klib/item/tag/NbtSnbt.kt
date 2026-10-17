package me.kzheart.klib.item.tag

/** 传统 SNBT 语法；不依赖服务端版本专属的文本扩展。 */
internal object NbtSnbt {
    fun read(text: String): NbtTag = Reader(text).read()

    fun write(tag: NbtTag): String {
        val out = NbtTextOutput()
        writeValue(out, tag, 0)
        return out.toString()
    }

    private fun writeValue(out: NbtTextOutput, tag: NbtTag, depth: Int) {
        out.enter(depth)
        when (tag.type) {
            NbtType.COMPOUND -> {
                out.append('{')
                (tag as NbtCompound).entries().entries.forEachIndexed { index, (key, child) ->
                    if (index != 0) out.append(',')
                    out.quoted(key, false)
                    out.append(':')
                    writeValue(out, child, depth + 1)
                }
                out.append('}')
            }
            NbtType.LIST -> {
                out.append('[')
                (tag as NbtList).forEachIndexed { index, child ->
                    if (index != 0) out.append(',')
                    writeValue(out, child, depth + 1)
                }
                out.append(']')
            }
            NbtType.STRING -> out.quoted(tag.value() as String, false)
            NbtType.BYTE_ARRAY -> {
                val values = tag.value() as ByteArray
                out.elements(values.size)
                out.append("[B;")
                values.forEachIndexed { index, value ->
                    if (index != 0) out.append(',')
                    out.append(value.toString()); out.append('b')
                }
                out.append(']')
            }
            NbtType.INT_ARRAY -> {
                val values = tag.value() as IntArray
                out.elements(values.size)
                out.append("[I;")
                values.forEachIndexed { index, value ->
                    if (index != 0) out.append(',')
                    out.append(value.toString())
                }
                out.append(']')
            }
            NbtType.LONG_ARRAY -> {
                val values = tag.value() as LongArray
                out.elements(values.size)
                out.append("[L;")
                values.forEachIndexed { index, value ->
                    if (index != 0) out.append(',')
                    out.append(value.toString()); out.append('L')
                }
                out.append(']')
            }
            else -> {
                val number = tag.value() as Number
                require(number !is Float || number.isFinite()) { "Non-finite floats cannot be written as SNBT" }
                require(number !is Double || number.isFinite()) { "Non-finite doubles cannot be written as SNBT" }
                out.append(number.toString())
                if (tag.type != NbtType.INT) out.append(NbtTextNumbers.suffix(tag.type))
            }
        }
    }

    private class Reader(text: String) : NbtTextReader(text, false) {
        override fun value(depth: Int): NbtTag {
            enter(depth)
            whitespace()
            return when (peek()) {
                '{' -> compound(depth)
                '[' -> arrayOrList(depth)
                '"', '\'' -> NbtTag.string(quoted())
                else -> NbtTextNumbers.snbt(token())
            }
        }

        private fun arrayOrList(depth: Int): NbtTag {
            val begin = position
            expect('[')
            whitespace()
            val type = when (peek()) {
                'B' -> NbtType.BYTE
                'I' -> NbtType.INT
                'L' -> NbtType.LONG
                else -> null
            }
            if (type != null) {
                position++
                whitespace()
                if (take(';')) {
                    val values = NbtTextArray()
                    whitespace()
                    if (take(']')) return values.tag(type)
                    while (true) {
                        elements(1)
                        val element = NbtTextNumbers.snbt(token())
                        require(element.type == type) { "Expected SNBT array element $type but found ${element.type}" }
                        values.add((element.value() as Number).toLong())
                        whitespace()
                        if (take(']')) return values.tag(type)
                        expect(',')
                        whitespace()
                        if (take(']')) return values.tag(type)
                    }
                }
            }
            position = begin
            return list(depth)
        }
    }
}

/** 小型文本游标共用词法、预算和容器规则；两个格式各自决定标量语义。 */
internal abstract class NbtTextReader(private val source: String, private val json: Boolean) {
    protected var position = 0
    private val budget = NbtTextBudget()

    init { NbtTextUtf8.length(source) }

    fun read(): NbtTag {
        val result = value(0)
        whitespace()
        require(position == source.length) { "Trailing input at character $position" }
        return result
    }

    protected abstract fun value(depth: Int): NbtTag
    protected fun enter(depth: Int) = budget.enter(depth)
    protected fun elements(count: Int) = budget.elements(count)
    protected fun peek(): Char? = source.getOrNull(position)
    protected fun fail(message: String): Nothing = throw IllegalArgumentException("$message at character $position")
    protected fun take(char: Char): Boolean = if (peek() == char) { position++; true } else false
    protected fun expect(char: Char) { if (!take(char)) fail("Expected '$char'") }

    protected fun whitespace() {
        while (peek()?.let { if (json) it == ' ' || it == '\n' || it == '\r' || it == '\t' else Character.isWhitespace(it) } == true) position++
    }

    protected fun token(): String {
        whitespace()
        val start = position
        while (peek()?.let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '+' || it == '.' } == true) position++
        if (start == position) fail("Expected an unquoted SNBT token")
        return source.substring(start, position)
    }

    protected fun quoted(): String {
        val quote = peek()
        if (quote != '"' && (json || quote != '\'')) fail("Expected a quoted string")
        position++
        val result = StringBuilder()
        while (true) {
            val char = peek() ?: fail("Unterminated quoted string")
            position++
            when {
                char == quote -> return result.toString()
                char == '\\' -> {
                    val escape = peek() ?: fail("Unterminated string escape")
                    position++
                    if (!json) {
                        if (escape != quote && escape != '\\') fail("Invalid SNBT escape")
                        result.append(escape)
                    } else when (escape) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            var code = 0
                            repeat(4) {
                                val digit = peek()?.let { Character.digit(it, 16) } ?: -1
                                if (digit < 0 || peek()!! > '\u007F') fail("Invalid JSON Unicode escape")
                                code = code * 16 + digit
                                position++
                            }
                            result.append(code.toChar())
                        }
                        else -> fail("Invalid JSON escape")
                    }
                }
                json && char < ' ' -> fail("Unescaped JSON control character")
                else -> result.append(char)
            }
        }
    }

    protected fun compound(depth: Int): NbtCompound {
        expect('{')
        val result = NbtCompound()
        whitespace()
        if (take('}')) return result
        while (true) {
            val key = if (json || peek() == '"' || peek() == '\'') quoted() else token()
            require(!result.contains(key)) { "Duplicate NBT compound key: $key" }
            whitespace()
            expect(':')
            result[key] = value(depth + 1)
            whitespace()
            if (take('}')) return result
            expect(',')
            whitespace()
            if (!json && take('}')) return result
        }
    }

    protected fun list(depth: Int): NbtList {
        expect('[')
        val result = NbtList()
        whitespace()
        if (take(']')) return result
        while (true) {
            result.add(value(depth + 1))
            whitespace()
            if (take(']')) return result
            expect(',')
            whitespace()
            if (!json && take(']')) return result
        }
    }
}

internal class NbtTextBudget {
    private var count = 0

    fun enter(depth: Int) {
        require(depth <= MAX_DEPTH) { "NBT nesting exceeds $MAX_DEPTH" }
        elements(1)
    }

    fun elements(size: Int) {
        require(size >= 0 && count <= MAX_ELEMENTS - size) { "NBT text exceeds $MAX_ELEMENTS values and array elements" }
        count += size
    }

    companion object {
        const val MAX_DEPTH = 512
        const val MAX_ELEMENTS = 1024 * 1024
    }
}

/** 检查真实 UTF-8 长度；不先分配一个可能过大的编码后字节数组。 */
internal object NbtTextUtf8 {
    fun length(text: String): Int {
        require(text.length <= NbtBinary.MAX_BYTES) { "NBT text exceeds ${NbtBinary.MAX_BYTES} UTF-8 bytes" }
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes += when {
                char < '\u0080' -> 1
                char < '\u0800' -> 2
                Character.isHighSurrogate(char) -> {
                    require(index < text.length && Character.isLowSurrogate(text[index])) { "Unpaired UTF-16 surrogate in NBT text" }
                    index++
                    4
                }
                Character.isLowSurrogate(char) -> throw IllegalArgumentException("Unpaired UTF-16 surrogate in NBT text")
                else -> 3
            }
            require(bytes <= NbtBinary.MAX_BYTES) { "NBT text exceeds ${NbtBinary.MAX_BYTES} UTF-8 bytes" }
        }
        return bytes
    }
}

/** 按写入内容限制输出大小，避免 joinToString / 转义后再检查造成无限中间分配。 */
internal class NbtTextOutput {
    private val text = StringBuilder()
    private var bytes = 0
    private val budget = NbtTextBudget()

    fun enter(depth: Int) = budget.enter(depth)
    fun elements(count: Int) = budget.elements(count)

    fun append(value: String) {
        reserve(NbtTextUtf8.length(value))
        text.append(value)
    }

    fun append(value: Char) {
        require(!Character.isSurrogate(value)) { "Unpaired UTF-16 surrogate in NBT text" }
        reserve(if (value < '\u0080') 1 else if (value < '\u0800') 2 else 3)
        text.append(value)
    }

    fun quoted(value: String, json: Boolean, suffix: String = "") {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            when {
                char == '"' || char == '\\' -> { append('\\'); append(char) }
                json && (char < ' ' || Character.isSurrogate(char)) -> {
                    append("\\u")
                    append(HEX[(char.code ushr 12) and 15]); append(HEX[(char.code ushr 8) and 15])
                    append(HEX[(char.code ushr 4) and 15]); append(HEX[char.code and 15])
                }
                Character.isHighSurrogate(char) -> {
                    require(index < value.length && Character.isLowSurrogate(value[index])) { "Unpaired UTF-16 surrogate in SNBT string" }
                    reserve(4)
                    text.append(char).append(value[index++])
                }
                else -> append(char)
            }
        }
        append(suffix)
        append('"')
    }

    private fun reserve(size: Int) {
        require(size >= 0 && bytes <= NbtBinary.MAX_BYTES - size) { "NBT text exceeds ${NbtBinary.MAX_BYTES} UTF-8 bytes" }
        bytes += size
    }

    override fun toString(): String = text.toString()

    companion object { private const val HEX = "0123456789abcdef" }
}

/** 原始数值缓冲区按需增长；输入中不存在可操纵的预分配长度。 */
internal class NbtTextArray {
    private var values = LongArray(16)
    private var size = 0

    fun add(value: Long) {
        require(size < NbtTextBudget.MAX_ELEMENTS) { "Too many NBT array elements" }
        if (size == values.size) values = values.copyOf(minOf(values.size * 2, NbtTextBudget.MAX_ELEMENTS))
        values[size++] = value
    }

    fun tag(type: NbtType): NbtTag = when (type) {
        NbtType.BYTE -> NbtTag.bytes(ByteArray(size) { values[it].toByte() })
        NbtType.INT -> NbtTag.ints(IntArray(size) { values[it].toInt() })
        NbtType.LONG -> NbtTag.longs(values.copyOf(size))
        else -> throw IllegalArgumentException("Invalid NBT array type: $type")
    }
}

internal object NbtTextNumbers {
    private val integer = Regex("[+-]?[0-9]+")
    private val snbtInteger = Regex("[+-]?(?:0|[1-9][0-9]*)")
    private val snbtDouble = Regex("[+-]?(?:[0-9]+[.]|[0-9]*[.][0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val nonFinite = setOf("NaN", "Infinity", "+Infinity", "-Infinity")

    fun suffix(type: NbtType): Char = when (type) {
        NbtType.BYTE -> 'b'
        NbtType.SHORT -> 's'
        NbtType.INT -> 'i'
        NbtType.LONG -> 'l'
        NbtType.FLOAT -> 'f'
        NbtType.DOUBLE -> 'd'
        else -> throw IllegalArgumentException("Not a numeric NBT type: $type")
    }

    fun snbt(token: String): NbtTag {
        if (token.equals("true", true)) return NbtTag.byteValue(1)
        if (token.equals("false", true)) return NbtTag.byteValue(0)
        val body = token.dropLast(1)
        val type = when (token.last().lowercaseChar()) {
            'b' -> NbtType.BYTE
            's' -> NbtType.SHORT
            'l' -> NbtType.LONG
            'f' -> NbtType.FLOAT
            'd' -> NbtType.DOUBLE
            else -> null
        }
        if (type != null) {
            val floating = type == NbtType.FLOAT || type == NbtType.DOUBLE
            require(!floating || body !in nonFinite) { "Non-finite SNBT numeric literal: $token" }
            if ((if (floating) decimal else snbtInteger).matches(body)) return parse(body, type, false)
        }
        if (snbtInteger.matches(token)) return parse(token, NbtType.INT, false)
        if (snbtDouble.matches(token)) return parse(token, NbtType.DOUBLE, false)
        return NbtTag.string(token)
    }

    fun parse(text: String, type: NbtType, allowNonFinite: Boolean): NbtTag {
        require(text.length in 1..128) { "Invalid NBT numeric literal length" }
        val floating = type == NbtType.FLOAT || type == NbtType.DOUBLE
        val special = floating && allowNonFinite && text in nonFinite
        require(special || (if (floating) decimal else integer).matches(text)) { "Invalid NBT $type literal: $text" }
        try {
            return when (type) {
                NbtType.BYTE -> NbtTag.byteValue(text.toByte())
                NbtType.SHORT -> NbtTag.shortValue(text.toShort())
                NbtType.INT -> NbtTag.intValue(text.toInt())
                NbtType.LONG -> NbtTag.longValue(text.toLong())
                NbtType.FLOAT -> {
                    val value = text.toFloat()
                    require(special || value.isFinite()) { "NBT FLOAT overflow: $text" }
                    require(special || value != 0.0f || !nonzeroSignificand(text)) { "NBT FLOAT underflow: $text" }
                    NbtTag.floatValue(value)
                }
                NbtType.DOUBLE -> {
                    val value = text.toDouble()
                    require(special || value.isFinite()) { "NBT DOUBLE overflow: $text" }
                    require(special || value != 0.0 || !nonzeroSignificand(text)) { "NBT DOUBLE underflow: $text" }
                    NbtTag.doubleValue(value)
                }
                else -> throw IllegalArgumentException("Not a numeric NBT type: $type")
            }
        } catch (failure: NumberFormatException) {
            throw IllegalArgumentException("Invalid or out-of-range NBT $type literal: $text", failure)
        }
    }

    private fun nonzeroSignificand(text: String): Boolean {
        for (char in text) {
            if (char == 'e' || char == 'E') break
            if (char in '1'..'9') return true
        }
        return false
    }
}
