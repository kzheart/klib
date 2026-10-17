package me.kzheart.klib.lang

import java.util.Locale

/** YAML 库将配置节解码为映射后使用的适配入口。 */
class YamlMessageCatalogLoader private constructor() {
    companion object {
        @JvmStatic fun fromDecodedMap(decodedSection: Map<String, *>): MessageCatalog {
            val flattened = LinkedHashMap<String, Any?>()
            flatten("", decodedSection, flattened)
            return MapMessageCatalog(flattened)
        }

        private fun flatten(prefix: String, source: Map<String, *>, target: MutableMap<String, Any?>) {
            for ((name, value) in source) {
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                if (value is Map<*, *>) {
                    val mapping = stringKeyedMap(key, value)
                    // 只有 text 为字符串才是富文本，否则继续作为普通节展开。
                    if (mapping["text"] is String) putUnique(target, key, rich(key, mapping))
                    else flatten(key, mapping, target)
                } else putUnique(target, key, value)
            }
        }

        private fun rich(key: String, value: Map<String, *>): RichText {
            val text = value["text"]
            require(text is String) { "Rich catalog entry requires string text (a section with a string 'text' child is treated as rich text): $key" }
            val color = value["color"]?.let {
                MessageColor.fromTag(it.toString()) ?: throw IllegalArgumentException("Unknown rich catalog color: $it")
            }
            val hover = if (value.containsKey("hover")) TextAction(TextAction.Type.HOVER_TEXT, value["hover"].toString()) else null
            return RichText(listOf(RichTextSegment(text, color, value["bold"] == true, value["italic"] == true,
                value["underlined"] == true, value["strikethrough"] == true, value["obfuscated"] == true,
                hover, click(key, value["click"]))))
        }

        private fun click(key: String, raw: Any?): TextAction? {
            if (raw == null) return null
            require(raw is Map<*, *>) { "Rich catalog click must be a mapping: $key" }
            val click = stringKeyedMap("$key.click", raw)
            val type = click["type"]
            val value = click["value"]
            require(type != null && value != null) { "Rich catalog click requires type and value: $key" }
            val action = try {
                TextAction.Type.valueOf(type.toString().trim().uppercase(Locale.ROOT))
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown rich catalog click type: $type", failure)
            }
            require(action != TextAction.Type.HOVER_TEXT) { "HOVER_TEXT must use the hover field: $key" }
            return TextAction(action, value.toString())
        }

        private fun stringKeyedMap(path: String, source: Map<*, *>): Map<String, Any?> =
            source.entries.associateTo(LinkedHashMap()) { (key, value) ->
                require(key is String) { "Catalog key must be a string: $path" }
                key to value
            }

        private fun putUnique(target: MutableMap<String, Any?>, key: String, value: Any?) {
            require(target.put(key, value) == null) { "Duplicate YAML message key: $key" }
        }
    }
}
