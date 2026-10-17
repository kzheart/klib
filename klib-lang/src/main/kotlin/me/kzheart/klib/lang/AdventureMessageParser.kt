package me.kzheart.klib.lang

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage

/** 将 Adventure 树压平为库拥有的不可变样式片段。 */
internal object AdventureMessageParser {
    private val miniMessage = MiniMessage.miniMessage()
    @JvmStatic fun parse(source: String): RichText = parseComponent(miniMessage.deserialize(convertLegacyCodes(source)))

    private fun parseComponent(component: Component): RichText {
        val segments = ArrayList<RichTextSegment>()
        append(component, EffectiveStyle(), segments)
        return RichText(segments)
    }

    private fun append(component: Component, inherited: EffectiveStyle, output: MutableList<RichTextSegment>) {
        val style = inherited.merge(component.style())
        val content = when (component) {
            is TextComponent -> component.content()
            is TranslatableComponent -> component.fallback() ?: component.key()
            is KeybindComponent -> component.keybind()
            else -> ""
        }
        if (content.isNotEmpty()) output.add(style.segment(content))
        if (component is TranslatableComponent) {
            for (argument in component.arguments()) {
                val value = argument.value()
                if (value is Component) append(value, style, output)
                else output.add(style.segment(value.toString()))
            }
        }
        for (child in component.children()) append(child, style, output)
    }

    private fun convertLegacyCodes(source: String): String = buildString(source.length + 32) {
        var index = 0
        while (index < source.length) {
            val current = source[index]
            if (current == '<' && !isEscaped(source, index)) {
                val tagEnd = findTagEnd(source, index + 1)
                if (tagEnd >= 0) {
                    append(source, index, tagEnd + 1)
                    index = tagEnd + 1
                    continue
                }
            }
            if (current == '&' && index + 1 < source.length) {
                if (source[index + 1] == '#' && index + 8 <= source.length) {
                    val hex = source.substring(index + 2, index + 8)
                    if (hex.matches(Regex("[0-9a-fA-F]{6}"))) {
                        append("<reset><#").append(hex).append('>')
                        index += 8
                        continue
                    }
                }
                val replacement = legacyTag(source[index + 1])
                if (replacement != null) {
                    append(replacement)
                    index += 2
                    continue
                }
            }
            append(current)
            index++
        }
    }

    private fun isEscaped(source: String, index: Int): Boolean {
        var cursor = index - 1
        while (cursor >= 0 && source[cursor] == '\\') cursor--
        return (index - 1 - cursor) % 2 == 1
    }

    private fun findTagEnd(source: String, start: Int): Int {
        var escaped = false
        for (index in start until source.length) {
            val current = source[index]
            if (current == '>' && !escaped) return index
            escaped = current == '\\' && !escaped
        }
        return -1
    }

    private fun legacyTag(rawCode: Char): String? {
        val code = rawCode.lowercaseChar()
        MessageColor.fromLegacy(code)?.let { return "<reset><$it>" }
        return when (code) {
            'k' -> "<obfuscated>"
            'l' -> "<bold>"
            'm' -> "<strikethrough>"
            'n' -> "<underlined>"
            'o' -> "<italic>"
            'r' -> "<reset>"
            else -> null
        }
    }

    private data class EffectiveStyle(
        val color: MessageColor? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underlined: Boolean = false,
        val strikethrough: Boolean = false,
        val obfuscated: Boolean = false,
        val hover: TextAction? = null,
        val click: TextAction? = null
    ) {
        fun segment(text: String): RichTextSegment = RichTextSegment(text, color, bold, italic, underlined,
            strikethrough, obfuscated, hover, click)
        fun merge(local: Style): EffectiveStyle = EffectiveStyle(
            local.color()?.let { MessageColor.rgb(it.value()) } ?: color,
            decoration(local, TextDecoration.BOLD, bold), decoration(local, TextDecoration.ITALIC, italic),
            decoration(local, TextDecoration.UNDERLINED, underlined),
            decoration(local, TextDecoration.STRIKETHROUGH, strikethrough),
            decoration(local, TextDecoration.OBFUSCATED, obfuscated),
            if (local.hoverEvent() != null) hover(local.hoverEvent()!!) else hover,
            if (local.clickEvent() != null) click(local.clickEvent()!!) else click)
    }

    private fun decoration(style: Style, decoration: TextDecoration, inherited: Boolean): Boolean =
        when (style.decoration(decoration)) {
            TextDecoration.State.NOT_SET -> inherited
            TextDecoration.State.TRUE -> true
            else -> false
        }

    private fun hover(event: HoverEvent<*>): TextAction? = when (event.action()) {
        HoverEvent.Action.SHOW_TEXT -> TextAction(TextAction.Type.HOVER_TEXT, parseComponent(event.value() as Component).plainText())
        HoverEvent.Action.SHOW_ITEM -> optionalHover {
            val item = event.value() as HoverEvent.ShowItem
            TextAction(TextAction.Type.HOVER_ITEM, item.item().asString() + if (item.count() > 1) " x${item.count()}" else "")
        }
        HoverEvent.Action.SHOW_ENTITY -> optionalHover {
            val entity = event.value() as HoverEvent.ShowEntity
            val name = entity.name()?.let { parseComponent(it).plainText() }.orEmpty()
            TextAction(TextAction.Type.HOVER_ENTITY, entity.type().asString() + if (name.isNotEmpty()) " ($name)" else "")
        }
        else -> null
    }

    private inline fun optionalHover(resolve: () -> TextAction): TextAction? = try { resolve() }
        catch (_: RuntimeException) { null } catch (_: LinkageError) { null }

    private fun click(event: ClickEvent): TextAction {
        val type = when (event.action()) {
            ClickEvent.Action.RUN_COMMAND -> TextAction.Type.RUN_COMMAND
            ClickEvent.Action.SUGGEST_COMMAND -> TextAction.Type.SUGGEST_COMMAND
            ClickEvent.Action.OPEN_URL -> TextAction.Type.OPEN_URL
            ClickEvent.Action.OPEN_FILE -> TextAction.Type.OPEN_FILE
            ClickEvent.Action.CHANGE_PAGE -> TextAction.Type.CHANGE_PAGE
            else -> TextAction.Type.COPY_TO_CLIPBOARD
        }
        return TextAction(type, event.value())
    }
}
