package me.kzheart.klib.ui.chat

import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.function.Consumer
import me.kzheart.klib.KLogger
import me.kzheart.klib.lang.BukkitAdventure
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin

/** Paper 反射适配器只拥有虚拟方块和回调；输入生命周期由面板模块管理。 */
internal class PaperSignInput private constructor(private val owner: Scope, private val logger: KLogger,
                                                  event: Class<*>) : ChatPanelSignInput, Listener, Disposable {
    private val loader = Bukkit::class.java.classLoader
    private val position = loader.loadClass("io.papermc.paper.math.Position")
    private val side = loader.loadClass("org.bukkit.block.sign.Side")
    private val blockData = loader.loadClass("org.bukkit.block.data.BlockData")
    private val block = position.getMethod("block", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val front = side.getField("FRONT").get(null)
    private val openVirtualSign = Player::class.java.getMethod("openVirtualSign", position, side)
    private val editedPosition = event.getMethod("getEditedBlockPosition")
    private val blockX = position.getMethod("blockX")
    private val blockY = position.getMethod("blockY")
    private val blockZ = position.getMethod("blockZ")
    private val lines = event.getMethod("lines")
    private val sendBlockChange = Player::class.java.getMethod("sendBlockChange", Location::class.java, blockData)
    private val getBlockData = Block::class.java.getMethod("getBlockData")
    private val signData = Server::class.java.getMethod("createBlockData", String::class.java).invoke(Bukkit.getServer(), "minecraft:oak_sign")
    private val pending = HashMap<UUID, Pending>()

    override fun open(player: Player, text: Array<String>, submitted: Consumer<Array<String>>): Boolean {
        cancel(player)
        val location = player.location.block.location
        try {
            sendBlockChange.invoke(player, location, signData)
            player.sendSignChange(location, text)
            openVirtualSign.invoke(player, block.invoke(null, location.blockX, location.blockY, location.blockZ), front)
        } catch (failure: ReflectiveOperationException) { return openFailed(player, location, failure) }
        catch (failure: RuntimeException) { return openFailed(player, location, failure) }
        pending[player.uniqueId] = Pending(location, submitted)
        return true
    }
    private fun openFailed(player: Player, location: Location, failure: Exception): Boolean {
        restore(player, location)
        logger.error("无法打开告示牌输入", if (failure is InvocationTargetException) failure.cause else failure)
        return false
    }
    override fun cancel(player: Player) { pending.remove(player.uniqueId)?.let { restore(player, it.location) } }
    private fun submitted(event: Event) {
        if (!Bukkit.isPrimaryThread()) { owner.submitSync { submitted(event); null }; return }
        val player = (event as PlayerEvent).player
        val current = pending[player.uniqueId] ?: return
        val values = try {
            val position = editedPosition.invoke(event)
            if (blockX.invoke(position) as Int != current.location.blockX || blockY.invoke(position) as Int != current.location.blockY ||
                blockZ.invoke(position) as Int != current.location.blockZ) return
            (lines.invoke(event) as List<*>).map { BukkitAdventure.plain(it!!) }.toTypedArray()
        } catch (failure: ReflectiveOperationException) { logger.error("无法读取告示牌输入", failure); emptyArray() }
        catch (failure: RuntimeException) { logger.error("无法读取告示牌输入", failure); emptyArray() }
        pending.remove(player.uniqueId); restore(player, current.location); current.submitted.accept(values)
    }
    private fun restore(player: Player, location: Location) {
        if (!player.isOnline) return
        try { sendBlockChange.invoke(player, location, getBlockData.invoke(location.block)) }
        catch (failure: ReflectiveOperationException) { logger.error("无法还原告示牌输入位置", failure) }
        catch (failure: RuntimeException) { logger.error("无法还原告示牌输入位置", failure) }
    }
    @EventHandler fun quit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
    override fun dispose() {
        pending.entries.toList().forEach { (id, value) -> Bukkit.getPlayer(id)?.let { restore(it, value.location) } }
        pending.clear(); HandlerList.unregisterAll(this)
    }
    private class Pending(val location: Location, val submitted: Consumer<Array<String>>)
    companion object {
        @JvmStatic fun detect(owner: Scope, plugin: Plugin, logger: KLogger): PaperSignInput? {
            val event: Class<*>
            val input: PaperSignInput
            try {
                event = Bukkit::class.java.classLoader.loadClass("io.papermc.paper.event.packet.UncheckedSignChangeEvent")
                input = PaperSignInput(owner, logger, event)
            } catch (_: ReflectiveOperationException) { return null }
            catch (_: LinkageError) { return null }
            catch (_: RuntimeException) { return null }
            owner.install(input)
            plugin.server.pluginManager.registerEvents(input, plugin)
            @Suppress("UNCHECKED_CAST")
            plugin.server.pluginManager.registerEvent(event as Class<out Event>, input, EventPriority.NORMAL,
                { _, fired -> if (event.isInstance(fired)) input.submitted(fired) }, plugin, false)
            return input
        }
    }
}
