package me.kzheart.klib.item.tag

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NbtCodecLimitsTest {
    @Test fun `depth 512 is accepted and 513 fails for input and output`() {
        val snbt = "[".repeat(512) + "0" + "]".repeat(512)
        val json = "[".repeat(512) + "\"0i\"" + "]".repeat(512)
        val tag = NbtCodec.fromSnbt(snbt)
        assertEquals(snbt, NbtCodec.toSnbt(tag))
        assertEquals(json, NbtCodec.toJson(NbtCodec.fromJson(json)))
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromSnbt("[$snbt]") }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromJson("[$json]") }
        val tooDeep = NbtList.of(tag)
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toSnbt(tooDeep) }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toJson(tooDeep) }
        // Empty containers at the deepest allowed level do not invent an extra child.
        val empty = "[".repeat(512) + "{}" + "]".repeat(512)
        assertEquals(empty, NbtCodec.toSnbt(NbtCodec.fromSnbt(empty)))
    }

    @Test fun `input and output count UTF8 bytes rather than UTF16 characters`() {
        val max = NbtBinary.MAX_BYTES
        val fitting = "a".repeat(max - 2)
        assertEquals(fitting.length, NbtCodec.fromSnbt("\"$fitting\"").asString().length)
        assertEquals(max, NbtCodec.toSnbt(NbtTag.string(fitting)).length)
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromSnbt(" \"$fitting\"") }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toSnbt(NbtTag.string(fitting + "a")) }
        val jsonFitting = fitting.dropLast(1)
        assertEquals(jsonFitting.length, NbtCodec.fromJson("\"${jsonFitting}t\"").asString().length)
        assertEquals(max, NbtCodec.toJson(NbtTag.string(jsonFitting)).length)
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromJson(" \"${jsonFitting}t\"") }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toJson(NbtTag.string(fitting)) }
        val wide = "中".repeat(max / 3 + 1)
        assertTrue(wide.length < max)
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromSnbt("\"$wide\"") }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromJson("\"${wide}t\"") }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toSnbt(NbtTag.string(wide)) }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toJson(NbtTag.string(wide)) }
        // JSON control-character escaping expands the output, unlike SNBT.
        val controls = "\u0000".repeat(max / 6 + 1)
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toJson(NbtTag.string(controls)) }
        assertEquals(controls.length + 2, NbtCodec.toSnbt(NbtTag.string(controls)).length)
    }

    @Test fun `array and tag count budget applies symmetrically without trusting lengths`() {
        val allowed = NbtTextBudget.MAX_ELEMENTS - 1
        val values = NbtTag.bytes(ByteArray(allowed))
        val snbt = NbtCodec.toSnbt(values)
        val json = NbtCodec.toJson(values)
        assertEquals(allowed, NbtCodec.fromSnbt(snbt).asByteArray().size)
        assertEquals(allowed, NbtCodec.fromJson(json).asByteArray().size)
        val tooLarge = NbtTag.bytes(ByteArray(allowed + 1))
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toSnbt(tooLarge) }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.toJson(tooLarge) }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromSnbt(snbt.dropLast(1) + ",0b]") }
        assertThrows(IllegalArgumentException::class.java) { NbtCodec.fromJson(json.dropLast(3) + ",0b]\"") }
        val budget = NbtTextBudget()
        budget.elements(NbtTextBudget.MAX_ELEMENTS)
        assertThrows(IllegalArgumentException::class.java) { budget.enter(0) }
        assertThrows(IllegalArgumentException::class.java) { NbtTextBudget().elements(-1) }
    }
}
