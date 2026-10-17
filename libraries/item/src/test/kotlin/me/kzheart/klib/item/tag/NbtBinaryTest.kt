package me.kzheart.klib.item.tag

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class NbtBinaryTest {
    @Test fun `all standard NBT types round trip without loss`() {
        val root = NbtCompound()
            .put("byte", NbtTag.byteValue((-3).toByte())).put("short", NbtTag.shortValue(32000))
            .put("int", NbtTag.intValue(Int.MIN_VALUE)).put("long", NbtTag.longValue(Long.MAX_VALUE))
            .put("float", NbtTag.floatValue(1.25f)).put("double", NbtTag.doubleValue(-2.75))
            .put("bytes", NbtTag.bytes(byteArrayOf(-1, 0, 127)))
            .put("string", NbtTag.string("标签\u0000\uD83D\uDE00"))
            .put("list", NbtList.of(NbtTag.string("a"), NbtTag.string("b")))
            .put("compound", NbtCompound().put("child", NbtTag.intValue(8)))
            .put("ints", NbtTag.ints(intArrayOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)))
            .put("longs", NbtTag.longs(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)))
            .put("empty", NbtList())
        assertEquals(root, NbtBinary.decode(NbtBinary.encode(root)))
    }

    @Test fun `known vanilla uncompressed format is decoded`() {
        val bytes = byteArrayOf(10, 0, 0, 3, 0, 1, 120, 0, 0, 0, 42, 0)
        assertEquals(42, NbtBinary.decode(bytes)["x"]!!.value())
        assertArrayEquals(bytes, NbtBinary.encode(NbtCompound().put("x", NbtTag.intValue(42))))
    }

    @Test fun `arrays and compound snapshots are independent`() {
        val input = byteArrayOf(1, 2)
        val tag = NbtTag.bytes(input)
        input[0] = 8
        (tag.value() as ByteArray)[1] = 9
        assertArrayEquals(byteArrayOf(1, 2), tag.value() as ByteArray)
        val original = NbtCompound().put("nested", NbtCompound().put("x", NbtTag.intValue(1)))
        val copy = original.copy()
        (copy["nested"] as NbtCompound)["x"] = NbtTag.intValue(2)
        assertEquals(1, ((original["nested"] as NbtCompound)["x"]!!).value())
    }

    @Test fun `mixed list and unsupported scalar fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) { NbtList.of(NbtTag.intValue(1), NbtTag.string("x")) }
        assertThrows(IllegalArgumentException::class.java) { NbtTag.from(true) }
        assertThrows(IllegalStateException::class.java) { NbtCompound().value() }
    }

    @Test fun `invalid and truncated lengths fail before allocation`() {
        fun invalid(id: Int, length: Int): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeByte(10); it.writeUTF(""); it.writeByte(id); it.writeUTF("x"); it.writeInt(length); it.writeByte(0) }
            return bytes.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { NbtBinary.decode(invalid(7, Int.MAX_VALUE)) }
        assertThrows(IllegalArgumentException::class.java) { NbtBinary.decode(invalid(11, -1)) }
        assertThrows(IllegalArgumentException::class.java) { NbtBinary.decode(byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { NbtBinary.decode(byteArrayOf(10, 0, 0, 0, 7)) }
    }

    @Test fun `size and depth limits fail explicitly`() {
        val out = NbtBinary.LimitedOutput()
        assertThrows(java.io.IOException::class.java) { out.write(ByteArray(NbtBinary.MAX_BYTES + 1)) }
        var nested = NbtCompound()
        repeat(514) { nested = NbtCompound().put("x", nested) }
        assertThrows(IllegalArgumentException::class.java) { NbtBinary.encode(nested) }
    }
}
