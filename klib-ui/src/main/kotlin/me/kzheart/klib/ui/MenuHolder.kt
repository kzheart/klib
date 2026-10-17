package me.kzheart.klib.ui

import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** 单个菜单会话中携带身份信息的 Bukkit 物品栏持有者。 */
class MenuHolder internal constructor(@JvmField internal val viewer: Player, private val session: MenuSession,
                                      @JvmField internal val renderer: MenuRenderer) : InventoryHolder {
    private val playerId = viewer.uniqueId
    private var inventory: Inventory? = null
    @JvmField internal val renderedItems: MutableMap<Int, ItemStack?> = LinkedHashMap()
    @JvmName("attach") internal fun attach(inventory: Inventory) {
        check(this.inventory == null) { "menu inventory is already attached" }
        this.inventory = inventory
    }
    fun playerId(): UUID = playerId
    fun session(): MenuSession = session
    fun model(): MenuModel = session.model()
    fun refresh(model: MenuModel) = renderer.update(this, model)
    fun refresh() = renderer.render(this)
    fun close(reason: CloseReason): MenuCloseResult = renderer.close(this, reason)
    override fun getInventory(): Inventory? = inventory
}
