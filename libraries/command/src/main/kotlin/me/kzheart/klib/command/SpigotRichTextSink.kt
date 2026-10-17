package me.kzheart.klib.command

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment
import me.kzheart.klib.lang.TextAction
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Optional interactive delivery is isolated behind one boundary with a legacy-text fallback. */
class SpigotRichTextSink(private val interactive: InteractiveDelivery) : RichTextSink {
    override fun send(sender: CommandSender, text: RichText) {
        if (sender !is Player || !interactive.trySend(sender, text)) sender.sendMessage(text.legacyText())
    }
    fun interface InteractiveDelivery { fun trySend(player: Player, text: RichText): Boolean }

    class RuntimeMethodCache(private val methodName: String, vararg parameterTypes: Class<*>) {
        private val parameterTypes = parameterTypes.copyOf()
        private val methods = ConcurrentHashMap<Class<*>, Optional<Method>>()
        private val resolutions = AtomicInteger()
        fun find(runtimeType: Class<*>): Method? = methods.computeIfAbsent(runtimeType) { resolve(it) }.orElse(null)
        fun resolutionCount(): Int = resolutions.get()
        private fun resolve(runtimeType: Class<*>): Optional<Method> {
            resolutions.incrementAndGet()
            return try { Optional.of(accessibleMethod(runtimeType)) }
            catch (ignored: NoSuchMethodException) { Optional.empty() }
            catch (ignored: SecurityException) { Optional.empty() }
            catch (ignored: LinkageError) { Optional.empty() }
        }
        private fun accessibleMethod(type: Class<*>): Method {
            if (Modifier.isPublic(type.modifiers)) {
                val method = type.getMethod(methodName, *parameterTypes)
                if (Modifier.isPublic(method.declaringClass.modifiers)) return method
            }
            for (contract in type.interfaces) try { return accessibleMethod(contract) } catch (ignored: NoSuchMethodException) { }
            return type.superclass?.let { accessibleMethod(it) } ?: throw NoSuchMethodException(methodName)
        }
    }

    private class ReflectionInteractiveDelivery : InteractiveDelivery {
        private val spigotMethods = RuntimeMethodCache("spigot")
        private val sendMessageMethods = api?.let { RuntimeMethodCache("sendMessage", ReflectArray.newInstance(it.baseComponent, 0).javaClass) }
        override fun trySend(player: Player, text: RichText): Boolean {
            val api = api ?: return false
            return try {
                val components = ReflectArray.newInstance(api.baseComponent, text.segments().size)
                text.segments().forEachIndexed { index, segment -> ReflectArray.set(components, index, api.component(segment)) }
                val spigot = spigotMethods.find(player.javaClass)?.invoke(player) ?: return false
                val send = sendMessageMethods?.find(spigot.javaClass) ?: return false
                send.invoke(spigot, components)
                true
            } catch (failure: ReflectiveOperationException) { false }
            catch (failure: LinkageError) { false }
            catch (failure: RuntimeException) { false }
        }
        companion object { private val api = BungeeApi.resolve() }
    }

    /** Resolves the optional host API once and owns every component-construction detail. */
    private class BungeeApi(
        val baseComponent: Class<*>, private val chatColor: Class<*>,
        private val hoverAction: Class<*>, private val clickAction: Class<*>,
        private val textConstructor: Constructor<*>, private val hoverConstructor: Constructor<*>,
        private val clickConstructor: Constructor<*>, private val setColor: Method,
        private val styles: List<Pair<Method, (RichTextSegment) -> Boolean>>,
        private val setHoverEvent: Method, private val setClickEvent: Method, private val chatColorOf: Method?,
    ) {
        fun component(segment: RichTextSegment): Any {
            val component = textConstructor.newInstance(segment.text())
            segment.color()?.let { color -> setColor.invoke(component, if (!color.isLegacy() && chatColorOf != null)
                chatColorOf.invoke(null, color.toString()) else namedColor(chatColor, color)) }
            for ((setter, enabled) in styles) if (enabled(segment)) setter.invoke(component, true)
            segment.hover()?.let { hover ->
                val contents = ReflectArray.newInstance(baseComponent, 1)
                ReflectArray.set(contents, 0, textConstructor.newInstance(hover.value()))
                setHoverEvent.invoke(component, hoverConstructor.newInstance(enumValue(hoverAction, "SHOW_TEXT"), contents))
            }
            segment.click()?.let { click ->
                setClickEvent.invoke(component, clickConstructor.newInstance(enumValue(clickAction, clickActionName(click)), click.value()))
            }
            return component
        }
        companion object {
            fun resolve(): BungeeApi? = try {
                val base = Class.forName("net.md_5.bungee.api.chat.BaseComponent")
                val text = Class.forName("net.md_5.bungee.api.chat.TextComponent")
                val color = Class.forName("net.md_5.bungee.api.ChatColor")
                val hover = Class.forName("net.md_5.bungee.api.chat.HoverEvent")
                val hoverAction = Class.forName("net.md_5.bungee.api.chat.HoverEvent\$Action")
                val click = Class.forName("net.md_5.bungee.api.chat.ClickEvent")
                val clickAction = Class.forName("net.md_5.bungee.api.chat.ClickEvent\$Action")
                val componentArray = ReflectArray.newInstance(base, 0).javaClass
                val colorOf = try { color.getMethod("of", String::class.java) } catch (ignored: NoSuchMethodException) { null }
                val styleReaders = listOf<Pair<String, (RichTextSegment) -> Boolean>>(
                    "setBold" to { it.bold() }, "setItalic" to { it.italic() }, "setUnderlined" to { it.underlined() },
                    "setStrikethrough" to { it.strikethrough() }, "setObfuscated" to { it.obfuscated() })
                BungeeApi(base, color, hoverAction, clickAction, text.getConstructor(String::class.java),
                    hover.getConstructor(hoverAction, componentArray), click.getConstructor(clickAction, String::class.java),
                    base.getMethod("setColor", color), styleReaders.map { (name, read) -> base.getMethod(name, Boolean::class.javaObjectType) to read },
                    base.getMethod("setHoverEvent", hover), base.getMethod("setClickEvent", click), colorOf)
            } catch (ignored: ReflectiveOperationException) { null }
            catch (ignored: LinkageError) { null }
            catch (ignored: RuntimeException) { null }
            private fun enumValue(type: Class<*>, name: String): Any = type.enumConstants.firstOrNull { (it as Enum<*>).name == name }
                ?: throw IllegalArgumentException("No enum constant ${type.name}.$name")
            private fun clickActionName(action: TextAction): String = when (action.type()) {
                TextAction.Type.RUN_COMMAND -> "RUN_COMMAND"
                TextAction.Type.SUGGEST_COMMAND -> "SUGGEST_COMMAND"
                TextAction.Type.OPEN_URL -> "OPEN_URL"
                TextAction.Type.COPY_TO_CLIPBOARD -> "COPY_TO_CLIPBOARD"
                TextAction.Type.CHANGE_PAGE -> "CHANGE_PAGE"
                TextAction.Type.OPEN_FILE -> "OPEN_FILE"
                else -> throw IllegalArgumentException("Unsupported click action: ${action.type()}")
            }
        }
    }
    companion object {
        @JvmField val INSTANCE = SpigotRichTextSink(ReflectionInteractiveDelivery())
        @JvmStatic @Throws(ReflectiveOperationException::class)
        fun namedColor(type: Class<*>, color: MessageColor): Any = type.getField(color.nearestLegacy().toString().uppercase(Locale.ROOT)).get(null)
    }
}
