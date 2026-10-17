package me.kzheart.klib.item.tag

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NbtPathTest {
    @Test fun `literal compound keys and operators remain literal`() {
        val root = NbtCompound().putString("display.name", "literal")
        root["list[0]"] = NbtTag.string("also literal")
        root[""] = NbtTag.string("empty key")
        assertEquals("literal", root.getString("display.name"))
        assertNull(root.getPath("display.name"))
        assertEquals("also literal", root.getString("list[0]"))
        assertEquals("empty key", root.getString(""))
        root.putPath("display.name", NbtTag.string("nested"))
        assertEquals("nested", root.getPath("display.name")!!.asString())
        assertEquals("literal", root.getString("display.name"))
    }

    @Test fun `escaped punctuation unicode and spaces address exact keys`() {
        val root = NbtCompound()
        val path = "a\\.b.c\\[d\\].slash\\\\key.标签 name"
        assertSame(root, root.putPath(path, NbtTag.string("value")))
        assertEquals("value", root.getCompound("a.b")!!.getCompound("c[d]")!!
            .getCompound("slash\\key")!!.getString("标签 name"))
        assertEquals("value", root.getPath(path)!!.asString())
        assertTrue(root.containsPath(path))
        assertEquals("value", root.removePath(path)!!.asString())
        assertFalse(root.containsPath(path))
        assertNull(root.removePath(path))
    }

    @Test fun `paths traverse nested lists and compounds and mutate only the destination`() {
        val root = NbtTag.ofMap(mapOf(
            "rows" to listOf(mapOf("name" to "a"), mapOf("name" to "b")),
            "matrix" to listOf(listOf(1, 2), listOf(3, 4))
        ))
        val retainedRows = root.getList("rows")!!
        val retainedRow = retainedRows[0].asCompound()
        root.putPath("rows[0].meta.count", NbtTag.intValue(2))
        assertSame(retainedRows, root.getList("rows"))
        assertSame(retainedRow, root.getPath("rows[0]"))
        assertEquals(2, retainedRow.getCompound("meta")!!.getInt("count"))
        assertEquals("b", root.getPath("rows[01].name")!!.asString())
        assertEquals(4, root.getPath("matrix[1][1]")!!.asInt())
        root.putPath("matrix[1][1]", NbtTag.intValue(5))
        assertEquals(5, root.getPath("matrix[1][1]")!!.asInt())
        assertEquals("a", root.removePath("rows[0].name")!!.asString())
        assertFalse(root.containsPath("rows[0].name"))
        assertEquals(3, root.removePath("matrix[1][0]")!!.asInt())
        assertEquals(5, root.getPath("matrix[1][0]")!!.asInt())
        assertEquals(5, root.removePath("matrix[1][0]")!!.asInt())
        assertNull(root.getPath("matrix[1]")!!.asList().elementType())
        root.getPath("matrix[1]")!!.asList().add(NbtTag.string("new type"))
        assertEquals("new type", root.getPath("matrix[1][0]")!!.asString())
        assertEquals(root, NbtBinary.decode(NbtBinary.encode(root)))
    }

    @Test fun `missing reads and removals never create containers`() {
        val root = NbtCompound().putCompound("exists", NbtCompound())
        val before = root.copy()
        for (path in listOf("missing", "missing.child", "missing[0]", "exists.missing.child[1]")) {
            assertNull(root.getPath(path))
            assertFalse(root.containsPath(path))
            assertNull(root.removePath(path))
            assertEquals(before, root)
        }
    }

    @Test fun `invalid syntax is rejected by all path methods before traversal`() {
        val invalid = listOf("", ".", ".x", "x.", "x..y", "[0]", "x.[0]", "x[]", "x[-1]",
            "x[+1]", "x[1.0]", "x[ 1]", "x[1 ]", "x[１]", "x[1", "x]", "x[0]y", "x[0]]",
            "x[2147483648]", "x[99999999999999999999999999999]", "x\\", "x\\q", "missing..x")
        val root = NbtCompound().putInt("keep", 1)
        val before = root.copy()
        for (path in invalid) {
            val operations: List<() -> Any?> = listOf(
                { root.getPath(path) }, { root.containsPath(path) },
                { root.removePath(path) }, { root.putPath(path, NbtTag.intValue(9)) }
            )
            for (operation in operations) {
                val failure = assertThrows(IllegalArgumentException::class.java, { operation() }, path)
                assertTrue(failure.message!!.contains("Invalid NBT path"), path)
                assertEquals(before, root, path)
            }
        }
    }

    @Test fun `existing wrong containers fail explicitly in every operation`() {
        val root = NbtCompound().putInt("scalar", 1).putCompound("compound", NbtCompound())
            .putList("list", NbtList.of(NbtTag.intValue(1))).putByteArray("array", byteArrayOf(1))
        val before = root.copy()
        for (path in listOf("scalar.child", "scalar[0]", "compound[0]", "list.child", "list[0].child", "array[0]")) {
            val operations: List<() -> Any?> = listOf(
                { root.getPath(path) }, { root.containsPath(path) },
                { root.removePath(path) }, { root.putPath(path, NbtTag.intValue(9)) }
            )
            for (operation in operations) {
                val failure = assertThrows(IllegalStateException::class.java, { operation() }, path)
                assertTrue(failure.message!!.contains(path))
                assertTrue(failure.message!!.contains("expected"))
                assertEquals(before, root)
            }
        }
    }

    @Test fun `list bounds are never treated as missing keys or implicit appends`() {
        val root = NbtCompound().putList("list", NbtList.of(NbtTag.intValue(1))).putList("empty", NbtList())
        val before = root.copy()
        for (path in listOf("list[1]", "list[2147483647]", "list[3].child", "empty[0]")) {
            val operations: List<() -> Any?> = listOf(
                { root.getPath(path) }, { root.containsPath(path) },
                { root.removePath(path) }, { root.putPath(path, NbtTag.intValue(9)) }
            )
            for (operation in operations) {
                assertThrows(IndexOutOfBoundsException::class.java, { operation() }, path)
                assertEquals(before, root)
            }
        }
    }

    @Test fun `failed writes never publish partial branches or damage list type`() {
        val root = NbtCompound().putCompound("existing", NbtCompound())
            .putList("list", NbtList.of(NbtTag.intValue(1)))
        val before = root.copy()
        for (path in listOf("new.list[0]", "new.branch.list[0].child", "existing.new.branch.list[0].child")) {
            assertThrows(NoSuchElementException::class.java) { root.putPath(path, NbtTag.intValue(9)) }
            assertEquals(before, root)
        }
        assertThrows(IllegalArgumentException::class.java) { root.putPath("list[0]", NbtTag.longValue(9)) }
        assertEquals(before, root)
        root.putPath("existing.new.branch", NbtTag.intValue(9))
        assertEquals(9, root.getPath("existing.new.branch")!!.asInt())
    }

    @Test fun `path budget counts every key and index and rejects 513 segments without mutation`() {
        val path512 = List(512) { "x" }.joinToString(".")
        val path513 = "$path512.x"
        val root = NbtCompound().putInt("keep", 1)
        root.putPath(path512, NbtTag.intValue(7))
        assertEquals(7, root.getPath(path512)!!.asInt())
        assertTrue(root.containsPath(path512))
        val before = root.copy()
        val operations: List<() -> Any?> = listOf(
            { root.getPath(path513) }, { root.containsPath(path513) },
            { root.removePath(path513) }, { root.putPath(path513, NbtTag.intValue(8)) }
        )
        for (operation in operations) {
            val failure = assertThrows(IllegalArgumentException::class.java) { operation() }
            assertTrue(failure.message!!.contains("512 segments"))
            assertEquals(before, root)
        }
        // 首个键占一段，后续每个下标分别占一段，不能把一串下标当成单段。
        val indices512 = "missing" + "[0]".repeat(511)
        assertNull(root.getPath(indices512))
        assertThrows(IllegalArgumentException::class.java) { root.getPath(indices512 + "[0]") }
        assertEquals(7, root.removePath(path512)!!.asInt())
        assertFalse(root.containsPath(path512))
    }

    @Test fun `path insertion deeply isolates input across new and existing destinations`() {
        val input = NbtCompound().putInt("value", 1)
        val root = NbtCompound().putCompound("existing", NbtCompound())
            .putList("list", NbtList.of(NbtCompound()))
        root.putPath("new.branch", input).putPath("existing.child", input).putPath("list[0]", input)
        input.putInt("value", 2)
        for (path in listOf("new.branch.value", "existing.child.value", "list[0].value")) {
            assertEquals(1, root.getPath(path)!!.asInt())
        }
        root.getPath("new.branch")!!.asCompound().putInt("value", 3)
        assertEquals(1, root.getPath("existing.child.value")!!.asInt())
        assertEquals(1, root.getPath("list[0].value")!!.asInt())
    }
}
