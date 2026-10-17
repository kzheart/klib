package me.kzheart.klib.ui.chat

import java.util.function.Consumer
import java.util.regex.Pattern
import me.kzheart.klib.lang.RichText

/** 路由按钮在点击时重新解析实时页面；预填和复制仅在客户端执行。 */
class ChatPanelButton private constructor(private val id: String?, private val label: RichText,
    private val hover: RichText?, private val value: RichText?, private val permission: String, private val kind: Kind,
    private val text: String, private val callback: Consumer<ChatPanelSession>?, private val reset: Consumer<ChatPanelSession>?) {
    internal enum class Kind { ACTION, COMMAND, SUGGEST, COPY }
    fun hover(value: RichText): ChatPanelButton = ChatPanelButton(id, label, value, this.value, permission, kind, text, callback, reset)
    fun value(value: RichText): ChatPanelButton = ChatPanelButton(id, label, hover, value, permission, kind, text, callback, reset)
    fun value(value: String): ChatPanelButton = value(RichText.plain(value))
    fun permission(required: String): ChatPanelButton = ChatPanelButton(id, label, hover, value, required, kind, text, callback, reset)
    fun reset(action: Consumer<ChatPanelSession>): ChatPanelButton {
        check(kind == Kind.ACTION) { "only action buttons can be reset" }
        return ChatPanelButton(id, label, hover, value, permission, kind, text, callback, action)
    }
    fun id(): String? = id
    fun label(): RichText = label
    fun hover(): RichText? = hover
    fun value(): RichText? = value
    fun permission(): String = permission
    @JvmName("kind") internal fun kind(): Kind = kind
    @JvmName("text") internal fun text(): String = text
    @JvmName("routed") internal fun routed(): Boolean = kind == Kind.ACTION || kind == Kind.COMMAND
    @JvmName("resetAction") internal fun resetAction(): Consumer<ChatPanelSession>? = reset
    @JvmName("invoke") internal fun invoke(session: ChatPanelSession) {
        if (kind == Kind.ACTION) callback!!.accept(session)
        else if (kind == Kind.COMMAND) session.player().performCommand(text.removePrefix("/"))
    }
    companion object {
        @JvmField internal val ID: Pattern = Pattern.compile("[A-Za-z0-9_.:+-]{1,64}")
        @JvmStatic fun action(id: String, label: RichText, action: Consumer<ChatPanelSession>): ChatPanelButton =
            ChatPanelButton(checkId(id), label, null, null, "", Kind.ACTION, "", action, null)
        @JvmStatic fun command(label: RichText, command: String): ChatPanelButton =
            ChatPanelButton("cmd." + Integer.toHexString(command.hashCode()), label, null, null, "", Kind.COMMAND, command, null, null)
        @JvmStatic fun suggest(label: RichText, value: String): ChatPanelButton =
            ChatPanelButton(null, label, null, null, "", Kind.SUGGEST, value, null, null)
        @JvmStatic fun copy(label: RichText, value: String): ChatPanelButton =
            ChatPanelButton(null, label, null, null, "", Kind.COPY, value, null, null)
        @JvmStatic @JvmName("checkId") internal fun checkId(id: String): String { require(ID.matcher(id).matches()) { "invalid chat panel id: $id" }; return id }
    }
}
