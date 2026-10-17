package me.kzheart.klib.item.tag

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompleteItemCustomDataTest {
    private val names = listOf("minecraft:custom_data", "custom_data", ":custom_data")
    private fun item(components: NbtCompound) = NbtCompound().putCompound("components", components)

    @Test fun `all accepted names retain exact numeric lists without mutating input`() {
        val data = NbtCompound().put("steps", NbtList.of(NbtTag.intValue(1), NbtTag.intValue(2)))
        names.forEach { name ->
            val root = item(NbtCompound().putCompound(name, data)); val before = root.copy()
            assertEquals(data, NativeItemTags.completeItemCustomData(root))
            assertEquals(before, root)
        }
    }

    @Test fun `all set remove combinations and duplicate names fail before codec`() {
        names.forEach { set -> names.forEach { remove ->
            val root = item(NbtCompound().putCompound(set, NbtCompound()).putCompound("!$remove", NbtCompound()))
            val before = root.copy()
            assertThrows(IllegalArgumentException::class.java) { NativeItemTags.completeItemCustomData(root) }
            assertEquals(before, root)
        } }
        assertThrows(IllegalArgumentException::class.java) {
            NativeItemTags.completeItemCustomData(item(NbtCompound().putCompound(names[0], NbtCompound()).putCompound(names[1], NbtCompound())))
        }
    }

    @Test fun `empty compound remains distinct while string and removal stay with host codec`() {
        names.forEach { name ->
            assertEquals(NbtCompound(), NativeItemTags.completeItemCustomData(item(NbtCompound().putCompound(name, NbtCompound()))))
            assertNull(NativeItemTags.completeItemCustomData(item(NbtCompound().putString(name, "{steps:[1,2]}"))))
            assertNull(NativeItemTags.completeItemCustomData(item(NbtCompound().putCompound("!$name", NbtCompound()))))
        }
    }
}
