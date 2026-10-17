package me.kzheart.klib.compat

import java.util.Collections

class Capabilities private constructor() {
    companion object {
        @JvmField val TEXT: Capability<TextBridge> = Capability.of("text", TextBridge::class.java)
        @JvmField val NBT: Capability<NbtBridge> = Capability.of("nbt", NbtBridge::class.java)
        @JvmField val MATERIAL: Capability<MaterialBridge> = Capability.of("material", MaterialBridge::class.java)
        @JvmField val INVENTORY: Capability<InventoryBridge> = Capability.of("inventory", InventoryBridge::class.java)
        @JvmField val SIDEBAR: Capability<SidebarBridge> = Capability.of("sidebar", SidebarBridge::class.java)
        private val values: List<Capability<*>> = Collections.unmodifiableList(listOf(TEXT, NBT, MATERIAL, INVENTORY, SIDEBAR))
        @JvmStatic fun values(): List<Capability<*>> = values
    }
}
