package me.kzheart.klib.script

import java.util.Collections
import java.util.Optional

class StatementArguments internal constructor(values: Map<String, String>) {
    private val values = Collections.unmodifiableMap(LinkedHashMap(values))
    fun require(name: String): String = values[name] ?: throw IllegalArgumentException("Missing combined argument: $name")
    fun get(name: String): Optional<String> = Optional.ofNullable(values[name])
    fun value(name: String, context: ScriptContext): Any? = InlineValues.value(require(name), context)
    fun asMap(): Map<String, String> = values
}
