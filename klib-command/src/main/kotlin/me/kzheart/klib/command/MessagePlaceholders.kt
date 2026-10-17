package me.kzheart.klib.command
import me.kzheart.klib.lang.Placeholders
internal class MessagePlaceholders private constructor() {
    companion object {
        @JvmStatic fun none(): Map<String, Any?> = emptyMap()
        @JvmStatic fun of(vararg pairs: Any?): Map<String, Any?> = Placeholders.of(*pairs)
    }
}
