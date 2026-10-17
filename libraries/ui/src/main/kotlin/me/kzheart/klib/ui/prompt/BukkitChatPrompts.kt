package me.kzheart.klib.ui.prompt

import me.kzheart.klib.KLogger
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/** 作用域持有的统一聊天提示监听器。 */
class BukkitChatPrompts private constructor(private val owner: Scope, private val plugin: Plugin,
                                            private val logger: KLogger) : Listener, Disposable {
    private val active = HashMap<UUID, ActivePrompt<*>>()
    private var disposed = false
    fun <T> start(player: Player, spec: PromptSpec<T>): PromptSession<T> {
        val prompt: ActivePrompt<T>
        val previous = synchronized(this) {
            ensureOpen()
            prompt = ActivePrompt(player.uniqueId, spec, ChatPrompt.start(owner, spec.timeout(), spec.parser()))
            active.put(player.uniqueId, prompt)
        }
        previous?.let { it.superseded = true; it.session.cancel() }
        prompt.session.completionSync().whenComplete { outcome, failure ->
            synchronized(this) { active.remove(prompt.playerId, prompt) }
            if (failure != null) {
                if (!owner.isClosed) logger.error("聊天输入结果无法切回主线程: player=${prompt.playerId}", failure)
            } else {
                val current = plugin.server.getPlayer(prompt.playerId)
                if (current != null && current.isOnline) {
                    if (outcome.status() == PromptStatus.CANCELLED && !prompt.superseded) send(current, spec.cancelledMessage())
                    else if (outcome.status() == PromptStatus.TIMED_OUT) send(current, spec.timeoutMessage())
                }
            }
        }
        return prompt.session
    }
    @Synchronized fun hasPrompt(playerId: UUID): Boolean = playerId in active
    fun cancel(playerId: UUID): Boolean = synchronized(this) { active[playerId] }?.session?.cancel() ?: false
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onChat(event: AsyncPlayerChatEvent) {
        val prompt = synchronized(this) { if (disposed) return; active[event.player.uniqueId] } ?: return
        event.isCancelled = true
        val input = event.message
        if (prompt.spec.cancelKeyword().equals(input.trim(), true)) prompt.session.cancel()
        else if (!prompt.session.submit(input)) dispatchMessage(prompt.playerId, prompt.spec.invalidMessage())
    }
    override fun dispose() {
        val snapshot = synchronized(this) {
            if (disposed) return
            disposed = true
            active.values.toList().also { active.clear() }
        }
        HandlerList.unregisterAll(this)
        snapshot.forEach { it.session.cancel() }
    }
    private fun dispatchMessage(playerId: UUID, message: String) {
        if (message.isEmpty()) return
        try { owner.after(Ticks.of(0)) { plugin.server.getPlayer(playerId)?.takeIf { it.isOnline }?.let { send(it, message) } } }
        catch (failure: RuntimeException) { if (!owner.isClosed) logger.error("聊天输入反馈无法切回主线程: player=$playerId", failure) }
    }
    @Synchronized private fun ensureOpen() { check(!disposed && !owner.isClosed) { "chat prompt bridge is closed" } }
    private class ActivePrompt<T>(val playerId: UUID, val spec: PromptSpec<T>, val session: PromptSession<T>) {
        @Volatile var superseded = false
    }
    companion object {
        @JvmStatic fun install(owner: Scope, plugin: Plugin): BukkitChatPrompts {
            check(!owner.isClosed) { "cannot install chat prompts in a closed scope" }
            val prompts = BukkitChatPrompts(owner, plugin, owner.findCapability(KLogger::class.java).orElseGet { KLogger(plugin.logger) })
            owner.install(prompts)
            try { plugin.server.pluginManager.registerEvents(prompts, plugin) }
            catch (failure: RuntimeException) { prompts.dispose(); throw failure }
            return prompts
        }
        private fun send(player: Player, message: String) { if (message.isNotEmpty()) player.sendMessage(message) }
    }
}
