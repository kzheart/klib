package me.kzheart.klib.lang

import me.kzheart.klib.scheduler.KScheduler
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Disposable
import org.bukkit.Server
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import java.util.UUID

class BukkitMessageRouter internal constructor(
    private val server: Server,
    private val components: BukkitComponentSender,
    private val scheduler: KScheduler?,
    private val bossBarDuration: Ticks
) : MessageRouter, Disposable {
    private val lifecycleLock = Any()
    private val bossBars = LinkedHashMap<UUID, BossBarEntry>()
    private var disposed = false

    constructor(server: Server) : this(server, ReflectionBukkitComponentSender.INSTANCE, null, DEFAULT_BOSS_BAR_DURATION)
    constructor(server: Server, scheduler: KScheduler?) : this(server, ReflectionBukkitComponentSender.INSTANCE, scheduler, DEFAULT_BOSS_BAR_DURATION)
    internal constructor(server: Server, components: BukkitComponentSender) : this(server, components, null, DEFAULT_BOSS_BAR_DURATION)

    override fun route(recipient: MessageRecipient, message: RichText) = route(recipient, MessageRoute.CHAT, message)
    override fun route(recipient: MessageRecipient, route: MessageRoute, message: RichText) {
        synchronized(lifecycleLock) { ensureOpen() }
        releaseOfflineBossBars()
        val player = recipient.handle() as? Player
        if (recipient.isConsole() || player == null) {
            recipient.sendLegacy(message.plainText())
            return
        }
        when (route) {
            MessageRoute.CHAT -> sendComponentOrLegacy(player, message, false)
            MessageRoute.ACTION_BAR -> sendComponentOrLegacy(player, message, true)
            MessageRoute.TITLE -> sendTitle(player, message)
            MessageRoute.BOSS_BAR -> sendBossBar(player, message)
        }
    }

    override fun dispose() {
        val entries = synchronized(lifecycleLock) {
            if (disposed) return
            disposed = true
            bossBars.values.toList().also { bossBars.clear() }
        }
        entries.forEach { it.bar.removeAll() }
    }

    private fun sendComponentOrLegacy(player: Player, message: RichText, actionBar: Boolean) {
        if (!components.send(player, message, actionBar)) player.sendMessage(message.legacyText())
    }

    private fun sendBossBar(player: Player, message: RichText) {
        val replacement = server.createBossBar(message.legacyText(), BarColor.WHITE, BarStyle.SOLID)
        replacement.addPlayer(player)
        val entry = BossBarEntry(player, replacement)
        val previous = synchronized(lifecycleLock) {
            if (disposed) {
                replacement.removeAll()
                throw IllegalStateException("Bukkit message router is closed")
            }
            bossBars.put(player.uniqueId, entry)
        }
        previous?.bar?.removeAll()
        scheduler?.after(bossBarDuration) { expireBossBar(player.uniqueId, entry) }
    }

    private fun expireBossBar(playerId: UUID, entry: BossBarEntry) {
        synchronized(lifecycleLock) {
            if (disposed || bossBars[playerId] !== entry) return
            bossBars.remove(playerId)
        }
        entry.bar.removeAll()
    }

    private fun releaseOfflineBossBars() {
        val released = synchronized(lifecycleLock) {
            val entries = ArrayList<BossBarEntry>()
            val iterator = bossBars.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.player.isOnline) {
                    iterator.remove()
                    entries.add(entry)
                }
            }
            entries
        }
        released.forEach { it.bar.removeAll() }
    }

    private fun ensureOpen() { check(!disposed) { "Bukkit message router is closed" } }
    private class BossBarEntry(val player: Player, val bar: BossBar)

    companion object {
        private val DEFAULT_BOSS_BAR_DURATION = Ticks.of(100L)
        private fun sendTitle(player: Player, message: RichText) {
            val legacy = message.legacyText()
            val separator = legacy.indexOf('|')
            player.sendTitle(if (separator >= 0) legacy.substring(0, separator) else legacy,
                if (separator >= 0) legacy.substring(separator + 1) else "", 10, 60, 10)
        }
    }
}
