package me.kzheart.klib.item.tag

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Random

class NbtCodecTest {
    private fun allTypes(): NbtCompound = NbtCompound()
        .put("byte", NbtTag.byteValue(Byte.MIN_VALUE))
        .put("short", NbtTag.shortValue(Short.MAX_VALUE))
        .put("int", NbtTag.intValue(Int.MIN_VALUE))
        .put("long", NbtTag.longValue(Long.MAX_VALUE))
        .put("float", NbtTag.floatValue(-0.0f))
        .put("double", NbtTag.doubleValue(Double.MIN_VALUE))
        .put("bytes", NbtTag.bytes(byteArrayOf(-128, 0, 127)))
        .put("string", NbtTag.string("\"'\\ 标签\u0000\n\r\t\b\u000C😀"))
        .put("list", NbtList.of(NbtTag.string("a"), NbtTag.string("b")))
        .put("compound", NbtCompound().put("child", NbtTag.intValue(8)))
        .put("ints", NbtTag.ints(intArrayOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)))
        .put("longs", NbtTag.longs(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)))
        .put("emptyList", NbtList())
        .put("emptyCompound", NbtCompound())
        .put("emptyBytes", NbtTag.bytes(byteArrayOf()))
        .put("emptyInts", NbtTag.ints(intArrayOf()))
        .put("emptyLongs", NbtTag.longs(longArrayOf()))
        .put("literal.dot[0]\\key", NbtTag.string("literal"))
        .put("", NbtTag.string(""))

    @Test fun `all twelve types and empty containers round trip through distinct formats`() {
        val root = allTypes()
        assertEquals(root, NbtCodec.fromBytes(NbtCodec.toBytes(root)))
        assertEquals(root, NbtCodec.fromSnbt(NbtCodec.toSnbt(root)))
        assertEquals(root, NbtCodec.fromJson(NbtCodec.toJson(root)))
        root.entries().values.forEach { tag ->
            assertEquals(tag, NbtCodec.fromSnbt(NbtCodec.toSnbt(tag)), tag.type.toString())
            assertEquals(tag, NbtCodec.fromJson(NbtCodec.toJson(tag)), tag.type.toString())
        }
    }

    @Test fun `public binary codec delegates without changing vanilla wire bytes`() {
        val root = NbtCompound().put("x", NbtTag.intValue(42))
        val expected = byteArrayOf(10, 0, 0, 3, 0, 1, 120, 0, 0, 0, 42, 0)
        assertArrayEquals(expected, NbtCodec.toBytes(root))
        assertArrayEquals(NbtBinary.encode(root), NbtCodec.toBytes(root))
        assertEquals(NbtBinary.decode(expected), NbtCodec.fromBytes(expected))
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromBytes(expected + 0) }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromBytes(byteArrayOf(0)) }
        val negativeArray = byteArrayOf(10, 0, 0, 7, 0, 1, 120, -1, -1, -1, -1, 0)
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromBytes(negativeArray) }
    }

    @Test fun `upstream typed JSON fixture preserves suffixes empty arrays and literal keys`() {
        val fixture = """{"b":"-128b","s":"32767s","i":"2147483647i","l":"9223372036854775807l","f":"1.25f","d":"-2.75d","text":"5it","empty":"t","bytes":"-128,0,127b]","ints":"-2147483648,0,2147483647i]","longs":"-9223372036854775808,0,9223372036854775807l]","list":["1i","2i"],"nested":{"x":"1b"},"a.b":"literal keyt","eb":"b]","ei":"i]","el":"l]"}"""
        val decoded = NbtCodec.fromJson(fixture).asCompound()
        assertEquals(Byte.MIN_VALUE, decoded["b"]!!.value())
        assertEquals(Long.MAX_VALUE, decoded["l"]!!.value())
        assertEquals("5i", decoded["text"]!!.value())
        assertEquals("", decoded["empty"]!!.value())
        assertEquals("literal key", decoded["a.b"]!!.value())
        assertFalse(decoded.contains("a"))
        assertArrayEquals(byteArrayOf(), decoded["eb"]!!.value() as ByteArray)
        assertArrayEquals(intArrayOf(), decoded["ei"]!!.value() as IntArray)
        assertArrayEquals(longArrayOf(), decoded["el"]!!.value() as LongArray)
        assertEquals(fixture, NbtCodec.toJson(decoded))
        listOf("", "42", "1b", "t", "b]", "NaNf", "null", "true").forEach {
            assertEquals(NbtTag.string(it), NbtCodec.fromJson(NbtCodec.toJson(NbtTag.string(it))))
        }
    }

    @Test fun `SNBT handles scalar suffixes booleans quotes whitespace exponents and typed arrays`() {
        val root = NbtCodec.fromSnbt("""{b:+127B,s:-32768S,i:+2147483647,l:-9223372036854775808L,f:.5F,d:1.e-2D,e:2.0e3,yes:TrUe,no:FALSE,name:hello_world-1.2,'a.b':'it\'s "quoted"',ba:[ B ; -128b,0B,127b,],ia:[I; -1,0,1],la:[L; -1l,0L,1l],list:[{a:1},{b:2},],}""").asCompound()
        assertEquals(127.toByte(), root["b"]!!.value())
        assertEquals(Short.MIN_VALUE, root["s"]!!.value())
        assertEquals(Int.MAX_VALUE, root["i"]!!.value())
        assertEquals(Long.MIN_VALUE, root["l"]!!.value())
        assertEquals(0.5f, root["f"]!!.value())
        assertEquals(0.01, root["d"]!!.value())
        assertEquals(2000.0, root["e"]!!.value())
        assertEquals(1.toByte(), root["yes"]!!.value())
        assertEquals(0.toByte(), root["no"]!!.value())
        assertEquals("hello_world-1.2", root["name"]!!.value())
        assertEquals("it's \"quoted\"", root["a.b"]!!.value())
        assertEquals(NbtTag.intValue(1), NbtCodec.fromSnbt("\u2003 1\n\t"))
        assertEquals(NbtTag.string("B"), NbtCodec.fromSnbt("[B]").asList()[0])
        assertEquals(NbtTag.string("12i"), NbtCodec.fromSnbt("12i"))
        listOf("01", "01b", "1e3").forEach { assertEquals(NbtTag.string(it), NbtCodec.fromSnbt(it)) }
        assertEquals(root, NbtCodec.fromSnbt(NbtCodec.toSnbt(root)))
    }

    @Test fun `JSON escaping is strict and preserves all UTF16 strings`() {
        val raw = "\"\\/\b\u000C\n\r\t\u0000中文😀\uD800\uDC00\uD800"
        val tag = NbtTag.string(raw)
        val json = NbtCodec.toJson(tag)
        assertFalse(json.any { it < ' ' })
        assertEquals(tag, NbtCodec.fromJson(json))
        assertEquals(NbtTag.string("\"\\/\b\u000C\n\r\tA"), NbtCodec.fromJson("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041t\""))
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toSnbt(tag) }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromSnbt("\"\uD800\"") }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromJson("\"\uD800t\"") }
        val controls = (0..31).map { it.toChar() }.joinToString("")
        assertEquals(NbtTag.string(controls), NbtCodec.fromSnbt(NbtCodec.toSnbt(NbtTag.string(controls))))
    }

    @Test fun `malformed JSON and SNBT fail rather than losing data`() {
        val invalidJson = listOf("", "1", "true", "null", "\"\"", "\"123\"", "\"1B\"", "\"1.0i\"", "\"b\"", "\"]\"", "\"x]\"", "\"1,b]\"", "\",1i]\"", "\"1,,2l]\"", "\"128b]\"", "{'a':'1i'}", "{a:\"1i\"}", "{\"a\":\"1i\",\"a\":\"2i\"}", "{\"a\":\"1i\",}", "[\"1i\",]", "[\"1i\",\"1l\"]", "\"1i\" true", "\"\\xi\"", "\"\\u12Xt\"", "\"\\u１２３４t\"", "\"\nt\"", "[", "{")
        invalidJson.forEach { text -> assertThrows(IllegalArgumentException::class.java, { NbtCodec.fromJson(text) }, text) }
        val invalidSnbt = listOf("", " ", "{a:1,a:2}", "{a:1,\"a\":2}", "{a:1}{}", "[1,2L]", "[B;1]", "[I;1L]", "[L;1]", "[X;1]", "[B;-129b]", "{a:}", "{a 1}", "[1,,2]", "'unterminated", "\"\\n\"", "\"\\u0041\"", "\"\\'\"", "{minecraft:id:1}", "[", "{")
        invalidSnbt.forEach { text -> assertThrows(IllegalArgumentException::class.java, { NbtCodec.fromSnbt(text) }, text) }
    }

    @Test fun `numeric boundaries reject overflow and underflow instead of falling back`() {
        val snbt = listOf("128b", "-129b", "32768s", "-32769s", "2147483648", "-2147483649", "9223372036854775808L", "-9223372036854775809L", "1e100f", "1e400d", "1e-1000f", "1e-1000d", "NaNf", "Infinityd", "-Infinityf", "1".repeat(129))
        snbt.forEach { text -> assertThrows(IllegalArgumentException::class.java, { NbtCodec.fromSnbt(text) }, text) }
        val json = listOf("128b", "-129b", "32768s", "2147483648i", "9223372036854775808l", "1e100f", "1e400d", "1e-1000f", "1e-1000d", "1.0b", "0x1p1f", "nanf", "1".repeat(129) + "i")
        json.forEach { text -> assertThrows(IllegalArgumentException::class.java, { NbtCodec.fromJson("\"$text\"") }, text) }
    }

    @Test fun `finite floating point bit patterns and negative zero round trip exactly`() {
        val random = Random(724913)
        val floats = mutableListOf(0.0f, -0.0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        val doubles = mutableListOf(0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE)
        repeat(300) {
            floats.add(java.lang.Float.intBitsToFloat(random.nextInt()))
            doubles.add(java.lang.Double.longBitsToDouble(random.nextLong()))
        }
        floats.filter { it.isFinite() }.forEach { original ->
            val tag = NbtTag.floatValue(original)
            val fromSnbt = NbtCodec.fromSnbt(NbtCodec.toSnbt(tag)).value() as Float
            val fromJson = NbtCodec.fromJson(NbtCodec.toJson(tag)).value() as Float
            assertEquals(java.lang.Float.floatToRawIntBits(original), java.lang.Float.floatToRawIntBits(fromSnbt))
            assertEquals(java.lang.Float.floatToRawIntBits(original), java.lang.Float.floatToRawIntBits(fromJson))
        }
        doubles.filter { it.isFinite() }.forEach { original ->
            val tag = NbtTag.doubleValue(original)
            val fromSnbt = NbtCodec.fromSnbt(NbtCodec.toSnbt(tag)).value() as Double
            val fromJson = NbtCodec.fromJson(NbtCodec.toJson(tag)).value() as Double
            assertEquals(java.lang.Double.doubleToRawLongBits(original), java.lang.Double.doubleToRawLongBits(fromSnbt))
            assertEquals(java.lang.Double.doubleToRawLongBits(original), java.lang.Double.doubleToRawLongBits(fromJson))
        }
    }

    @Test fun `nonfinite values round trip through JSON and bytes but are refused by SNBT`() {
        val values = listOf(NbtTag.floatValue(Float.NaN), NbtTag.floatValue(Float.POSITIVE_INFINITY), NbtTag.floatValue(Float.NEGATIVE_INFINITY), NbtTag.doubleValue(Double.NaN), NbtTag.doubleValue(Double.POSITIVE_INFINITY), NbtTag.doubleValue(Double.NEGATIVE_INFINITY))
        values.forEach {
            assertEquals(it, NbtCodec.fromJson(NbtCodec.toJson(it)))
            val root = NbtCompound().put("x", it)
            assertEquals(root, NbtCodec.fromBytes(NbtCodec.toBytes(root)))
            assertThrows(IllegalArgumentException::class.java) { NbtCodec.toSnbt(it) }
        }
        assertEquals(NbtTag.floatValue(Float.POSITIVE_INFINITY), NbtCodec.fromJson("\"+Infinityf\""))
        assertEquals(NbtTag.string("NaNf"), NbtCodec.fromSnbt("\"NaNf\""))
    }

    @Test fun `decoded snapshots support CRUD without sharing source values`() {
        val original = NbtCompound().put("a.b", NbtList.of(NbtCompound().put("n", NbtTag.intValue(1))))
        listOf(NbtCodec.fromSnbt(NbtCodec.toSnbt(original)), NbtCodec.fromJson(NbtCodec.toJson(original)), NbtCodec.fromBytes(NbtCodec.toBytes(original))).forEach { snapshot ->
            val root = snapshot.asCompound()
            val list = root["a.b"]!!.asList()
            list[0].asCompound()["n"] = NbtTag.intValue(2)
            list.add(NbtCompound().put("n", NbtTag.intValue(3)))
            assertEquals(2, list.size())
            assertEquals(NbtTag.intValue(2), list.removeAt(0).asCompound()["n"])
            root["created"] = NbtTag.string("new")
            assertTrue(root.contains("created"))
            assertEquals(NbtTag.string("new"), root.remove("created"))
            assertFalse(root.contains("created"))
        }
        assertEquals(1, original["a.b"]!!.asList().size())
        assertEquals(NbtTag.intValue(1), original["a.b"]!!.asList()[0].asCompound()["n"])
    }
}
