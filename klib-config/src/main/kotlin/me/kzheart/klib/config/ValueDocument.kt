package me.kzheart.klib.config

import java.util.Collections
import java.util.IdentityHashMap
import java.util.function.BiFunction

/** Owns a parsed value tree without converting external formats through YAML. */
class ValueDocument private constructor(
    private val sourceName: String,
    values: Map<String, *>,
    private val sectionWriter: BiFunction<ConfigNode, Map<String, Any?>, String>,
) {
    private val values = map(copy(values, IdentityHashMap(), sourceName))
    fun sourceName(): String = sourceName
    fun root(): ConfigNode = ConfigNode(this, values, true, "", "")
    fun node(path: String): ConfigNode = if (path.isEmpty()) root() else root().node(path)
    @JvmName("sectionText") internal fun sectionText(node: ConfigNode, value: Any?): String =
        java.util.Objects.requireNonNull(sectionWriter.apply(node, map(snapshot(value))), "sectionWriter result")

    companion object {
        @JvmStatic fun of(sourceName: String, values: Map<String, *>,
            sectionWriter: BiFunction<String, @JvmSuppressWildcards Map<String, Any?>, String>): ValueDocument =
            ValueDocument(sourceName, values, BiFunction { node, snapshot -> sectionWriter.apply(node.path(), snapshot) })
        @JvmStatic fun ofWithNodeWriter(sourceName: String, values: Map<String, *>,
            sectionWriter: BiFunction<ConfigNode, @JvmSuppressWildcards Map<String, Any?>, String>): ValueDocument =
            ValueDocument(sourceName, values, sectionWriter)
        @JvmStatic @Suppress("UNCHECKED_CAST") @JvmName("map") internal fun map(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

        private fun copy(value: Any?, active: IdentityHashMap<Any, Boolean>, source: String): Any? {
            if (value !is Map<*, *> && value !is List<*>) return value
            if (active.put(value, true) != null) throw ConfigException("$source:<root>: recursive value containers are not supported")
            return try {
                if (value is Map<*, *>) value.entries.associateTo(linkedMapOf<String, Any?>()) { (key, child) ->
                    if (key !is String) throw ConfigException("$source:<root>: value mapping keys must be strings")
                    key to copy(child, active, source)
                } else (value as List<*>).mapTo(arrayListOf()) { copy(it, active, source) }
            } finally { active.remove(value) }
        }

        @JvmStatic @JvmName("snapshot") internal fun snapshot(value: Any?): Any? = when (value) {
            is Map<*, *> -> Collections.unmodifiableMap(map(value).mapValuesTo(linkedMapOf()) { snapshot(it.value) })
            is List<*> -> Collections.unmodifiableList(value.map { snapshot(it) })
            else -> value
        }
    }
}
