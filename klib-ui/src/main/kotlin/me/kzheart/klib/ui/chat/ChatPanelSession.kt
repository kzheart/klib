package me.kzheart.klib.ui.chat

import java.util.ArrayDeque
import java.util.Deque
import java.util.function.Consumer
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.scheduler.TaskHandle
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.ui.prompt.PromptSession
import org.bukkit.entity.Player

/** 一个玩家的页面历史、当前输入和刷新状态，生命周期由统一面板桥接拥有。 */
class ChatPanelSession internal constructor(@JvmField internal val panels: BukkitChatPanels, @JvmField internal val player: Player, now: Long) : Disposable {
    @JvmField internal val pages: MutableMap<String, Page> = object : LinkedHashMap<String, Page>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Page>?): Boolean = size > MAX_PAGES
    }
    @JvmField internal val history: Deque<String> = ArrayDeque()
    @JvmField internal var current: String? = null
    @JvmField internal var status: RichText? = null
    @JvmField internal var lastActive = now
    @JvmField internal var closed = false
    @JvmField internal var suspended = false
    @JvmField internal var outputGeneration = 0L
    @JvmField internal var rendering = false
    @JvmField internal var renders = 0
    @JvmField internal var acting = 0
    @JvmField internal var navigation = 0
    @JvmField internal var pending: Pending<*>? = null
    @JvmField internal var redraw: TaskHandle? = null
    @JvmField internal var invokingPermission = ""
    fun player(): Player = player
    fun isClosed(): Boolean = closed
    fun panelId(): String = pages[current]?.panel?.id() ?: ""
    fun page(): Int = pages[current]?.index ?: 0
    fun page(index: Int) = panels.page(this, index)
    fun open(panel: ChatPanel) = panels.navigate(this, panel)
    fun back(): Boolean = panels.back(this)
    fun refresh() = panels.scheduleRedraw(this)
    fun status(value: RichText) { status = value; panels.scheduleRedraw(this) }
    fun status(value: String) = status(RichText.plain(value))
    fun inputting(): Boolean = pending != null
    fun <T> input(input: ChatPanelInput<T>, accepted: Consumer<T>) = panels.input(this, input, accepted, null)
    fun <T> input(input: ChatPanelInput<T>, accepted: Consumer<T>, cancelled: Runnable) = panels.input(this, input, accepted, cancelled)
    fun output(action: Runnable) = panels.output(this, action)
    override fun dispose() = panels.close(this, true)
    internal class Page(@JvmField internal val key: String, @JvmField internal var panel: ChatPanel) {
        @JvmField internal var index = 0
        @JvmField internal var retired = false
    }
    internal class Pending<T>(@JvmField internal val input: ChatPanelInput<T>, @JvmField internal val accepted: Consumer<T>,
        @JvmField internal val cancelled: Runnable?, @JvmField internal val origin: String, @JvmField internal val permission: String) {
        @JvmField internal var prompt: PromptSession<T>? = null
        @JvmField internal var sign = false
        @JvmField internal var timeout: TaskHandle? = null
    }
    companion object { internal const val MAX_PAGES = 64 }
}
