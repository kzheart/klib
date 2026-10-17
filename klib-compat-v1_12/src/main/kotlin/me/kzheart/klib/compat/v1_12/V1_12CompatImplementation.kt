package me.kzheart.klib.compat.v1_12

import me.kzheart.klib.compat.*

/** Version selection is separate from the shared packet and capability implementations. */
class V1_12CompatImplementation : AbstractCompatProvider(ID, SERVER_VERSION, bridges()) {
    override fun supports(serverVersion: ServerVersion): Boolean = serverVersion >= version() && serverVersion < ServerVersion.of(1, 20, 0)
    companion object {
        const val ID = "compat-v1_12"
        const val SERVER_VERSION = "1.12.2"
        private fun bridges(): Map<Capability<*>, Any> = mapOf(
            Capabilities.TEXT to TextBridge { false },
            Capabilities.NBT to NbtBridge { false },
            Capabilities.MATERIAL to MaterialBridge { true },
            Capabilities.INVENTORY to InventoryBridge { false },
            Capabilities.SIDEBAR to V1_12SidebarBridge()
        )
    }
}
