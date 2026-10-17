package me.kzheart.klib.command
import java.util.Collections
internal class ArgumentException(private val key: String, vararg placeholderPairs: Any?) : Exception(key) {
    private val placeholders = Collections.unmodifiableMap(MessagePlaceholders.of(*placeholderPairs))
    fun key(): String = key
    fun placeholders(): Map<String, Any?> = placeholders
    companion object { private const val serialVersionUID = 1L }
}
