package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemainingPlatformReturnTest {
    private fun guarded(item: ItemStack?): Int =
        if (Items.customModelData(item) != null) Items.customModelData(item) else -1

    @Test fun customModelDataKeepsPlatformUnboxingAndNullAbsence() {
        FakeItemServer.install(true)
        assertEquals(-1, guarded(null))
        assertEquals(-1, guarded(ItemStack(Material.AIR)))
        assertEquals(-1, guarded(ItemStack(Material.STONE)))
        val item = Items.of(Material.STONE).customModelData(37).build()
        val present: Int = Items.customModelData(item)
        assertEquals(37, present)
        assertEquals(37, guarded(item))
        assertNull(Items.customModelData(Items.edit(item).customModelData(null).build()))
    }
}
