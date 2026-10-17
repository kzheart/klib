package me.kzheart.klib.item.tag

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompleteItemCountTest {
    @Test fun `positive legacy byte and modern int counts are accepted exactly`() {
        assertEquals(127, NativeItemTags.completeItemCount(NbtCompound().put("Count", NbtTag.byteValue(127)), ItemTagStorage.LEGACY_ROOT))
        assertEquals(Int.MAX_VALUE, NativeItemTags.completeItemCount(NbtCompound().put("count", NbtTag.intValue(Int.MAX_VALUE)), ItemTagStorage.CUSTOM_DATA))
    }

    @Test fun `zero negative and missing counts cannot silently default to one`() {
        for (storage in ItemTagStorage.values()) {
            assertThrows(IllegalArgumentException::class.java) { NativeItemTags.completeItemCount(NbtCompound(), storage) }
            for (value in listOf(0, -1, -128)) {
                val tree = if (storage == ItemTagStorage.LEGACY_ROOT) NbtCompound().put("Count", NbtTag.byteValue(value.toByte()))
                    else NbtCompound().put("count", NbtTag.intValue(value))
                val before = tree.copy()
                assertThrows(IllegalArgumentException::class.java) { NativeItemTags.completeItemCount(tree, storage) }
                assertEquals(before, tree)
            }
        }
    }

    @Test fun `count name and numeric wire type belong to the current schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeItemTags.completeItemCount(NbtCompound().put("Count", NbtTag.intValue(1)), ItemTagStorage.LEGACY_ROOT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeItemTags.completeItemCount(NbtCompound().put("count", NbtTag.byteValue(1)), ItemTagStorage.CUSTOM_DATA)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeItemTags.completeItemCount(NbtCompound().put("Count", NbtTag.byteValue(1)), ItemTagStorage.CUSTOM_DATA)
        }
    }
}
