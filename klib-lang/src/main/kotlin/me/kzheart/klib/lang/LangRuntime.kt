package me.kzheart.klib.lang

import me.kzheart.klib.config.api.ConfigDocument
import me.kzheart.klib.scope.Scope
import org.bukkit.command.CommandSender

class LangRuntime internal constructor(
    private val owner: Scope,
    private val locale: String,
    private val document: ConfigDocument<LanguageFile>,
    private val catalog: ReloadableMessageCatalog,
    private val pipeline: MessagePipeline
) {
    fun locale(): String = locale
    fun catalog(): MessageCatalog { ensureOpen(); return catalog }
    fun pipeline(): MessagePipeline { ensureOpen(); return pipeline }
    fun send(recipient: MessageRecipient, key: String): RichText {
        ensureOpen(); return pipeline.send(recipient, key)
    }
    fun send(recipient: MessageRecipient, key: String, placeholders: Map<String, *>): RichText {
        ensureOpen(); return pipeline.send(recipient, key, placeholders)
    }
    fun send(recipient: MessageRecipient, route: MessageRoute, key: String, placeholders: Map<String, *>): RichText {
        ensureOpen(); return pipeline.send(recipient, route, key, placeholders)
    }
    fun send(sender: CommandSender, key: String): RichText = send(MessageRecipient.commandSender(sender), key)
    fun send(sender: CommandSender, key: String, placeholders: Map<String, *>): RichText =
        send(MessageRecipient.commandSender(sender), key, placeholders)
    fun send(sender: CommandSender, route: MessageRoute, key: String, placeholders: Map<String, *>): RichText =
        send(MessageRecipient.commandSender(sender), route, key, placeholders)
    fun configDocument(): ConfigDocument<*> = document
    private fun ensureOpen() { check(!owner.isClosed) { "Language runtime is closed: $locale" } }

    internal class LanguageFile {
        @JvmField var messages: MutableMap<String, Any?>? = null
    }
}
