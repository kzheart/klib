package me.kzheart.klib.item.tag

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.Locale
import java.util.UUID

class NbtTypedModelTest {
    private val values = listOf(
        NbtTag.byteValue(Byte.MIN_VALUE), NbtTag.shortValue(Short.MAX_VALUE),
        NbtTag.intValue(Int.MIN_VALUE), NbtTag.longValue(Long.MAX_VALUE),
        NbtTag.floatValue(-0.0f), NbtTag.doubleValue(Double.POSITIVE_INFINITY),
        NbtTag.bytes(byteArrayOf(-1, 0, 127)), NbtTag.string("标签\u0000😀"),
        NbtList.of(NbtTag.intValue(1)), NbtCompound().putInt("x", 1),
        NbtTag.ints(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE)),
        NbtTag.longs(longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE))
    )

    private val accessors: Map<NbtType, (NbtTag) -> Any> = linkedMapOf(
        NbtType.BYTE to { it.asByte() }, NbtType.SHORT to { it.asShort() },
        NbtType.INT to { it.asInt() }, NbtType.LONG to { it.asLong() },
        NbtType.FLOAT to { it.asFloat() }, NbtType.DOUBLE to { it.asDouble() },
        NbtType.BYTE_ARRAY to { it.asByteArray() }, NbtType.STRING to { it.asString() },
        NbtType.LIST to { it.asList() }, NbtType.COMPOUND to { it.asCompound() },
        NbtType.INT_ARRAY to { it.asIntArray() }, NbtType.LONG_ARRAY to { it.asLongArray() }
    )

    @Test fun `all exact accessors preserve their type and reject every other type`() {
        for (tag in values) {
            val actual = accessors.getValue(tag.type)(tag)
            when (actual) {
                is ByteArray -> assertArrayEquals(tag.value() as ByteArray, actual)
                is IntArray -> assertArrayEquals(tag.value() as IntArray, actual)
                is LongArray -> assertArrayEquals(tag.value() as LongArray, actual)
                is NbtTag -> assertSame(tag, actual)
                else -> assertEquals(tag.value(), actual)
            }
            for ((expected, read) in accessors) {
                if (expected == tag.type) continue
                val failure = assertThrows(IllegalStateException::class.java) { read(tag) }
                assertTrue(failure.message!!.contains("expected NBT $expected", ignoreCase = true))
                assertTrue(failure.message!!.contains("found ${tag.type}"))
            }
        }
        assertTrue(NbtTag.floatValue(Float.NaN).asFloat().isNaN())
        assertTrue(NbtTag.doubleValue(Double.NaN).asDouble().isNaN())
        assertEquals((-0.0f).toBits(), NbtTag.floatValue(-0.0f).asFloat().toBits())
        assertEquals((-0.0).toBits(), NbtTag.doubleValue(-0.0).asDouble().toBits())
    }

    @Test fun `number accessor exposes all six exact numeric types for explicit conversions`() {
        val numeric = setOf(NbtType.BYTE, NbtType.SHORT, NbtType.INT, NbtType.LONG, NbtType.FLOAT, NbtType.DOUBLE)
        for (tag in values) {
            if (tag.type in numeric) {
                assertEquals(tag.value(), tag.asNumber())
                assertEquals(tag.value().javaClass, tag.asNumber().javaClass)
            } else {
                val failure = assertThrows(IllegalStateException::class.java) { tag.asNumber() }
                assertTrue(failure.message!!.contains(tag.type.name))
            }
        }
        assertEquals(1, NbtTag.doubleValue(1.75).asNumber().toInt())
        assertEquals(-1, NbtTag.longValue(Long.MAX_VALUE).asNumber().toInt())
        assertThrows(IllegalStateException::class.java) { NbtTag.string("123").asNumber() }
        assertThrows(IllegalStateException::class.java) { NbtTag.doubleValue(1.0).asInt() }
    }

    @Test fun `typed compound writes and reads every type without coercion`() {
        val compound = NbtCompound()
            .putByte("byte", -7).putShort("short", 32123).putInt("int", Int.MIN_VALUE)
            .putLong("long", Long.MAX_VALUE).putFloat("float", -1.25f).putDouble("double", 2.5)
            .putString("string", "标签").putByteArray("bytes", byteArrayOf(1, 2))
            .putIntArray("ints", intArrayOf(3, 4)).putLongArray("longs", longArrayOf(5, 6))
            .putCompound("compound", NbtCompound().putInt("x", 7))
            .putList("list", NbtList.of(NbtTag.string("hello")))
        assertEquals((-7).toByte(), compound.getByte("byte"))
        assertEquals(32123.toShort(), compound.getShort("short"))
        assertEquals(Int.MIN_VALUE, compound.getInt("int"))
        assertEquals(Long.MAX_VALUE, compound.getLong("long"))
        assertEquals(-1.25f, compound.getFloat("float"))
        assertEquals(2.5, compound.getDouble("double"))
        assertEquals("标签", compound.getString("string"))
        assertArrayEquals(byteArrayOf(1, 2), compound.getByteArray("bytes"))
        assertArrayEquals(intArrayOf(3, 4), compound.getIntArray("ints"))
        assertArrayEquals(longArrayOf(5, 6), compound.getLongArray("longs"))
        assertEquals(7, compound.getCompound("compound")!!.getInt("x"))
        assertEquals("hello", compound.getList("list")!![0].asString())
        compound.getCompound("compound")!!.putInt("x", 8)
        compound.getList("list")!!.add(NbtTag.string("world"))
        assertEquals(8, compound.getCompound("compound")!!.getInt("x"))
        assertEquals(2, compound.getList("list")!!.size())
        assertEquals(compound, NbtBinary.decode(NbtBinary.encode(compound)))
    }

    @Test fun `typed getters return null for missing keys and use defaults only for missing keys`() {
        val empty = NbtCompound()
        val getters: List<(NbtCompound) -> Any?> = listOf(
            { it.getByte("x") }, { it.getShort("x") }, { it.getInt("x") }, { it.getLong("x") },
            { it.getFloat("x") }, { it.getDouble("x") }, { it.getString("x") },
            { it.getByteArray("x") }, { it.getIntArray("x") }, { it.getLongArray("x") },
            { it.getCompound("x") }, { it.getList("x") }
        )
        getters.forEach { assertNull(it(empty)) }
        val defaults: List<Pair<NbtTag, (NbtCompound) -> Any>> = listOf(
            NbtTag.byteValue(9) to { it.getByte("x", 9) },
            NbtTag.shortValue(9) to { it.getShort("x", 9) },
            NbtTag.intValue(9) to { it.getInt("x", 9) },
            NbtTag.longValue(9) to { it.getLong("x", 9) },
            NbtTag.floatValue(9f) to { it.getFloat("x", 9f) },
            NbtTag.doubleValue(9.0) to { it.getDouble("x", 9.0) },
            NbtTag.string("fallback") to { it.getString("x", "fallback") },
            NbtTag.bytes(byteArrayOf(9)) to { it.getByteArray("x", byteArrayOf(9)) },
            NbtTag.ints(intArrayOf(9)) to { it.getIntArray("x", intArrayOf(9)) },
            NbtTag.longs(longArrayOf(9)) to { it.getLongArray("x", longArrayOf(9)) },
            NbtCompound().putInt("fallback", 9) to { it.getCompound("x", NbtCompound().putInt("fallback", 9)) },
            NbtList.of(NbtTag.intValue(9)) to { it.getList("x", NbtList.of(NbtTag.intValue(9))) }
        )
        for ((fallback, read) in defaults) {
            assertEquals(fallback, NbtTag.from(read(empty)))
            for (tag in values) {
                val compound = NbtCompound().put("x", tag)
                if (tag.type == fallback.type) assertEquals(tag, NbtTag.from(read(compound)))
                else assertThrows(IllegalStateException::class.java) { read(compound) }
            }
        }
        getters.forEachIndexed { index, read ->
            val expected = defaults[index].first.type
            for (tag in values.filter { it.type != expected }) {
                assertThrows(IllegalStateException::class.java) { read(NbtCompound().put("x", tag)) }
            }
        }
        val defaultCompound = NbtCompound()
        val defaultArray = byteArrayOf(1)
        assertSame(defaultCompound, empty.getCompound("x", defaultCompound))
        assertSame(defaultArray, empty.getByteArray("x", defaultArray))
    }

    @Test fun `explicit boolean helpers write zero or one and read any nonzero byte as true`() {
        val root = NbtCompound().putBoolean("yes", true).putBoolean("no", false)
        assertEquals(NbtType.BYTE, root["yes"]!!.type)
        assertEquals(1.toByte(), root.getByte("yes"))
        assertEquals(0.toByte(), root.getByte("no"))
        assertEquals(true, root.getBoolean("yes"))
        assertEquals(false, root.getBoolean("no"))
        assertNull(root.getBoolean("missing"))
        assertTrue(root.getBoolean("missing", true))
        assertFalse(root.getBoolean("no", true))
        assertFalse(NbtTag.booleanValue(false).asBoolean())
        assertThrows(IllegalStateException::class.java) { NbtTag.string("true").asBoolean() }
        assertThrows(IllegalStateException::class.java) { NbtTag.intValue(1).asBoolean() }
        for (nonzero in listOf(Byte.MIN_VALUE, (-1).toByte(), 2.toByte(), Byte.MAX_VALUE)) {
            root.putByte("nonzero", nonzero)
            assertTrue(root.getBoolean("nonzero")!!)
            assertTrue(root.getBoolean("nonzero", false))
        }
        root.putString("wrong", "true")
        assertThrows(IllegalStateException::class.java) { root.getBoolean("wrong", true) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.from(true) }
        assertEquals(root, NbtBinary.decode(NbtBinary.encode(root)))
    }

    @Test fun `explicit uuid helpers require canonical strings across JVM versions`() {
        val uuid = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef")
        val fallback = UUID(0, 0)
        val root = NbtCompound().putUuid("id", uuid)
        assertEquals(NbtType.STRING, root["id"]!!.type)
        assertEquals(uuid.toString(), root.getString("id"))
        assertEquals(uuid, root.getUuid("id"))
        assertEquals(uuid, root.getUuid("id", fallback))
        assertNull(root.getUuid("missing"))
        assertSame(fallback, root.getUuid("missing", fallback))
        assertEquals(uuid, NbtTag.uuidValue(uuid).asUuid())
        assertEquals(uuid, NbtTag.string(uuid.toString().uppercase(Locale.ROOT)).asUuid())
        assertThrows(IllegalStateException::class.java) { NbtTag.ints(intArrayOf(1, 2, 3, 4)).asUuid() }
        for (invalid in listOf("", "invalid", "1-1-1-1-1", "000000001-0001-0001-0001-000000000001",
            "12345678-90ab-cdef-1234-567890abcdeg", " 12345678-90ab-cdef-1234-567890abcdef")) {
            root.putString("invalid", invalid)
            assertThrows(IllegalArgumentException::class.java) { root.getUuid("invalid") }
            assertThrows(IllegalArgumentException::class.java) { root.getUuid("invalid", fallback) }
        }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.from(uuid) }
        assertEquals(root, NbtBinary.decode(NbtBinary.encode(root)))
    }

    @Test fun `typed array inputs and stored outputs are isolated`() {
        val bytes = byteArrayOf(1)
        val ints = intArrayOf(2)
        val longs = longArrayOf(3)
        val compound = NbtCompound().putByteArray("b", bytes).putIntArray("i", ints).putLongArray("l", longs)
        bytes[0] = 9; ints[0] = 9; longs[0] = 9
        compound.getByteArray("b")!![0] = 8
        compound.getIntArray("i", intArrayOf())[0] = 8
        compound.getLongArray("l")!![0] = 8
        assertArrayEquals(byteArrayOf(1), compound.getByteArray("b"))
        assertArrayEquals(intArrayOf(2), compound.getIntArray("i"))
        assertArrayEquals(longArrayOf(3), compound.getLongArray("l"))
    }

    @Test fun `explicit recursive factories preserve types and isolate containers`() {
        val bytes = byteArrayOf(1, 2)
        val shared = linkedMapOf<String, Any>("number" to 7.toShort(), "bytes" to bytes)
        val input = linkedMapOf<String, Any>(
            "byte" to 1.toByte(), "long" to Long.MAX_VALUE,
            "list" to mutableListOf(shared, shared), "nested" to listOf(listOf(1), listOf("a")),
            "tag" to NbtCompound().putString("name", "original")
        )
        val result = NbtTag.ofMap(input)
        bytes[0] = 9
        shared["number"] = "changed"
        (input["tag"] as NbtCompound).putString("name", "changed")
        assertEquals(NbtType.BYTE, result["byte"]!!.type)
        assertEquals(Long.MAX_VALUE, result.getLong("long"))
        val list = result.getList("list")!!
        assertEquals(7.toShort(), list[0].asCompound().getShort("number"))
        assertArrayEquals(byteArrayOf(1, 2), list[1].asCompound().getByteArray("bytes"))
        list[0].asCompound().putShort("number", 8)
        assertEquals(7.toShort(), list[1].asCompound().getShort("number"))
        assertEquals("original", result.getCompound("tag")!!.getString("name"))
        assertEquals(NbtType.LIST, result.getList("nested")!!.elementType())
        assertNull(NbtTag.ofList(emptyList<Any>()).elementType())
        assertTrue(NbtTag.ofMap(emptyMap<String, Any>()).isEmpty())
        assertEquals(result, NbtBinary.decode(NbtBinary.encode(result)))
    }

    @Test fun `factories reject invalid conversions while from retains its strict contract`() {
        val unsupported = listOf(true, 'x', BigInteger.ONE, shortArrayOf(1), doubleArrayOf(1.0), setOf(1))
        unsupported.forEach {
            assertThrows(IllegalArgumentException::class.java) { NbtTag.from(it) }
            assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(mapOf("x" to it)) }
        }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.from(mapOf("x" to 1)) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.from(listOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(mapOf(1 to "x")) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(mapOf(null to "x")) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(mapOf("x" to null)) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofList(listOf(null)) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofList(listOf<Any>(1, 2L)) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(mapOf("x" to listOf(1, "2"))) }
        val map = linkedMapOf<String, Any>()
        map["self"] = map
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(map) }
        val list = ArrayList<Any>()
        list.add(list)
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofList(list) }
        val indirect = linkedMapOf<String, Any>()
        val child = arrayListOf<Any>(indirect)
        indirect["child"] = child
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(indirect) }
    }

    @Test fun `recursive factories accept depth 512 and reject depth 513 before copying`() {
        var map: Any = 1
        var list: Any = 1
        repeat(512) {
            map = mapOf("child" to map)
            list = listOf(list)
        }
        val compound = NbtTag.ofMap(map as Map<*, *>)
        val array = NbtTag.ofList(list as List<*>)
        var mapLeaf: NbtTag = compound
        var listLeaf: NbtTag = array
        repeat(512) {
            mapLeaf = mapLeaf.asCompound()["child"]!!
            listLeaf = listLeaf.asList()[0]
        }
        assertEquals(1, mapLeaf.asInt())
        assertEquals(1, listLeaf.asInt())
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(mapOf("child" to map)) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofList(listOf(list)) }
        // 已构造的标签不能成为跳过递归深度验证的捷径。
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofMap(mapOf("child" to compound)) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.ofList(listOf(array)) }
        assertEquals(1, mapLeaf.asInt())
        assertEquals(1, listLeaf.asInt())
    }

    @Test fun `mutable list is homogeneous and resets its type only when empty`() {
        val list = NbtList()
        assertTrue(list.isEmpty())
        assertNull(list.elementType())
        assertSame(list, list.add(NbtTag.intValue(1)))
        list.add(NbtTag.intValue(2))
        assertEquals(1, list.set(0, NbtTag.intValue(3)).asInt())
        list[1] = NbtTag.intValue(4)
        assertEquals(listOf(3, 4), list.map { it.asInt() })
        val before = list.copy()
        assertThrows(IllegalArgumentException::class.java) { list.add(NbtTag.longValue(5)) }
        assertThrows(IllegalArgumentException::class.java) { list[0] = NbtTag.string("bad") }
        assertThrows(IndexOutOfBoundsException::class.java) { list[-1] = NbtTag.intValue(9) }
        assertThrows(IndexOutOfBoundsException::class.java) { list[2] = NbtTag.intValue(9) }
        assertThrows(IndexOutOfBoundsException::class.java) { list.removeAt(2) }
        assertEquals(before, list)
        assertEquals(4, list.removeAt(1).asInt())
        assertThrows(IllegalArgumentException::class.java) { list[0] = NbtTag.string("still bad") }
        list.removeAt(0)
        assertNull(list.elementType())
        list.add(NbtTag.string("now allowed"))
        assertEquals(NbtType.STRING, list.elementType())
        list.clear()
        assertNull(list.elementType())
        list.add(NbtTag.byteValue(2))
        assertEquals(NbtType.BYTE, list.elementType())
    }

    @Test fun `list construction add set and copying isolate input trees`() {
        val input = NbtCompound().putInt("value", 1)
        val initial = arrayListOf<NbtTag>(input)
        val list = NbtList(initial)
        initial.clear()
        list.add(input)
        list[0] = input
        input.putInt("value", 2)
        assertEquals(listOf(1, 1), list.map { it.asCompound().getInt("value") })
        val copy = list.copy()
        copy[0].asCompound().putInt("value", 3)
        assertEquals(1, list[0].asCompound().getInt("value"))
        assertThrows(UnsupportedOperationException::class.java) { (list.values() as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (list.values() as MutableList)[0] = NbtTag.string("bad") }
        val iterator = list.iterator() as MutableIterator<NbtTag>
        iterator.next()
        assertThrows(UnsupportedOperationException::class.java) { iterator.remove() }
        assertEquals(NbtType.COMPOUND, list.elementType())
        assertEquals(2, list.size())
    }
}
