package me.kzheart.klib.command

import java.util.Optional
import java.util.function.IntFunction
import me.kzheart.klib.command.api.CommandHelpStyle
import me.kzheart.klib.command.api.CommandSpec
import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.MessagePipeline
import me.kzheart.klib.lang.MessageRecipient
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment
import me.kzheart.klib.lang.TextAction
import org.bukkit.command.CommandSender

/** Layout templates remain separate from command navigation, access checks and click behavior. */
class HelpRenderer(private val messages: CommandMessages) {
    fun render(spec: CommandSpec, sender: CommandSender, requestedPage: Int, pageSize: Int): HelpPage {
        val typed = spec as? CommandSpecImpl ?: throw IllegalArgumentException("命令规格必须由 Klib 创建")
        require(pageSize > 0) { "pageSize must be positive" }
        val entries = arrayListOf<HelpEntry>()
        if (CommandDispatcher.isAccessible(sender, typed.root())) collect(typed.root(), sender, "/${typed.name()}", hashSetOf(), entries)
        return renderEntries(sender, typed.name(), description(typed.root(), sender), entries, requestedPage, pageSize, style(typed.root()),
            IntFunction { "/${typed.name()} help $it" })
    }
    fun renderEntries(sender: CommandSender, command: String, summary: String, entries: List<CommandHelpEntry>,
        page: Int, style: CommandHelpStyle, navigation: IntFunction<String>): HelpPage =
        renderEntries(sender, command, summary, entries.map { HelpEntry(it.usage(), it.description().ifEmpty { "暂无说明" },
            suggestion(it.usage()), it.category(), it.hover()) }, page, style.pageSize(), style, navigation)

    private fun renderEntries(sender: CommandSender, command: String, summary: String, entries: List<HelpEntry>, requestedPage: Int,
        pageSize: Int, style: CommandHelpStyle, navigation: IntFunction<String>): HelpPage {
        val pages = maxOf(1, (entries.size + pageSize - 1) / pageSize)
        val page = requestedPage.coerceIn(1, pages)
        val from = minOf((page - 1) * pageSize, entries.size)
        val values = MessagePlaceholders.of("command", command, "page", page, "pages", pages, "count", entries.size, "summary", summary)
        val output = arrayListOf<RichTextSegment>()
        repeat(style.clearLines()) { output.add(RichTextSegment.plain("\n")) }
        output.addAll((style.header()?.let { template(it, sender, values) }
            ?: messages.resolve(sender, CommandMessageKeys.HELP_HEADER, values)).segments())
        var category = ""
        for (index in from until minOf(from + pageSize, entries.size)) {
            val entry = entries[index]
            if (entry.category.isNotEmpty() && entry.category != category) {
                category = entry.category
                output.add(RichTextSegment.plain("\n"))
                output.addAll(template(style.group(), sender, MessagePlaceholders.of("category", category)).segments())
            }
            output.add(RichTextSegment.plain("\n"))
            appendEntry(output, sender, style, style.entry(), entry, index + 1)
            if (style.description().isNotEmpty()) {
                output.add(RichTextSegment.plain("\n"))
                appendEntry(output, sender, style, style.description(), entry, index + 1)
            }
        }
        if (entries.isEmpty()) output.addAll(template("\n<gray>没有可使用的命令", sender, values).segments())
        if (pages > 1) {
            output.add(RichTextSegment.plain("\n"))
            if (page > 1) output.addAll(navigation(style.previous(), CommandMessageKeys.HELP_PREVIOUS, navigation.apply(page - 1), page - 1, sender, values))
            if (page > 1 && page < pages) output.add(RichTextSegment.plain("  "))
            if (page < pages) output.addAll(navigation(style.next(), CommandMessageKeys.HELP_NEXT, navigation.apply(page + 1), page + 1, sender, values))
        }
        if (style.footer().isNotEmpty()) {
            output.add(RichTextSegment.plain("\n"))
            output.addAll(template(style.footer(), sender, values).segments())
        }
        return HelpPage(page, pages, RichText(output))
    }
    @JvmName("usage") internal fun usage(node: CommandNode, sender: CommandSender, prefix: String, maxLines: Int): RichText? {
        val entries = arrayListOf<HelpEntry>()
        collect(node, sender, prefix, hashSetOf(), entries)
        if (entries.isEmpty() || entries.size > maxLines) return null
        val style = style(node)
        val output = arrayListOf<RichTextSegment>()
        for (entry in entries) {
            if (output.isNotEmpty()) output.add(RichTextSegment.plain("\n"))
            appendEntry(output, sender, style, style.usage(), entry, 1)
        }
        return RichText(output)
    }
    private fun appendEntry(output: MutableList<RichTextSegment>, sender: CommandSender, style: CommandHelpStyle,
        layout: String, entry: HelpEntry, index: Int) {
        val parsed = template(layout, sender, MessagePlaceholders.of("description", entry.description, "index", index))
        val line = arrayListOf<RichTextSegment>()
        for (part in parsed.segments()) {
            val text = part.text()
            var offset = 0
            while (true) {
                val found = text.indexOf("{usage}", offset)
                if (found < 0) break
                if (found > offset) line.add(withText(part, text.substring(offset, found)))
                appendStyledUsage(line, entry.usage, style)
                offset = found + 7
            }
            if (offset < text.length) line.add(withText(part, text.substring(offset)))
        }
        val hover = entry.hover.ifEmpty { template(style.hover(), sender,
            MessagePlaceholders.of("description", entry.description, "usage", entry.usage)).plainText() }
        CommandDispatcher.appendWithActions(output, RichText(line), TextAction(TextAction.Type.HOVER_TEXT, hover),
            TextAction(TextAction.Type.SUGGEST_COMMAND, entry.suggestion))
    }
    private fun navigation(layout: String?, key: String, command: String, page: Int, sender: CommandSender,
        values: Map<String, Any?>): List<RichTextSegment> {
        val label = layout?.let { template(it, sender, values) } ?: messages.resolve(sender, key, values)
        val output = arrayListOf<RichTextSegment>()
        CommandDispatcher.appendWithActions(output, label, TextAction(TextAction.Type.HOVER_TEXT,
            messages.resolve(sender, CommandMessageKeys.HELP_OPEN_PAGE, MessagePlaceholders.of("page", page)).plainText()),
            TextAction(TextAction.Type.RUN_COMMAND, command))
        return output
    }
    private fun collect(parent: CommandNode, sender: CommandSender, prefix: String, seen: MutableSet<String>, entries: MutableList<HelpEntry>) {
        for (child in parent.children) {
            if (!CommandDispatcher.isAccessible(sender, child)) continue
            val usage = "$prefix ${child.usageToken()}"
            if (child.handler != null && child.handlerAccess?.test(sender) != false && seen.add(usage)) {
                entries.add(HelpEntry(usage, description(child, sender), suggestion(usage)))
            }
            collect(child, sender, usage, seen, entries)
        }
    }
    private fun description(node: CommandNode, sender: CommandSender): String {
        for (current in generateSequence(node) { it.parent }) {
            val description = current.descriptionKey?.let { messages.resolve(sender, it, MessagePlaceholders.none()).plainText() } ?: current.description
            if (description.isNotEmpty()) return description
        }
        return "暂无说明"
    }
    private class HelpEntry(val usage: String, val description: String, val suggestion: String, val category: String = "", val hover: String = "")
    companion object {
        private val defaultStyle = CommandHelpStyle.preset(CommandHelpStyle.Preset.ELEGANT)
        @JvmStatic @JvmName("style") internal fun style(node: CommandNode): CommandHelpStyle {
            val provider = generateSequence(node) { it.parent }.mapNotNull { it.helpStyle }.firstOrNull() ?: return defaultStyle
            return java.util.Objects.requireNonNull(provider.get(), "help style provider returned null")
        }
        private fun appendStyledUsage(output: MutableList<RichTextSegment>, usage: String, style: CommandHelpStyle) {
            usage.split(' ').forEachIndexed { index, token ->
                if (index > 0) output.add(RichTextSegment.plain(" "))
                val rgb = if (index == 0) style.commandColor() else if (token.startsWith('<') || token.startsWith('[')) style.argumentColor() else style.literalColor()
                val color = MessageColor.rgb(rgb).let { if (it.nearestLegacy().rgb() == rgb) it.nearestLegacy() else it }
                output.add(RichTextSegment(token, color, false, null, null))
            }
        }
        private fun template(source: String, sender: CommandSender, values: Map<String, *>): RichText =
            MessagePipeline({ Optional.of(source) }, "", null) { _, _ -> }.render(MessageRecipient.commandSender(sender), "help-template", values)
        private fun withText(source: RichTextSegment, text: String): RichTextSegment = RichTextSegment(text, source.color(), source.bold(),
            source.italic(), source.underlined(), source.strikethrough(), source.obfuscated(), source.hover(), source.click())
        private fun suggestion(usage: String): String = usage.indexOfFirst { it == '<' || it == '[' }.let { if (it < 0) "$usage " else usage.substring(0, it) }
    }
}
