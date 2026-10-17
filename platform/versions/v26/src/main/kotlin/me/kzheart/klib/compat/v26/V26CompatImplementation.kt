package me.kzheart.klib.compat.v26

import me.kzheart.klib.compat.*

/** Version selection is separate from the shared packet and capability implementations. */
class V26CompatImplementation : AbstractCompatProvider(ID, SERVER_VERSION, bridges()) {
    override fun supports(serverVersion: ServerVersion): Boolean = serverVersion.major() == version().major() && serverVersion >= version()
    companion object {
        const val ID = "compat-v26"
        const val SERVER_VERSION = "26.2"
        private fun bridges(): Map<Capability<*>, Any> = mapOf(
            Capabilities.TEXT to TextBridge { true },
            Capabilities.NBT to NbtBridge { true },
            Capabilities.MATERIAL to MaterialBridge { false },
            Capabilities.INVENTORY to InventoryBridge { true },
            Capabilities.SIDEBAR to V26SidebarBridge()
        )
    }
}
