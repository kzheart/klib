package me.kzheart.klib.compat.v1_21

import me.kzheart.klib.compat.*

/** Version selection is separate from the shared packet and capability implementations. */
class V1_21CompatImplementation : AbstractCompatProvider(ID, SERVER_VERSION, bridges()) {
    override fun supports(serverVersion: ServerVersion): Boolean = serverVersion >= version() && serverVersion < ServerVersion.of(26, 2, 0)
    companion object {
        const val ID = "compat-v1_21"
        const val SERVER_VERSION = "1.21"
        private fun bridges(): Map<Capability<*>, Any> = mapOf(
            Capabilities.TEXT to TextBridge { true },
            Capabilities.NBT to NbtBridge { true },
            Capabilities.MATERIAL to MaterialBridge { false },
            Capabilities.INVENTORY to InventoryBridge { true },
            Capabilities.SIDEBAR to V1_21SidebarBridge()
        )
    }
}
