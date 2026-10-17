package me.kzheart.klib.ui.chat

import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate
import me.kzheart.klib.lang.RichText
import org.bukkit.entity.Player

/** 页面函数在每次显示时读取实时状态，不重放旧模型。 */
class ChatPanel private constructor(builder: Builder) {
    private val id = builder.id
    private val title = builder.title
    private val permission = builder.permission
    private val guard = builder.guard
    private val perLine = builder.perLine
    private val once = builder.once
    private val content = builder.content
    private val save = builder.save
    private val cancel = builder.cancel
    private val errors = builder.errors
    fun id(): String = id
    fun title(): RichText = title
    fun permission(): String = permission
    @JvmName("perLine") internal fun perLine(): Int = perLine
    @JvmName("once") internal fun once(): Boolean = once
    @JvmName("content") internal fun content(): Consumer<ChatPanelView> = content
    @JvmName("allowed") internal fun allowed(player: Player): Boolean = (permission.isEmpty() || player.hasPermission(permission)) && guard.test(player)
    @JvmName("hasHotkeys") internal fun hasHotkeys(): Boolean = save != null
    @JvmName("hotkey") internal fun hotkey(session: ChatPanelSession, cancelled: Boolean) { (if (cancelled) cancel else save)!!.accept(session) }
    @JvmName("errorHandler") internal fun errorHandler(): BiConsumer<ChatPanelSession, Throwable>? = errors
    class Builder internal constructor(id: String, internal val title: RichText) {
        internal val id = ChatPanelButton.checkId(id)
        internal var permission = ""
        internal var guard = Predicate<Player> { true }
        internal var perLine = 4
        internal var once = false
        internal var content = Consumer<ChatPanelView> { }
        internal var save: Consumer<ChatPanelSession>? = null
        internal var cancel: Consumer<ChatPanelSession>? = null
        internal var errors: BiConsumer<ChatPanelSession, Throwable>? = null
        fun content(value: Consumer<ChatPanelView>): Builder = apply { content = value }
        fun permission(value: String): Builder = apply { permission = value }
        fun guard(value: Predicate<Player>): Builder = apply { guard = value }
        fun perLine(value: Int): Builder = apply { require(value in 1..12) { "perLine must be 1..12" }; perLine = value }
        @JvmName("once") internal fun once(): Builder = apply { once = true }
        fun hotkeys(onSave: Consumer<ChatPanelSession>, onCancel: Consumer<ChatPanelSession>): Builder = apply { save = onSave; cancel = onCancel }
        fun errorHandler(handler: BiConsumer<ChatPanelSession, Throwable>): Builder = apply { errors = handler }
        fun build(): ChatPanel = ChatPanel(this)
    }
    companion object { @JvmStatic fun builder(id: String, title: RichText): Builder = Builder(id, title) }
}
