package me.kzheart.klib.config

import java.lang.reflect.Array as ReflectArray
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** Explicit, validated default YAML generation; never overwrites a live file. */
class ConfigDefaults private constructor() {
    companion object {
        @JvmStatic fun yaml(type: Class<*>): String = try {
            val visiting = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            val text = renderObject(ConfigTypePlan.of(type).defaults(), 0, visiting).ifEmpty { "{}\n" }
            YamlConfigMapper().read(YamlDocument.parse(type.name, text).root(), type)
            text
        } catch (failure: IllegalAccessException) {
            throw ConfigException("Cannot create defaults for ${type.name}", failure)
        }

        private fun renderObject(value: Any, indent: Int, visiting: MutableSet<Any>): String = visit(value, visiting) {
            buildString {
                for (declaration in ConfigTypePlan.of(value.javaClass).fields) {
                    if (declaration.ignored) continue
                    declaration.field.isAccessible = true
                    declaration.comment?.value?.forEach { line ->
                        line.split(Regex("\\R")).forEach { part -> append(" ".repeat(indent)).append("# ").append(part).append('\n') }
                    }
                    val child = declaration.field.get(value)
                    append(" ".repeat(indent)).append("'").append(declaration.name.replace("'", "''")).append("':")
                    if (child != null && isPojo(child)) {
                        val nested = renderObject(child, indent + 2, visiting)
                        if (nested.isEmpty()) append(" {}\n") else append('\n').append(nested)
                    } else append(' ').append(flow(normalize(child, visiting))).append('\n')
                }
            }
        }

        private fun isPojo(value: Any): Boolean = value !is String && value !is Number && value !is Boolean &&
            value !is Char && value !is Enum<*> && value !is Duration && value !is Map<*, *> &&
            value !is Iterable<*> && !value.javaClass.isArray

        private fun normalize(value: Any?, visiting: MutableSet<Any>): Any? = when (value) {
            null, is String, is Number, is Boolean -> value
            is Enum<*>, is Char, is Duration -> value.toString()
            else -> visit(value, visiting) {
                when {
                    value is Map<*, *> -> value.entries.associateTo(linkedMapOf<String, Any?>()) { (key, child) ->
                        if (key !is String) throw ConfigException("Config map keys must be String")
                        key to normalize(child, visiting)
                    }
                    value is Iterable<*> -> value.map { normalize(it, visiting) }
                    value.javaClass.isArray -> (0 until ReflectArray.getLength(value)).map { normalize(ReflectArray.get(value, it), visiting) }
                    else -> ConfigTypePlan.of(value.javaClass).fields.filterNot { it.ignored }
                        .associateTo(linkedMapOf()) {
                            it.field.isAccessible = true
                            it.name to normalize(it.field.get(value), visiting)
                        }
                }
            }
        }

        private inline fun <T> visit(value: Any, visiting: MutableSet<Any>, action: () -> T): T {
            if (!visiting.add(value)) throw ConfigException("Cyclic config defaults")
            return try { action() } finally { visiting.remove(value) }
        }

        private fun flow(value: Any?): String = Yaml(DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.FLOW
            defaultScalarStyle = DumperOptions.ScalarStyle.DOUBLE_QUOTED
            width = Int.MAX_VALUE
            setSplitLines(false)
        }).dump(value).trim()
    }
}
