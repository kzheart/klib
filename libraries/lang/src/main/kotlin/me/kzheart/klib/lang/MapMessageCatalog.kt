package me.kzheart.klib.lang

import java.util.Collections
import java.util.Optional

/** 支持字符串、字符串列表和预构建富文本的不可变消息目录。 */
class MapMessageCatalog(entries: Map<String, *>) : MessageCatalog {
    private val entries: Map<String, Any> = Collections.unmodifiableMap(
        entries.mapValuesTo(LinkedHashMap()) { (key, value) -> normalize(key, value) })

    override fun find(key: String): Optional<String> = Optional.ofNullable(entries[key] as? String)
    @Suppress("UNCHECKED_CAST")
    override fun findLines(key: String): Optional<List<String>> =
        Optional.ofNullable(entries[key] as? List<String>)
    override fun findRich(key: String): Optional<RichText> = Optional.ofNullable(entries[key] as? RichText)
    override fun findAny(key: String): Optional<Any> = Optional.ofNullable(entries[key])

    private fun normalize(key: String, value: Any?): Any = when (value) {
        is String, is RichText -> value
        is List<*> -> Collections.unmodifiableList(value.map { line ->
            require(line is String) { "Catalog list must contain strings: $key" }
            line
        })
        else -> throw IllegalArgumentException("Unsupported catalog value for $key")
    }
}
