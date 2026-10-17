package me.kzheart.klib.command
import me.kzheart.klib.lang.MessageCatalog
import me.kzheart.klib.lang.MessagePipeline
import me.kzheart.klib.lang.MessageRecipient
import me.kzheart.klib.lang.PlaceholderApi
import me.kzheart.klib.lang.RichText
import org.bukkit.command.CommandSender
class MessagePipelineCommandMessages(private val pipeline: MessagePipeline) : CommandMessages {
    constructor(catalog: MessageCatalog, prefix: String, placeholderApi: PlaceholderApi?) :
        this(MessagePipeline(catalog, prefix, placeholderApi) { _, _ -> })
    override fun resolve(sender: CommandSender, key: String, placeholders: Map<String, *>): RichText =
        pipeline.render(MessageRecipient.commandSender(sender), key, placeholders)
}
