package me.kzheart.klib.lang

import org.bukkit.entity.Player
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.util.Locale

internal class ReflectionBukkitComponentSender private constructor() : BukkitComponentSender {
    override fun send(player: Player, message: RichText, actionBar: Boolean): Boolean {
        val bungee = handles ?: return false
        return available {
            val components = ReflectArray.newInstance(bungee.baseComponent, message.segments().size)
            message.segments().forEachIndexed { index, segment ->
                val component = bungee.textConstructor.newInstance(segment.text())
                segment.color()?.let { color ->
                    val resolved = if (!color.isLegacy() && bungee.chatColorOfMethod != null)
                        bungee.chatColorOfMethod.invoke(null, color.toString()) else namedColor(bungee.chatColor, color)
                    bungee.setColorMethod.invoke(component, resolved)
                }
                listOf(segment.bold(), segment.italic(), segment.underlined(), segment.strikethrough(), segment.obfuscated())
                    .forEachIndexed { style, enabled -> if (enabled) bungee.decorations[style].invoke(component, true) }
                segment.hover()?.let { hover ->
                    val contents = ReflectArray.newInstance(bungee.baseComponent, 1)
                    ReflectArray.set(contents, 0, bungee.textConstructor.newInstance(hover.value()))
                    val event = bungee.hoverEventConstructor.newInstance(enumValue(bungee.hoverAction, "SHOW_TEXT"), contents)
                    bungee.setHoverEventMethod.invoke(component, event)
                }
                segment.click()?.let { click ->
                    require(click.type() in CLICK_TYPES) { "Unsupported click action: ${click.type()}" }
                    val event = bungee.clickEventConstructor.newInstance(enumValue(bungee.clickAction, click.type().name), click.value())
                    bungee.setClickEventMethod.invoke(component, event)
                }
                ReflectArray.set(components, index, component)
            }
            val spigot = bungee.spigotMethod.invoke(player) ?: return@available false
            if (actionBar) bungee.sendActionBarMethod.invoke(spigot, bungee.actionBarType, components)
            else bungee.sendChatMethod.invoke(spigot, components)
            true
        } ?: false
    }

    private class Bungee {
        val baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent")
        private val textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent")
        val chatColor = Class.forName("net.md_5.bungee.api.ChatColor")
        private val hoverEvent = Class.forName("net.md_5.bungee.api.chat.HoverEvent")
        val hoverAction = Class.forName("net.md_5.bungee.api.chat.HoverEvent\$Action")
        private val clickEvent = Class.forName("net.md_5.bungee.api.chat.ClickEvent")
        val clickAction = Class.forName("net.md_5.bungee.api.chat.ClickEvent\$Action")
        private val messageType = Class.forName("net.md_5.bungee.api.ChatMessageType")
        private val componentArray = ReflectArray.newInstance(baseComponent, 0).javaClass
        val textConstructor = textComponent.getConstructor(String::class.java)
        val hoverEventConstructor = hoverEvent.getConstructor(hoverAction, componentArray)
        val clickEventConstructor = clickEvent.getConstructor(clickAction, String::class.java)
        val setColorMethod = baseComponent.getMethod("setColor", chatColor)
        val decorations = listOf("setBold", "setItalic", "setUnderlined", "setStrikethrough", "setObfuscated")
            .map { baseComponent.getMethod(it, Boolean::class.javaObjectType) }
        val setHoverEventMethod = baseComponent.getMethod("setHoverEvent", hoverEvent)
        val setClickEventMethod = baseComponent.getMethod("setClickEvent", clickEvent)
        val chatColorOfMethod: Method? = try { chatColor.getMethod("of", String::class.java) }
            catch (_: NoSuchMethodException) { null }
        val spigotMethod = Player::class.java.getMethod("spigot")
        val sendChatMethod = spigotMethod.returnType.getMethod("sendMessage", componentArray)
        val sendActionBarMethod = spigotMethod.returnType.getMethod("sendMessage", messageType, componentArray)
        val actionBarType = enumValue(messageType, "ACTION_BAR")
    }

    companion object {
        @JvmField val INSTANCE = ReflectionBukkitComponentSender()
        private val handles = available { Bungee() }
        private val CLICK_TYPES = setOf(TextAction.Type.RUN_COMMAND, TextAction.Type.SUGGEST_COMMAND,
            TextAction.Type.OPEN_URL, TextAction.Type.COPY_TO_CLIPBOARD, TextAction.Type.CHANGE_PAGE, TextAction.Type.OPEN_FILE)
        @JvmStatic @Throws(ReflectiveOperationException::class)
        fun namedColor(type: Class<*>, color: MessageColor): Any =
            type.getField(color.nearestLegacy().toString().uppercase(Locale.ROOT)).get(null)
        private fun enumValue(type: Class<*>, value: String): Any =
            type.enumConstants.firstOrNull { (it as Enum<*>).name == value }
                ?: throw IllegalArgumentException("No enum constant ${type.name}.$value")
        private inline fun <T> available(resolve: () -> T): T? = try { resolve() }
            catch (_: ReflectiveOperationException) { null }
            catch (_: LinkageError) { null }
            catch (_: RuntimeException) { null }
    }
}
