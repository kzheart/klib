package me.kzheart.klib.ui.chat

import java.util.Collections
import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment
import me.kzheart.klib.lang.TextAction
import org.bukkit.entity.Player

/** 每次显示创建一个构建器，集中处理分组、宽度、权限和按钮路由。 */
class ChatPanelView internal constructor(private val session: ChatPanelSession, private val pageKey: String,
    private val command: String, private val options: ChatPanelOptions, private val perLine: Int) {
    private var subtitle: RichText? = null
    private var block: MutableList<RichText>? = null
    @JvmField internal val blocks: MutableList<MutableList<RichText>> = ArrayList()
    @JvmField internal val footer: MutableList<ChatPanelButton> = ArrayList()
    @JvmField internal val routes: MutableMap<String, Route> = HashMap()
    fun player(): Player = session.player()
    fun session(): ChatPanelSession = session
    fun subtitle(value: RichText): ChatPanelView = apply { subtitle = value }
    fun group(title: String): ChatPanelView = group(RichText(listOf(
        RichTextSegment(title, MessageColor.DARK_GRAY, false, null, null),
        RichTextSegment(" ···", MessageColor.DARK_GRAY, false, null, null))))
    fun group(title: RichText): ChatPanelView = apply { block = ArrayList<RichText>().also { blocks.add(it) }; add(title.segments()) }
    fun text(text: RichText): ChatPanelView = apply { add(text.segments()) }
    fun blank(): ChatPanelView = apply { current().add(RichText.plain("")) }
    fun line(text: RichText, vararg buttons: ChatPanelButton): ChatPanelView = apply {
        val rows = ChatPanelLayout.wrap(text, options.width() - 8)
        rows.dropLast(1).forEach { add(it.segments()) }
        var line = ArrayList(rows.last().segments())
        for (button in buttons) {
            if (!register(button)) continue
            val rendered = render(button)
            if (line.isNotEmpty() && overWidth(line, rendered)) { add(line); line = ArrayList() }
            if (line.isNotEmpty()) line.add(RichTextSegment.plain(" "))
            line.addAll(rendered)
        }
        add(line)
    }
    fun buttons(vararg buttons: ChatPanelButton): ChatPanelView = buttons(perLine, buttons.asList())
    fun buttons(buttons: List<ChatPanelButton>): ChatPanelView = buttons(perLine, buttons)
    fun buttons(perLine: Int, vararg buttons: ChatPanelButton): ChatPanelView = buttons(perLine, buttons.asList())
    fun buttons(perLine: Int, buttons: List<ChatPanelButton>): ChatPanelView = apply {
        require(perLine > 0) { "perLine must be positive" }
        var line = ArrayList<RichTextSegment>()
        var count = 0
        for (button in buttons) {
            if (!register(button)) continue
            val rendered = render(button)
            if (line.isNotEmpty() && (count >= perLine || overWidth(line, rendered))) { add(line); line = ArrayList(); count = 0 }
            if (line.isNotEmpty()) line.add(RichTextSegment.plain(" "))
            line.addAll(rendered); count++
        }
        if (line.isNotEmpty()) add(line)
    }
    fun footer(vararg buttons: ChatPanelButton): ChatPanelView = apply { buttons.filter(::register).forEach { footer.add(it) } }
    @JvmName("subtitle") internal fun subtitle(): RichText? = subtitle
    private fun overWidth(left: List<RichTextSegment>, right: List<RichTextSegment>): Boolean =
        ChatPanelLayout.width(left) + 4 + ChatPanelLayout.width(right) > options.width() - 8
    private fun add(segments: List<RichTextSegment>) { ChatPanelLayout.wrap(RichText(segments), options.width() - 8).forEach { current().add(indent(it.segments())) } }
    private fun current(): MutableList<RichText> = block ?: ArrayList<RichText>().also { block = it; blocks.add(it) }
    private fun register(button: ChatPanelButton): Boolean {
        if (button.routed()) {
            put(button.id()!!, Route(button, false))
            if (button.resetAction() != null) put(button.id() + "!r", Route(button, true))
        }
        return button.permission().isEmpty() || session.player().hasPermission(button.permission())
    }
    private fun put(id: String, route: Route) {
        val previous = routes.put(id, route)
        check(previous == null || previous.button.kind() == ChatPanelButton.Kind.COMMAND && previous.button.text() == route.button.text()) {
            "duplicate chat panel button id: $id"
        }
    }
    @JvmName("render") internal fun render(button: ChatPanelButton): List<RichTextSegment> {
        val click = when (button.kind()) {
            ChatPanelButton.Kind.COPY -> TextAction(TextAction.Type.COPY_TO_CLIPBOARD, button.text())
            ChatPanelButton.Kind.SUGGEST -> TextAction(TextAction.Type.SUGGEST_COMMAND, button.text())
            else -> run(button.id()!!)
        }
        val hover = hover(button.hover())
        return ArrayList<RichTextSegment>().apply {
            add(RichTextSegment("[", MessageColor.DARK_GRAY, false, hover, click))
            addAll(decorate(button.label().segments(), MessageColor.GOLD, hover, click))
            button.value()?.let { add(RichTextSegment(" ", null, false, hover, click)); addAll(decorate(it.segments(), MessageColor.WHITE, hover, click)) }
            if (button.resetAction() != null) {
                add(RichTextSegment.plain(" "))
                add(RichTextSegment("(R)", MessageColor.RED, false, hover(options.text(ChatPanelText.RESET)), run(button.id() + "!r")))
            }
            add(RichTextSegment("]", MessageColor.DARK_GRAY, false, hover, click))
        }
    }
    @JvmName("run") internal fun run(action: String): TextAction = TextAction(TextAction.Type.RUN_COMMAND, "$command $pageKey $action")
    @JvmName("blocks") internal fun blocks(): List<List<RichText>> = Collections.unmodifiableList(blocks)
    internal class Route(@JvmField internal val button: ChatPanelButton, @JvmField internal val reset: Boolean)
    companion object {
        internal const val INDENT = "  "
        @JvmStatic @JvmName("hover") internal fun hover(text: RichText?): TextAction? = text?.let { TextAction(TextAction.Type.HOVER_TEXT, it.legacyText()) }
        @JvmStatic @JvmName("decorate") internal fun decorate(segments: List<RichTextSegment>, fallback: MessageColor?, hover: TextAction?, click: TextAction?): List<RichTextSegment> =
            segments.map { RichTextSegment(it.text(), it.color() ?: fallback, it.bold(), it.italic(), it.underlined(),
                it.strikethrough(), it.obfuscated(), hover ?: it.hover(), click ?: it.click()) }
        @JvmStatic @JvmName("indent") internal fun indent(segments: List<RichTextSegment>): RichText = RichText(listOf(RichTextSegment.plain(INDENT)) + segments)
    }
}
