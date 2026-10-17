/*
 * 差异更新流程改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/NMSScoreboard.kt、
 * NMSScoreboardImpl.kt 与 type/PlayerScoreboard.kt。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; the full license text is reproduced in THIRD_PARTY_NOTICES.md
 * and META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.sidebar

import me.kzheart.klib.compat.Capabilities
import me.kzheart.klib.compat.CompatProviders
import me.kzheart.klib.compat.SidebarBridge
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Packet-only sidebars, serialized per player; no Bukkit scoreboard ownership is taken. */
class Sidebars private constructor(private val bridge: SidebarBridge, private val objective: String) : Disposable {
    private val sidebars = ConcurrentHashMap<UUID, PlayerSidebar>()
    @Volatile private var disposed = false
    fun maxLines(): Int = bridge.maxLines()
    fun show(player: Player, title: String, vararg lines: String) = show(player, title, lines.asList())
    fun show(player: Player, title: String, lines: List<String>) {
        val copy = lines.map { java.util.Objects.requireNonNull(it, "line") }
        val max = maxLines()
        require(copy.size <= max) { "sidebar supports at most $max lines, got ${copy.size}" }
        ensureActive()
        if (!player.isOnline) return
        sidebars.compute(player.uniqueId) { _, current ->
            ensureActive()
            (current?.takeIf { it.belongsTo(player) } ?: PlayerSidebar(bridge, objective, player)).apply { show(title, copy) }
        }
    }
    fun hide(player: Player): Boolean = hide(player.uniqueId, player)
    fun isShown(player: Player): Boolean = sidebars[player.uniqueId]?.belongsTo(player) == true
    override fun dispose() {
        disposed = true
        var failure: RuntimeException? = null
        sidebars.keys.toList().forEach { id ->
            try { hide(id, null) } catch (exception: RuntimeException) {
                if (failure == null) failure = exception else failure!!.addSuppressed(exception)
            }
        }
        failure?.let { throw it }
    }
    private fun hide(id: UUID, expected: Player?): Boolean {
        var shown = false
        var failure: RuntimeException? = null
        sidebars.computeIfPresent(id) { _, sidebar ->
            if (expected == null || sidebar.belongsTo(expected)) {
                shown = true
                try { sidebar.hide() } catch (exception: RuntimeException) { failure = exception }
            }
            null
        }
        failure?.let { throw it }
        return shown
    }
    private fun ensureActive() = check(!disposed) { "sidebars have been disposed" }
    companion object {
        private val random = SecureRandom()
        @JvmStatic fun install(scope: Scope): Sidebars {
            val provider = CompatProviders.resolveCurrent()
            val bridge = provider.capability(Capabilities.SIDEBAR).orElseThrow {
                IllegalStateException("Compatibility provider ${provider.id()} does not support packet sidebars on this server")
            }
            return install(scope, bridge)
        }
        @JvmStatic fun install(scope: Scope, bridge: SidebarBridge): Sidebars {
            check(!scope.isClosed) { "cannot install sidebars in a closed scope" }
            val sidebars = scope.install(Sidebars(bridge, "ks" + String.format("%08x", random.nextInt())))
            scope.on(PlayerQuitEvent::class.java) { event -> sidebars.sidebars.remove(event.player.uniqueId) }
            return sidebars
        }
    }
}
