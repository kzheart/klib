package me.kzheart.klib.item.tag

import java.io.IOException

/**
 * 与服务端无关的 NBT 编解码。二进制沿用标准未压缩、命名根 compound 格式。
 *
 * 文本最多 16 MiB（UTF-8）、512 层（根为 0），总标签与数组元素最多 1,048,576 个。
 * JSON 是 Klib 的可逆类型后缀格式，不是 SNBT 或 Minecraft item codec JSON：
 * 数字为带 b/s/i/l/f/d 后缀的字符串，普通字符串带 t，数组带 b]/i]/l]；对象与列表正常嵌套。
 * 空数组及字面键原样保留，非法数字、重复键、混型列表和尾随输入明确失败。
 * 非法二进制数据或超长 modified-UTF 字符串以 IllegalArgumentException 报告并保留 I/O cause，Java 无需捕获隐藏的 checked exception。
 * 数字字面量最多 128 字符；浮点溢出或非零值下溢为零明确失败，有限数正常采用 IEEE 754 舍入。
 */
object NbtCodec {
    /** 唯一二进制实现的公开入口；不压缩，根名称写为空字符串。 */
    @JvmStatic fun toBytes(root: NbtCompound): ByteArray = try {
        NbtBinary.encode(root)
    } catch (error: IOException) {
        throw IllegalArgumentException("Cannot encode binary NBT", error)
    }

    /** 读取标准未压缩命名根 compound；根名称不进入值树。 */
    @JvmStatic fun fromBytes(bytes: ByteArray): NbtCompound = try {
        NbtBinary.decode(bytes)
    } catch (error: IOException) {
        throw IllegalArgumentException("Malformed binary NBT", error)
    }

    /** 标准 SNBT 的 12 种类型；拒绝非有限浮点数及孤立 UTF-16 surrogate。 */
    @JvmStatic fun toSnbt(tag: NbtTag): String = NbtSnbt.write(tag)

    /** 单双引号只转义对应引号及反斜线；引号内的控制字符原样保留。 */
    @JvmStatic fun fromSnbt(text: String): NbtTag = NbtSnbt.read(text)

    /** 输出 类型后缀 JSON；NaN、Infinity 和负零保留其 NBT 浮点类型。 */
    @JvmStatic fun toJson(tag: NbtTag): String = NbtJson.write(tag)

    /** 只接受明确的 typed JSON；不把普通 JSON 数字、boolean 或 null 隐式转换为 NBT。 */
    @JvmStatic fun fromJson(text: String): NbtTag = NbtJson.Reader(text).read()
}

private object NbtJson {
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
                    out.quoted(key, true)
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
            NbtType.STRING -> out.quoted(tag.value() as String, true, "t")
            NbtType.BYTE_ARRAY -> {
                val values = tag.value() as ByteArray
                out.elements(values.size)
                out.append('"')
                values.forEachIndexed { index, value ->
                    if (index != 0) out.append(',')
                    out.append(value.toString())
                }
                out.append("b]\"")
            }
            NbtType.INT_ARRAY -> {
                val values = tag.value() as IntArray
                out.elements(values.size)
                out.append('"')
                values.forEachIndexed { index, value ->
                    if (index != 0) out.append(',')
                    out.append(value.toString())
                }
                out.append("i]\"")
            }
            NbtType.LONG_ARRAY -> {
                val values = tag.value() as LongArray
                out.elements(values.size)
                out.append('"')
                values.forEachIndexed { index, value ->
                    if (index != 0) out.append(',')
                    out.append(value.toString())
                }
                out.append("l]\"")
            }
            else -> out.quoted(tag.value().toString(), true, NbtTextNumbers.suffix(tag.type).toString())
        }
    }

    class Reader(text: String) : NbtTextReader(text, true) {
        override fun value(depth: Int): NbtTag {
            enter(depth)
            whitespace()
            return when (peek()) {
                '{' -> compound(depth)
                '[' -> list(depth)
                '"' -> typed(quoted())
                else -> fail("Expected a typed JSON string, object or array")
            }
        }

        private fun typed(text: String): NbtTag {
            require(text.isNotEmpty()) { "Empty typed JSON value" }
            if (text.last() == 't') return NbtTag.string(text.dropLast(1))
            if (text.endsWith(']')) {
                require(text.length >= 2) { "Missing typed JSON array type" }
                val type = when (text[text.length - 2]) {
                    'b' -> NbtType.BYTE
                    'i' -> NbtType.INT
                    'l' -> NbtType.LONG
                    else -> fail("Invalid typed JSON array type")
                }
                val values = NbtTextArray()
                val end = text.length - 2
                var start = 0
                if (end > 0) {
                    while (true) {
                        elements(1)
                        val comma = text.indexOf(',', start).let { if (it < 0) end else it }
                        require(comma > start && comma <= end) { "Empty typed JSON array element" }
                        val number = NbtTextNumbers.parse(text.substring(start, comma), type, false)
                        values.add((number.value() as Number).toLong())
                        if (comma == end) break
                        start = comma + 1
                    }
                }
                return values.tag(type)
            }
            val type = when (text.last()) {
                'b' -> NbtType.BYTE
                's' -> NbtType.SHORT
                'i' -> NbtType.INT
                'l' -> NbtType.LONG
                'f' -> NbtType.FLOAT
                'd' -> NbtType.DOUBLE
                else -> fail("Missing or invalid typed JSON suffix")
            }
            return NbtTextNumbers.parse(text.dropLast(1), type, true)
        }
    }
}
