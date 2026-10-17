package me.kzheart.klib.compat.v1_20

import me.kzheart.klib.compat.*

/** Version selection is separate from the shared packet and capability implementations. */
class V1_20CompatImplementation : AbstractCompatProvider(ID, SERVER_VERSION, bridges()) {
    override fun supports(serverVersion: ServerVersion): Boolean = serverVersion >= ServerVersion.of(1, 20, 0) && serverVersion < ServerVersion.of(1, 21, 0)
    companion object {
        const val ID = "compat-v1_20"
        const val SERVER_VERSION = "1.20.4"
        private fun bridges(): Map<Capability<*>, Any> = mapOf(
            Capabilities.TEXT to TextBridge { true },
            Capabilities.NBT to NbtBridge { true },
            Capabilities.MATERIAL to MaterialBridge { false },
            Capabilities.INVENTORY to InventoryBridge { true },
            Capabilities.SIDEBAR to V1_20SidebarBridge()
        )
    }
}
