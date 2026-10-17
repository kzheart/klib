package me.kzheart.klib.lang

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** 固定执行消息键、占位符、前缀、格式化、可选 PAPI 和路由阶段的管线。 */
class MessagePipeline(
    private val catalog: MessageCatalog,
    @Volatile private var prefix: String,
    private val placeholderApi: PlaceholderApi?,
    private val router: MessageRouter
) {
    private val warnedMissingKeys = ConcurrentHashMap.newKeySet<String>()

    fun send(recipient: MessageRecipient, key: String, placeholders: Map<String, *>): RichText =
        sendInternal(recipient, null, key, placeholders)
    fun send(recipient: MessageRecipient, route: MessageRoute, key: String, placeholders: Map<String, *>): RichText =
        sendInternal(recipient, route, key, placeholders)
    fun send(recipient: MessageRecipient, key: String): RichText = send(recipient, key, emptyMap<String, Any>())
    fun send(recipient: MessageRecipient, route: MessageRoute, key: String): RichText =
        send(recipient, route, key, emptyMap<String, Any>())

    private fun sendInternal(recipient: MessageRecipient, route: MessageRoute?, key: String,
                             placeholders: Map<String, *>): RichText {
        val resolved = resolveMessage(recipient, key, placeholders)
        router.route(recipient, route ?: resolved.route, resolved.message)
        return resolved.message
    }

    /** 解析消息但不进行路由，适用于自行负责投递的适配器。 */
    fun render(recipient: MessageRecipient, key: String, placeholders: Map<String, *>): RichText =
        resolveMessage(recipient, key, placeholders).message
    fun render(recipient: MessageRecipient, key: String): RichText = render(recipient, key, emptyMap<String, Any>())

    private fun resolveMessage(recipient: MessageRecipient, key: String, placeholders: Map<String, *>): ResolvedMessage {
        val template = resolveTemplate(key)
        // PAPI 仅处理目录模板。命名值最后以字面文本插入，不能注入格式、路由或 PAPI 表达式。
        val expanded = placeholderApi?.let { api ->
            RichText(template.message.segments().map { it.withText(api.expand(recipient, it.text())) })
        } ?: template.message
        val rendered = if (placeholders.isEmpty()) expanded
            else RichText(expanded.segments().map { it.withText(applyPlaceholders(it.text(), placeholders)) })
        return ResolvedMessage(template.route, rendered)
    }

    private fun resolveTemplate(key: String): ResolvedMessage {
        val entry = catalog.findAny(key).orElse(null) ?: return ResolvedMessage(MessageRoute.CHAT, missingKeyText(key))
        if (entry is RichText) {
            val routed = extractRoute(entry)
            return ResolvedMessage(routed.route, replacePrefix(routed.message))
        }
        // 保留旧目录对空行的拼接行为：只在已有输出后添加分隔符。
        val source = if (entry is List<*>) buildString {
            for (line in entry) {
                if (isNotEmpty()) append('\n')
                append(line as String)
            }
        } else entry.toString()
        val routed = extractRoute(source)
        return ResolvedMessage(routed.route, replacePrefix(parse(routed.text)))
    }

    private fun missingKeyText(key: String): RichText {
        if (warnedMissingKeys.add(key)) LOGGER.warning("Missing language key: $key")
        return RichText(listOf(RichTextSegment("[missing:$key]", MessageColor.RED, false, null, null)))
    }

    private fun replacePrefix(source: RichText): RichText {
        val parsedPrefix = parse(prefix)
        val result = ArrayList<RichTextSegment>()
        for (segment in source.segments()) {
            val text = segment.text()
            var start = 0
            while (true) {
                val match = text.indexOf("{prefix}", start)
                if (match < 0) break
                if (match > start) result.add(segment.withText(text.substring(start, match)))
                result.addAll(parsedPrefix.segments())
                start = match + "{prefix}".length
            }
            if (start < text.length) result.add(segment.withText(text.substring(start)))
        }
        return RichText(result)
    }

    @JvmName("updatePrefix") internal fun updatePrefix(replacement: String) { prefix = replacement }

    private data class ResolvedText(val route: MessageRoute, val text: String)
    private data class ResolvedMessage(val route: MessageRoute, val message: RichText)

    companion object {
        private val LOGGER = Logger.getLogger(MessagePipeline::class.java.name)
        private val ROUTES = listOf("actionbar:" to MessageRoute.ACTION_BAR, "title:" to MessageRoute.TITLE,
            "bossbar:" to MessageRoute.BOSS_BAR, "chat:" to MessageRoute.CHAT)
        private fun parse(source: String): RichText = AdventureMessageParser.parse(source)

        /** 单遍替换；插入的值不会再次参与扫描。 */
        private fun applyPlaceholders(source: String, placeholders: Map<String, *>): String {
            if ('{' !in source) return source
            return buildString(source.length) {
                var index = 0
                while (index < source.length) {
                    if (source[index] == '{') {
                        val close = source.indexOf('}', index + 1)
                        if (close > index) {
                            val name = source.substring(index + 1, close)
                            if (name != "prefix" && placeholders.containsKey(name)) {
                                append(placeholders[name].toString())
                                index = close + 1
                                continue
                            }
                        }
                    }
                    append(source[index++])
                }
            }
        }

        private fun extractRoute(source: String): ResolvedText {
            val lower = source.lowercase(Locale.ROOT)
            val (prefix, route) = ROUTES.firstOrNull { lower.startsWith(it.first) }
                ?: return ResolvedText(MessageRoute.CHAT, source)
            val start = prefix.length + if (source.getOrNull(prefix.length) == ' ') 1 else 0
            return ResolvedText(route, source.substring(start))
        }

        private fun extractRoute(source: RichText): ResolvedMessage {
            val plain = source.plainText()
            val routed = extractRoute(plain)
            var remaining = plain.length - routed.text.length
            if (remaining == 0) return ResolvedMessage(routed.route, source)
            val stripped = ArrayList<RichTextSegment>()
            for (segment in source.segments()) {
                if (remaining >= segment.text().length) remaining -= segment.text().length
                else {
                    stripped.add(segment.withText(segment.text().substring(remaining)))
                    remaining = 0
                }
            }
            return ResolvedMessage(routed.route, RichText(stripped))
        }
    }
}
