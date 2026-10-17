package me.kzheart.klib.ui

import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** 单个菜单会话中携带身份信息的 Bukkit 物品栏持有者。 */
internal class MenuHolderState constructor(@JvmField internal val viewer: Player, private val session: MenuSession,
                                      @JvmField internal val renderer: MenuRenderer) {
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
    fun refresh(owner: MenuHolder, model: MenuModel) = renderer.update(owner, model)
    fun refresh(owner: MenuHolder) = renderer.render(owner)
    fun close(owner: MenuHolder, reason: CloseReason): MenuCloseResult = renderer.close(owner, reason)
    fun getInventory(): Inventory? = inventory
}
