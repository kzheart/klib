package me.kzheart.klib.lang

import java.util.Collections

/** 占位符映射的可变参数工厂。 */
class Placeholders private constructor() {
    companion object {
        @JvmStatic fun of(vararg pairs: Any?): Map<String, Any?> {
            require(pairs.size % 2 == 0) { "Placeholders.of requires an even number of arguments (key, value, ...)" }
            if (pairs.isEmpty()) return emptyMap()
            val values = LinkedHashMap<String, Any?>()
            for (index in pairs.indices step 2) {
                val key = pairs[index]
                require(key is String) { "Placeholder key at position $index must be a string" }
                values[key] = pairs[index + 1]
            }
            return Collections.unmodifiableMap(values)
        }
    }
}
