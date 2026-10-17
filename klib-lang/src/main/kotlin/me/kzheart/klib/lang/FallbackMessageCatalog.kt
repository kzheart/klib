package me.kzheart.klib.lang

import java.util.Optional

internal class FallbackMessageCatalog(
    private val primary: MessageCatalog,
    private val fallback: MessageCatalog
) : MessageCatalog {
    override fun find(key: String): Optional<String> = first(primary.find(key)) { fallback.find(key) }
    override fun findLines(key: String): Optional<List<String>> = first(primary.findLines(key)) { fallback.findLines(key) }
    override fun findRich(key: String): Optional<RichText> = first(primary.findRich(key)) { fallback.findRich(key) }
    // 拥有该键的消息目录决定其渲染方式，不能跨来源拼接不同表示。
    override fun findAny(key: String): Optional<Any> = first(primary.findAny(key)) { fallback.findAny(key) }
    private inline fun <T : Any> first(value: Optional<T>, alternative: () -> Optional<T>): Optional<T> =
        if (value.isPresent) value else alternative()
}
